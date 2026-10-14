package com.lllhys.geeplay.vehicle.geely

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.vehicle.BluetoothAudioConflictPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface GeelyA2dpDisconnectResult {
    data object NothingConnected : GeelyA2dpDisconnectResult
    data class Disconnected(val address: String) : GeelyA2dpDisconnectResult
    data class Unavailable(val detail: String) : GeelyA2dpDisconnectResult
}

internal data class GeelyA2dpRestoreResult(
    val restored: Set<String>,
    val pending: Set<String>,
    val detail: String,
)

internal interface GeelyA2dpController {
    fun disconnectSingleConnected(): GeelyA2dpDisconnectResult
    fun restore(addresses: Set<String>): GeelyA2dpRestoreResult
}

internal fun interface GeelyA2dpRestoreRequester {
    fun request(address: String): Boolean
}

internal class FlymeBluetoothMusicRestoreRequester(
    context: Context,
    private val deviceSupported: () -> Boolean = { Build.MODEL.equals(SUPPORTED_MODEL, ignoreCase = true) },
    private val receiverAvailable: (ComponentName) -> Boolean = { component ->
        runCatching {
            context.packageManager.getReceiverInfo(component, 0).let { info ->
                info.exported && info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            }
        }.getOrDefault(false)
    },
    private val sendBroadcast: (Intent) -> Unit = context.applicationContext::sendBroadcast,
) : GeelyA2dpRestoreRequester {
    private val appContext = context.applicationContext
    private val component = ComponentName(OEM_PACKAGE, OEM_RECEIVER)

    override fun request(address: String): Boolean {
        if (!deviceSupported()) return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        if (!receiverAvailable(component)) return false
        return runCatching {
            sendBroadcast(
                Intent(ACTION_CHANGE_BLUETOOTH_SOURCE)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_FROM, appContext.packageName)
                    .putExtra(EXTRA_NEED_PLAY, true),
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        const val SUPPORTED_MODEL = "G636"
        const val OEM_PACKAGE = "com.flyme.auto.localmusic.bluetooth"
        const val OEM_RECEIVER = "com.flyme.auto.local.carcontroller.mediacenter.MediaCenterReceiver"
        const val ACTION_CHANGE_BLUETOOTH_SOURCE =
            "com.flyme.auto.localmusic.bluetooth.CHANGE_BT_SOURCE"
        const val EXTRA_FROM = "from"
        const val EXTRA_NEED_PLAY = "needPlay"
    }
}

internal class AndroidGeelyA2dpController(
    private val context: Context,
    private val oemRestoreRequester: GeelyA2dpRestoreRequester = FlymeBluetoothMusicRestoreRequester(context),
) : GeelyA2dpController {
    private val appContext = context.applicationContext

    override fun disconnectSingleConnected(): GeelyA2dpDisconnectResult {
        if (!hasBluetoothPermission()) return GeelyA2dpDisconnectResult.Unavailable("BLUETOOTH_CONNECT not granted")
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return GeelyA2dpDisconnectResult.Unavailable("Bluetooth adapter unavailable")
        return withA2dpSinkProxy(adapter) { proxy ->
            val devices = proxy.connectedDevices.orEmpty()
            when (devices.size) {
                0 -> GeelyA2dpDisconnectResult.NothingConnected
                1 -> {
                    val device = devices.single()
                    if (invokeConnection(proxy, "disconnect", device)) {
                        GeelyA2dpDisconnectResult.Disconnected(device.address)
                    } else {
                        GeelyA2dpDisconnectResult.Unavailable("A2DP sink disconnect rejected")
                    }
                }
                else -> GeelyA2dpDisconnectResult.Unavailable(
                    "multiple A2DP sink devices connected; refusing an ambiguous disconnect",
                )
            }
        } ?: GeelyA2dpDisconnectResult.Unavailable("A2DP sink profile unavailable")
    }

    override fun restore(addresses: Set<String>): GeelyA2dpRestoreResult {
        if (addresses.isEmpty()) return GeelyA2dpRestoreResult(emptySet(), emptySet(), "nothing to restore")
        if (!hasBluetoothPermission()) {
            return GeelyA2dpRestoreResult(emptySet(), addresses, "BLUETOOTH_CONNECT not granted")
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return GeelyA2dpRestoreResult(emptySet(), addresses, "Bluetooth adapter unavailable")
        return withA2dpSinkProxy(adapter) { proxy ->
            val connected = proxy.connectedDevices.orEmpty().mapTo(mutableSetOf(), BluetoothDevice::getAddress)
            val restored = LinkedHashSet<String>()
            val pending = LinkedHashSet<String>()
            var restoredThroughOem = false
            addresses.forEach { address ->
                if (address in connected) {
                    restored += address
                } else {
                    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                    val oemRestored = device != null &&
                        oemRestoreRequester.request(address) &&
                        waitForConnection(proxy, device)
                    val directRestored = device != null && !oemRestored &&
                        invokeConnection(proxy, "connect", device) &&
                        waitForConnection(proxy, device)
                    if (oemRestored || directRestored) {
                        restored += address
                        restoredThroughOem = restoredThroughOem || oemRestored
                    } else {
                        pending += address
                    }
                }
            }
            GeelyA2dpRestoreResult(
                restored,
                pending,
                when {
                    pending.isNotEmpty() -> "A2DP restore incomplete"
                    restoredThroughOem -> "A2DP restored through OEM Bluetooth music"
                    else -> "A2DP restored"
                },
            )
        } ?: GeelyA2dpRestoreResult(emptySet(), addresses, "A2DP sink profile unavailable")
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun <T> withA2dpSinkProxy(adapter: BluetoothAdapter, action: (BluetoothProfile) -> T): T? {
        val result = AtomicReference<Result<T>?>()
        val latch = CountDownLatch(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    result.set(runCatching { action(proxy) })
                } finally {
                    adapter.closeProfileProxy(profile, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                latch.countDown()
            }
        }
        if (!runCatching { adapter.getProfileProxy(appContext, listener, PROFILE_A2DP_SINK) }.getOrDefault(false)) {
            return null
        }
        if (!latch.await(PROFILE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return null
        return result.get()?.getOrNull()
    }

    private fun invokeConnection(proxy: BluetoothProfile, name: String, device: BluetoothDevice): Boolean = try {
        val method = proxy.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(BluetoothDevice::class.java))
        } ?: return false
        (method.invoke(proxy, device) as? Boolean) != false
    } catch (_: ReflectiveOperationException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun waitForConnection(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECTION_TIMEOUT_MILLIS)
        do {
            if (runCatching { proxy.getConnectionState(device) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED) ==
                BluetoothProfile.STATE_CONNECTED
            ) return true
            Thread.sleep(CONNECTION_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        return false
    }

    private companion object {
        // A phone sends media to the head unit, so Android exposes it under the hidden sink role.
        // BluetoothProfile.A2DP (2) is the opposite role and only lists devices this head unit sends to.
        const val PROFILE_A2DP_SINK = 11
        const val PROFILE_TIMEOUT_MILLIS = 4_000L
        const val CONNECTION_TIMEOUT_MILLIS = 12_000L
        const val CONNECTION_POLL_MILLIS = 100L
    }
}

internal class GeelyBluetoothMediaJournal(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun pending(): Set<String> = preferences.getStringSet(KEY_PENDING, emptySet()).orEmpty().toSet()

    @Synchronized
    fun add(address: String) {
        check(preferences.edit().putStringSet(KEY_PENDING, pending() + address).commit()) {
            "Could not persist pending Geely A2DP recovery"
        }
    }

    @Synchronized
    fun replace(addresses: Set<String>) {
        val editor = preferences.edit()
        if (addresses.isEmpty()) editor.remove(KEY_PENDING) else editor.putStringSet(KEY_PENDING, addresses)
        check(editor.commit()) { "Could not persist Geely A2DP recovery" }
    }

    private companion object {
        const val PREFERENCES = "geeplay_bluetooth_media_arbitration"
        const val KEY_PENDING = "pending_a2dp_devices"
    }
}

/** Disconnects only the head unit's OEM A2DP sink profile while GeePlay owns CarPlay music. */
class GeelyBluetoothAudioConflictPolicy internal constructor(
    private val controller: GeelyA2dpController,
    private val journal: GeelyBluetoothMediaJournal,
    private val worker: ScheduledExecutorService,
    private val onStatus: (String) -> Unit,
    private val restoreEnabled: () -> Boolean = { true },
    private val pollMillis: Long = POLL_MILLIS,
) : BluetoothAudioConflictPolicy {
    constructor(
        context: Context,
        onStatus: (String) -> Unit = GeelyIntegrationDiagnostics::bluetoothAudio,
    ) : this(
        AndroidGeelyA2dpController(context),
        GeelyBluetoothMediaJournal(context),
        newWorker(),
        onStatus,
        restoreEnabled = { GeelyMediaSettings.bluetoothAutoRestore(context) },
    )

    private val gate = Any()
    private var activeOwner: Any? = null
    private var activeCheck: (() -> Boolean)? = null
    private var generation = 0L
    private var poll: ScheduledFuture<*>? = null
    private var closed = false

    override fun acquire(owner: Any, stillActive: () -> Boolean) {
        val currentGeneration = synchronized(gate) {
            if (closed) return
            activeOwner = owner
            activeCheck = stillActive
            generation += 1
            poll?.cancel(false)
            generation
        }
        onStatus("A2DP arbitration requested")
        schedulePoll(currentGeneration, 0L)
    }

    override fun release(owner: Any) {
        val shouldRestore = synchronized(gate) {
            if (closed || activeOwner !== owner) return
            activeOwner = null
            activeCheck = null
            generation += 1
            poll?.cancel(false)
            poll = null
            true
        }
        if (shouldRestore) restoreIfEnabled()
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            activeOwner = null
            activeCheck = null
            generation += 1
            poll?.cancel(false)
            poll = null
        }
        restoreIfEnabled()
        worker.shutdown()
    }

    private fun schedulePoll(expectedGeneration: Long, delayMillis: Long) {
        synchronized(gate) {
            if (!current(expectedGeneration)) return
            poll = worker.schedule({ reconcile(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun reconcile(expectedGeneration: Long) {
        val stillActive = synchronized(gate) {
            if (!current(expectedGeneration)) return
            activeCheck
        }?.let { runCatching(it).getOrDefault(false) } == true
        if (!stillActive) {
            val owner = synchronized(gate) { activeOwner }
            if (owner != null) release(owner)
            return
        }
        when (val result = controller.disconnectSingleConnected()) {
            is GeelyA2dpDisconnectResult.Disconnected -> {
                journal.add(result.address)
                onStatus("A2DP sink media disconnected for CarPlay")
            }
            GeelyA2dpDisconnectResult.NothingConnected -> onStatus("A2DP sink media already clear")
            is GeelyA2dpDisconnectResult.Unavailable -> onStatus("A2DP avoidance unavailable: ${result.detail}")
        }
        if (synchronized(gate) { current(expectedGeneration) }) schedulePoll(expectedGeneration, pollMillis)
        else restoreInlineIfEnabled()
    }

    private fun restorePending() {
        val pending = journal.pending()
        if (pending.isEmpty()) {
            onStatus("A2DP arbitration idle")
            return
        }
        val result = controller.restore(pending)
        journal.replace(result.pending)
        onStatus(if (result.pending.isEmpty()) result.detail else "${result.detail}: ${result.pending.size} pending")
    }

    private fun restoreIfEnabled() {
        if (runCatching(restoreEnabled).getOrDefault(false)) {
            if (!worker.isShutdown) runCatching { worker.execute(::restorePending) }
        } else {
            onStatus("A2DP auto-restore disabled; media remains disconnected")
        }
    }

    private fun restoreInlineIfEnabled() {
        if (runCatching(restoreEnabled).getOrDefault(false)) {
            restorePending()
        } else {
            onStatus("A2DP auto-restore disabled; media remains disconnected")
        }
    }

    private fun current(expectedGeneration: Long): Boolean =
        !closed && generation == expectedGeneration && activeOwner != null

    companion object {
        private const val POLL_MILLIS = 3_000L

        private fun newWorker(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "geeplay-a2dp-arbitration").apply { isDaemon = true }
        }

        /** Best-effort recovery of an A2DP profile left disconnected by an interrupted process. */
        fun restoreOnAppOpen(
            context: Context,
            onStatus: (String) -> Unit = GeelyIntegrationDiagnostics::bluetoothAudio,
        ) {
            val journal = GeelyBluetoothMediaJournal(context)
            val pending = journal.pending()
            if (pending.isEmpty()) return
            if (!GeelyMediaSettings.bluetoothAutoRestore(context)) {
                onStatus("A2DP auto-restore disabled; ${pending.size} pending")
                return
            }
            val worker = newWorker()
            worker.execute {
                val result = AndroidGeelyA2dpController(context).restore(pending)
                journal.replace(result.pending)
                onStatus(if (result.pending.isEmpty()) result.detail else "${result.detail}: ${result.pending.size} pending")
                worker.shutdown()
            }
        }
    }
}
