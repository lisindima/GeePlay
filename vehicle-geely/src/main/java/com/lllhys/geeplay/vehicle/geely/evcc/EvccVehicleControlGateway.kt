package com.lllhys.geeplay.vehicle.geely.evcc

import com.kooo.evcc.evccapi.EvccActionRequest
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccApiInfo
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPageRequest
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSessionInfo
import com.kooo.evcc.evccapi.EvccShellRequest
import com.kooo.evcc.evccapi.EvccShellResult
import com.kooo.evcc.evccapi.EvccValue
import com.kooo.evcc.evccapi.EvccWriteRequest
import com.kooo.evcc.evccapi.client.EvccApiClient
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Privileged EVCC surface, kept separate from the read-only vehicle-data adapter. */
class EvccVehicleControlGateway(core: EvccConnectionCore) : Closeable {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "geeplay-evcc-control").apply { isDaemon = true }
    }
    private val readinessLock = Any()
    private val readiness = ArrayList<Pair<Set<String>, CompletableFuture<Boolean>>>()
    private val shellResults = ConcurrentHashMap<String, CompletableFuture<EvccShellResult>>()
    @Volatile private var permissions = emptySet<String>()
    @Volatile private var compatible = false
    @Volatile private var closed = false

    private val listener = object : EvccApiClient.Listener {
        override fun onSessionReady(apiInfo: EvccApiInfo, sessionInfo: EvccSessionInfo) {
            compatible = apiInfo.apiMajor == 1 && apiInfo.apiMinor >= 2
            permissions = sessionInfo.grantedPermissions.toSet()
            completeReadiness()
        }

        override fun onSessionClosed(reason: EvccOperationResult) {
            compatible = false
            permissions = emptySet()
        }

        override fun onShellCompleted(result: EvccShellResult) {
            shellResults.remove(result.requestId)?.complete(result)
        }

        override fun onError(operation: String, error: Throwable) {
            if (operation == "connect") completeReadiness(force = false)
        }
    }
    private val port = core.controlPort(listener)

    fun directory(query: String): CompletableFuture<List<EvccPropertyDescriptor>> =
        submit(setOf(EvccApiContract.Permission.PROPERTY_DIRECTORY)) {
            val result = LinkedHashMap<String, EvccPropertyDescriptor>()
            var pageToken = ""
            var snapshotToken = ""
            var pageCount = 0
            do {
                val page = port.queryPropertyDirectory(
                    EvccPageRequest(
                        requestId = "geeplay-control-${UUID.randomUUID()}",
                        pageToken = pageToken,
                        query = query,
                        limit = EvccApiContract.MAX_PAGE_SIZE,
                        snapshotToken = snapshotToken,
                    ),
                )
                if (page.status !in SUCCESS_STATUSES) break
                if (snapshotToken.isEmpty()) snapshotToken = page.snapshotToken
                page.items.mapNotNull(EvccPropertyRecord::descriptor).forEach { descriptor ->
                    result["${descriptor.source}:${descriptor.canonicalId}:${descriptor.areaId}"] = descriptor
                }
                pageToken = page.nextPageToken
                pageCount += 1
            } while (pageToken.isNotEmpty() && pageCount < MAX_DIRECTORY_PAGES)
            result.values.toList()
        }

    fun read(selector: EvccPropertySelector, forceRefresh: Boolean = true): CompletableFuture<EvccPropertyRecord> =
        submit(setOf(EvccApiContract.Permission.PROPERTY_READ)) {
            port.readProperty(selector, forceRefresh)
        }

    fun write(selector: EvccPropertySelector, value: EvccValue, validateOnly: Boolean): CompletableFuture<EvccOperationResult> =
        submit(setOf(EvccApiContract.Permission.PROPERTY_WRITE)) {
            port.writeProperty(
                EvccWriteRequest(
                    requestId = UUID.randomUUID().toString(),
                    selector = selector,
                    value = value,
                    validateOnly = validateOnly,
                ),
            )
        }

    fun executeAction(actionId: String, argumentsJson: String, validateOnly: Boolean = false):
        CompletableFuture<EvccOperationResult> = submit(setOf(EvccApiContract.Permission.ACTION_SAFE)) {
        port.executeAction(
            EvccActionRequest(
                requestId = UUID.randomUUID().toString(),
                actionId = actionId,
                argumentsJson = argumentsJson,
                validateOnly = validateOnly,
            ),
        )
    }

    /** User-triggered only. System writes still require EVCC's on-car confirmation. */
    fun executeSystemShell(command: String, timeoutMs: Long = 10_000L): CompletableFuture<EvccShellResult> {
        require(SAFE_SYSTEM_COMMAND.matches(command)) { "Shell command is outside GeePlay's allowlist" }
        val result = CompletableFuture<EvccShellResult>()
        ensure(setOf(EvccApiContract.Permission.SHELL, EvccApiContract.Permission.SYSTEM_WRITE))
            .whenComplete { ready, error ->
                if (error != null || ready != true) {
                    result.completeExceptionally(error ?: SecurityException("EVCC Shell authorization required"))
                    return@whenComplete
                }
                executeWorker {
                    val id = UUID.randomUUID().toString()
                    shellResults[id] = result
                    val accepted = port.executeShell(EvccShellRequest(id, command, timeoutMs, includeRawOutput = false))
                    if (accepted.status !in setOf(EvccApiContract.Status.ACCEPTED, EvccApiContract.Status.PENDING)) {
                        shellResults.remove(id)
                        result.complete(
                            EvccShellResult(id, accepted.status, stderr = accepted.message),
                        )
                    }
                }
            }
        return result
    }

    fun cancel(requestId: String): CompletableFuture<EvccOperationResult> = submit(emptySet()) {
        port.cancel(requestId)
    }

    fun grantedPermissions(): Set<String> = permissions

    private fun ensure(required: Set<String>): CompletableFuture<Boolean> {
        if (closed) return CompletableFuture.completedFuture(false)
        if (compatible && required.all(permissions::contains)) return CompletableFuture.completedFuture(true)
        val future = CompletableFuture<Boolean>()
        synchronized(readinessLock) { readiness += required to future }
        port.connect()
        worker.schedule({ future.complete(false) }, CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return future
    }

    private fun completeReadiness(force: Boolean? = null) {
        val pending = synchronized(readinessLock) { readiness.toList().also { readiness.clear() } }
        pending.forEach { (required, future) ->
            future.complete(force ?: (compatible && required.all(permissions::contains)))
        }
    }

    private fun <T> submit(required: Set<String>, action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        ensure(required).whenComplete { ready, error ->
            if (error != null || ready != true) {
                result.completeExceptionally(error ?: SecurityException("EVCC authorization required: $required"))
            } else {
                executeWorker {
                    runCatching(action).fold(result::complete, result::completeExceptionally)
                }
            }
        }
        return result
    }

    private fun executeWorker(action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (error: RejectedExecutionException) {
            // The caller's future will already fail on a closed gateway; no work remains here.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        completeReadiness(force = false)
        shellResults.values.forEach { it.completeExceptionally(IllegalStateException("EVCC control closed")) }
        shellResults.clear()
        port.close()
        worker.shutdownNow()
    }

    private companion object {
        const val MAX_DIRECTORY_PAGES = 10
        const val CONNECTION_TIMEOUT_SECONDS = 15L
        val SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
        val SAFE_SYSTEM_COMMAND = Regex(
            "^(?:appops set [A-Za-z0-9._]+ (?:WRITE_SETTINGS|SYSTEM_ALERT_WINDOW|RUN_ANY_IN_BACKGROUND|RUN_IN_BACKGROUND) allow|" +
                "cmd deviceidle whitelist \\+[A-Za-z0-9._]+)$",
        )
    }
}
