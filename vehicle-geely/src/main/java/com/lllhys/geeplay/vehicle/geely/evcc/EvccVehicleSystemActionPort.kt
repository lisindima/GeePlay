package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.kooo.evcc.evccapi.EvccApiContract
import com.shilapi.xcertplay.vehicle.VehicleSystemAction
import com.shilapi.xcertplay.vehicle.VehicleSystemActionPort
import com.shilapi.xcertplay.vehicle.VehicleSystemActionResult
import java.util.concurrent.CompletableFuture

/** Explicit, on-car-confirmed EVCC Shell actions; never invoked automatically. */
class EvccVehicleSystemActionPort(
    context: Context,
    private val gateway: EvccVehicleControlGateway,
) : VehicleSystemActionPort {
    private val packageName = context.applicationContext.packageName
    @Volatile private var lastStatus = "not requested"

    override fun execute(action: VehicleSystemAction): CompletableFuture<VehicleSystemActionResult> {
        val command = when (action) {
            VehicleSystemAction.GRANT_WRITE_SETTINGS -> "appops set $packageName WRITE_SETTINGS allow"
            VehicleSystemAction.GRANT_OVERLAY -> "appops set $packageName SYSTEM_ALERT_WINDOW allow"
            VehicleSystemAction.ALLOW_BACKGROUND -> "appops set $packageName RUN_ANY_IN_BACKGROUND allow"
            VehicleSystemAction.ALLOW_DOZE -> "cmd deviceidle whitelist +$packageName"
        }
        lastStatus = "${action.name}: awaiting EVCC confirmation"
        return gateway.executeSystemShell(command).thenApply { shell ->
            VehicleSystemActionResult(
                accepted = shell.status == EvccApiContract.Status.OK && shell.exitCode == 0,
                status = shell.status,
                detail = shell.stderr.ifBlank { shell.stdout }.take(160),
            ).also { result -> lastStatus = "${action.name}: ${result.status} exit=${shell.exitCode}" }
        }.exceptionally { error ->
            VehicleSystemActionResult(false, EvccApiContract.Status.FAILED, error.javaClass.simpleName)
                .also { lastStatus = "${action.name}: ${it.status} ${it.detail}" }
        }
    }

    fun diagnostics(): String = "EVCC system actions: $lastStatus"
}
