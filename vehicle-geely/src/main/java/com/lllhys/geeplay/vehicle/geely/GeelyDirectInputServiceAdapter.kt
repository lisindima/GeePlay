package com.lllhys.geeplay.vehicle.geely

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * G636/FX11 input service adapter.
 *
 * This firmware exposes the input manager directly as `com.geely.inputservice/.InputService`,
 * unlike [OneOsWheelAdapter]'s service-manager protocol. Both variants feed the same product input
 * runtime, so session, media-owner, call-state and duplicate-event gates remain centralized.
 */
class GeelyDirectInputServiceAdapter(
    context: Context,
    private val onEvent: (GeelyInputEvent) -> Boolean,
    private val onStatus: (String) -> Unit = {},
    private val worker: ExecutorService = sharedWorker,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : GeelyInputAdapter {
    private data class Registration(
        val epoch: Long,
        val binder: IBinder,
        val manager: IInputManager,
        val listener: IInputListener,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val appContext = context.applicationContext
    private val requested = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val epoch = AtomicLong()
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var bound = false
    @Volatile private var registration: Registration? = null
    private var timeout: Runnable? = null
    private var reconnect: Runnable? = null

    override fun start() {
        if (closed.get() || !requested.compareAndSet(false, true)) return
        val generation = epoch.incrementAndGet()
        main.post { bind(generation) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        epoch.incrementAndGet()
        main.post {
            cancelTimers()
            val oldConnection = connection
            connection = null
            if (bound && oldConnection != null) runCatching { appContext.unbindService(oldConnection) }
            bound = false
            executeWorker(::cleanupRegistration)
        }
    }

    private fun bind(generation: Long) {
        if (!current(generation) || connection != null) return
        GeelyInputDiagnostics.bindAttempted()
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                timeout?.let(main::removeCallbacks)
                timeout = null
                executeWorker { connect(generation, service) }
            }

            override fun onServiceDisconnected(name: ComponentName) = connectionLost(generation, "direct input disconnected")
            override fun onBindingDied(name: ComponentName) = connectionLost(generation, "direct input binding died")
            override fun onNullBinding(name: ComponentName) = connectionLost(generation, "direct input returned no Binder")
        }
        connection = next
        bound = runCatching {
            appContext.bindService(
                Intent(ACTION).addCategory(CATEGORY).setComponent(COMPONENT),
                next,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            connection = null
            notifyStatus("G636 direct input service unavailable")
            scheduleReconnect(generation)
            return
        }
        timeout = Runnable {
            if (current(generation) && registration == null) connectionLost(generation, "direct input timed out")
        }.also { main.postDelayed(it, BIND_TIMEOUT_MILLIS) }
        notifyStatus("waiting for G636 direct input service")
    }

    private fun connect(generation: Long, binder: IBinder) {
        if (!current(generation)) return
        var linkedDeath: IBinder.DeathRecipient? = null
        try {
            check(binder.interfaceDescriptor == INPUT_DESCRIPTOR) { "unexpected direct input descriptor" }
            synchronized(quarantined) {
                if (binder in quarantined) error("direct input Binder is quarantined")
            }
            val manager = IInputManager.Stub.asInterface(binder)
                ?: error("direct input manager unavailable")
            val listener = object : IInputListener.Stub() {
                override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) = Unit
                override fun onShortClick(keyCode: Int, softKeyFunction: Int) =
                    emit(generation, keyCode, GeelyInputGesture.SHORT)
                override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) = Unit
                override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) = Unit
                override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) =
                    emit(generation, keyCode, GeelyInputGesture.LONG)
                override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) =
                    emit(generation, keyCode, GeelyInputGesture.DOUBLE)
            }
            val death = IBinder.DeathRecipient {
                GeelyInputDiagnostics.binderDied()
                main.post { connectionLost(generation, "direct input Binder died") }
            }
            binder.linkToDeath(death, 0)
            linkedDeath = death
            manager.registerListener(listener, appContext.packageName, REQUESTED_KEYS)
            if (!current(generation)) {
                runCatching { manager.unregisterListener(listener, appContext.packageName) }
                binder.unlinkToDeath(death, 0)
                return
            }
            registration = Registration(generation, binder, manager, listener, death)
            GeelyInputDiagnostics.registered()
            notifyStatus("G636 direct media and voice keys active")
        } catch (error: Exception) {
            linkedDeath?.let { runCatching { binder.unlinkToDeath(it, 0) } }
            notifyStatus("G636 direct input unavailable (${error.javaClass.simpleName})")
            main.post { connectionLost(generation, "direct input registration failed") }
        }
    }

    private fun emit(generation: Long, rawCode: Int, gesture: GeelyInputGesture) {
        if (current(generation) && rawCode in REQUESTED_KEYS) {
            onEvent(GeelyInputEvent(rawCode, gesture, "geely_inputservice"))
        }
    }

    private fun connectionLost(generation: Long, message: String) {
        if (!current(generation)) return
        val nextGeneration = epoch.incrementAndGet()
        timeout?.let(main::removeCallbacks)
        timeout = null
        val old = connection
        connection = null
        if (bound && old != null) runCatching { appContext.unbindService(old) }
        bound = false
        notifyStatus(message)
        executeWorker {
            cleanupRegistration()
            main.post { scheduleReconnect(nextGeneration) }
        }
    }

    private fun cleanupRegistration() {
        val old = registration ?: return
        registration = null
        GeelyInputDiagnostics.unregisterAttempted()
        runCatching {
            old.manager.unregisterListener(old.listener, appContext.packageName)
            GeelyInputDiagnostics.unregistered()
        }.onFailure {
            synchronized(quarantined) { quarantined += old.binder }
            GeelyInputDiagnostics.quarantined()
            notifyStatus("G636 direct listener cleanup unconfirmed (${it.javaClass.simpleName})")
        }
        runCatching { old.binder.unlinkToDeath(old.deathRecipient, 0) }
    }

    private fun scheduleReconnect(generation: Long) {
        if (!current(generation) || reconnect != null) return
        reconnect = Runnable {
            reconnect = null
            if (current(generation)) bind(generation)
        }.also { main.postDelayed(it, RECONNECT_MILLIS) }
    }

    private fun cancelTimers() {
        timeout?.let(main::removeCallbacks)
        reconnect?.let(main::removeCallbacks)
        timeout = null
        reconnect = null
    }

    private fun current(generation: Long): Boolean =
        requested.get() && !closed.get() && epoch.get() == generation

    private fun executeWorker(action: () -> Unit) {
        try {
            worker.execute { runCatching(action) }
        } catch (_: RejectedExecutionException) {
            notifyStatus("G636 direct input worker unavailable")
        }
    }

    private fun notifyStatus(message: String) {
        runCatching { onStatus(message) }
    }

    companion object {
        const val INPUT_PACKAGE = "com.geely.inputservice"
        val COMPONENT = ComponentName(INPUT_PACKAGE, "$INPUT_PACKAGE.InputService")
        const val ACTION = "android.intent.action.GEELY_INPUT_SERVICE"
        const val CATEGORY = "android.intent.category.GEELY_INPUT"
        const val INPUT_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputManager"
        const val BIND_TIMEOUT_MILLIS = 8_000L
        const val RECONNECT_MILLIS = 2_000L
        const val KEY_VOICE_ASSIST = 200_231
        val REQUESTED_KEYS = intArrayOf(
            OneOsWheelProtocol.KEY_PLAY_PAUSE,
            OneOsWheelProtocol.KEY_NEXT,
            OneOsWheelProtocol.KEY_PREVIOUS,
            KEY_VOICE_ASSIST,
        )

        @Suppress("DEPRECATION")
        fun available(context: Context): Boolean = runCatching {
            context.packageManager.getServiceInfo(COMPONENT, 0)
        }.isSuccess

        private val sharedWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "geeplay-direct-input").apply { isDaemon = true }
        }
        private val quarantined: MutableSet<IBinder> =
            Collections.newSetFromMap(IdentityHashMap())
    }
}
