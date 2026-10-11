package com.lllhys.geeplay.vehicle.geely

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.vehicle.VehicleHostControls
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class GeelyInputGesture {
    SHORT,
    LONG,
    DOUBLE,
}

data class GeelyInputEvent(
    val rawCode: Int,
    val gesture: GeelyInputGesture,
    val source: String,
)

enum class GeelyInputAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    SIRI,
}

object GeelyInputMapping {
    fun action(profile: GeelyInputProfile, event: GeelyInputEvent): GeelyInputAction? {
        if (event.gesture != GeelyInputGesture.SHORT) return null
        return when (profile) {
            GeelyInputProfile.DISABLED -> null
            GeelyInputProfile.FLYME_VOICE ->
                if (event.rawCode == 231 || event.rawCode == 200231) GeelyInputAction.SIRI else null
            GeelyInputProfile.ONE_OS -> media(event.rawCode)
            GeelyInputProfile.ECARX_XUI_DIAGNOSTIC,
            GeelyInputProfile.ONE_OS_2_DIAGNOSTIC,
            -> media(event.rawCode) ?: if (event.rawCode == 200231) GeelyInputAction.SIRI else null
        }
    }

    private fun media(rawCode: Int): GeelyInputAction? = when (rawCode) {
        OneOsWheelProtocol.KEY_PLAY_PAUSE -> GeelyInputAction.PLAY_PAUSE
        OneOsWheelProtocol.KEY_NEXT -> GeelyInputAction.NEXT
        OneOsWheelProtocol.KEY_PREVIOUS -> GeelyInputAction.PREVIOUS
        else -> null
    }
}

interface GeelyInputAdapter : Closeable {
    fun start()
}

class OneOsWheelProtocolException(message: String) : Exception(message)

object OneOsWheelProtocol {
    const val PACKAGE = "com.geely.service.oneosapi"
    val COMPONENT = ComponentName(PACKAGE, "$PACKAGE.OneOSApiService")
    const val MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.IServiceManager"
    const val INPUT_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputManager"
    const val LISTENER_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputListener"
    const val GET_SERVICE = 2
    const val INPUT_SERVICE_TYPE = 8
    const val REGISTER = 3
    const val UNREGISTER = 4
    const val KEY_PLAY_PAUSE = 200085
    const val KEY_NEXT = 200087
    const val KEY_PREVIOUS = 200088
    val mediaKeys = intArrayOf(KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS)

    fun getInputService(manager: IBinder): IBinder {
        requireDescriptor(manager, MANAGER_DESCRIPTOR)
        return transact(manager, GET_SERVICE, MANAGER_DESCRIPTOR, write = {
            it.writeInt(INPUT_SERVICE_TYPE)
        }, read = { reply ->
            reply.readStrongBinder() ?: throw OneOsWheelProtocolException("missing input Binder")
        }).also { requireDescriptor(it, INPUT_DESCRIPTOR) }
    }

    fun register(input: IBinder, listener: IBinder, packageName: String) {
        requireDescriptor(input, INPUT_DESCRIPTOR)
        transact(input, REGISTER, INPUT_DESCRIPTOR, write = {
            it.writeStrongBinder(listener)
            it.writeString(packageName)
            it.writeIntArray(mediaKeys)
        }, read = { Unit })
    }

    fun unregister(input: IBinder, listener: IBinder, packageName: String) {
        requireDescriptor(input, INPUT_DESCRIPTOR)
        transact(input, UNREGISTER, INPUT_DESCRIPTOR, write = {
            it.writeStrongBinder(listener)
            it.writeString(packageName)
        }, read = { Unit })
    }

    private fun requireDescriptor(binder: IBinder, expected: String) {
        if (binder.interfaceDescriptor != expected) {
            throw OneOsWheelProtocolException("unexpected interface descriptor")
        }
    }

    private fun <T> transact(
        binder: IBinder,
        code: Int,
        descriptor: String,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            if (!binder.transact(code, data, reply, 0)) {
                throw OneOsWheelProtocolException("transaction $code rejected")
            }
            reply.readException()
            val result = read(reply)
            if (reply.dataAvail() != 0) throw OneOsWheelProtocolException("unexpected reply fields for transaction $code")
            return result
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

internal class OneOsWheelListener(
    private val event: (Int, GeelyInputGesture) -> Unit,
) : Binder() {
    init {
        attachInterface(null, OneOsWheelProtocol.LISTENER_DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(OneOsWheelProtocol.LISTENER_DESCRIPTOR)
            return true
        }
        if (code !in 1..6) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(OneOsWheelProtocol.LISTENER_DESCRIPTOR)
        val expectedBytes = if (code == 1) 12 else 8
        if (data.dataAvail() != expectedBytes) throw OneOsWheelProtocolException("unexpected callback fields")
        val rawCode = data.readInt()
        data.readInt()
        if (code == 1) data.readInt()
        if (data.dataAvail() != 0) throw OneOsWheelProtocolException("unexpected callback fields")
        val gesture = when (code) {
            2 -> GeelyInputGesture.SHORT
            5 -> GeelyInputGesture.LONG
            6 -> GeelyInputGesture.DOUBLE
            else -> null
        }
        if (rawCode in OneOsWheelProtocol.mediaKeys && gesture != null) event(rawCode, gesture)
        reply?.writeNoException()
        return true
    }
}

class OneOsWheelAdapter(
    context: Context,
    private val onEvent: (GeelyInputEvent) -> Boolean,
    private val onStatus: (String) -> Unit = {},
    private val worker: ExecutorService = sharedWorker,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : GeelyInputAdapter {
    private data class Registration(
        val epoch: Long,
        val input: IBinder,
        val listener: OneOsWheelListener,
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

            override fun onServiceDisconnected(name: ComponentName) = connectionLost(generation, "service disconnected")
            override fun onBindingDied(name: ComponentName) = connectionLost(generation, "binding died")
            override fun onNullBinding(name: ComponentName) = connectionLost(generation, "service returned no binder")
        }
        connection = next
        bound = runCatching {
            appContext.bindService(Intent().setComponent(OneOsWheelProtocol.COMPONENT), next, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            connection = null
            notifyStatus("OneOS input service unavailable")
            scheduleReconnect(generation)
            return
        }
        timeout = Runnable {
            if (current(generation) && registration == null) connectionLost(generation, "OneOS service connection timed out")
        }.also { main.postDelayed(it, BIND_TIMEOUT_MILLIS) }
        notifyStatus("waiting for OneOS input service")
    }

    private fun connect(generation: Long, manager: IBinder) {
        if (!current(generation)) return
        var linkedInput: IBinder? = null
        var linkedDeath: IBinder.DeathRecipient? = null
        try {
            val input = OneOsWheelProtocol.getInputService(manager)
            synchronized(quarantined) {
                if (input in quarantined) throw OneOsWheelProtocolException("input Binder is quarantined")
            }
            val listener = OneOsWheelListener { rawCode, gesture ->
                if (current(generation)) onEvent(GeelyInputEvent(rawCode, gesture, "oneos"))
            }
            val death = IBinder.DeathRecipient {
                GeelyInputDiagnostics.binderDied()
                main.post { connectionLost(generation, "input Binder died") }
            }
            input.linkToDeath(death, 0)
            linkedInput = input
            linkedDeath = death
            OneOsWheelProtocol.register(input, listener, appContext.packageName)
            if (!current(generation)) {
                runCatching { OneOsWheelProtocol.unregister(input, listener, appContext.packageName) }
                input.unlinkToDeath(death, 0)
                return
            }
            registration = Registration(generation, input, listener, death)
            GeelyInputDiagnostics.registered()
            notifyStatus("OneOS media keys active")
        } catch (error: Exception) {
            if (registration == null && linkedInput != null && linkedDeath != null) {
                runCatching { linkedInput.unlinkToDeath(linkedDeath, 0) }
            }
            notifyStatus("OneOS input unavailable (${error.javaClass.simpleName})")
            main.post { connectionLost(generation, "OneOS input registration failed") }
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
            OneOsWheelProtocol.unregister(old.input, old.listener, appContext.packageName)
            GeelyInputDiagnostics.unregistered()
        }.onFailure {
            synchronized(quarantined) { quarantined += old.input }
            GeelyInputDiagnostics.quarantined()
            notifyStatus("OneOS listener cleanup unconfirmed (${it.javaClass.simpleName})")
        }
        runCatching { old.input.unlinkToDeath(old.deathRecipient, 0) }
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

    private fun current(generation: Long): Boolean = requested.get() && !closed.get() && epoch.get() == generation

    private fun executeWorker(action: () -> Unit) {
        try {
            worker.execute { runCatching(action) }
        } catch (_: RejectedExecutionException) {
            notifyStatus("OneOS input worker unavailable")
        }
    }

    private fun notifyStatus(message: String) {
        runCatching { onStatus(message) }
    }

    companion object {
        const val BIND_TIMEOUT_MILLIS = 8_000L
        const val RECONNECT_MILLIS = 2_000L
        private val sharedWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "geeplay-oneos-input").apply { isDaemon = true }
        }
        private val quarantined: MutableSet<IBinder> =
            Collections.newSetFromMap(IdentityHashMap())
    }
}

class FlymeVoiceBroadcastAdapter(
    context: Context,
    private val onEvent: (GeelyInputEvent) -> Boolean,
    private val onStatus: (String) -> Unit = {},
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : GeelyInputAdapter {
    private val appContext = context.applicationContext
    private val requested = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var receiver: BroadcastReceiver? = null
    private var lastAcceptedAt: Long? = null

    override fun start() {
        if (closed.get() || !requested.compareAndSet(false, true)) return
        main.post {
            if (!requested.get() || closed.get()) return@post
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!requested.get() || closed.get() || intent.action !in ACTIONS) return
                    if (!intent.hasExtra(ACTION_TYPE)) return
                    val actionType = intent.getIntExtra(ACTION_TYPE, Int.MIN_VALUE)
                    val timestamp = now()
                    val previous = lastAcceptedAt
                    if (actionType != 0 || previous != null && timestamp - previous < DEDUPLICATION_MILLIS) return
                    lastAcceptedAt = timestamp
                    onEvent(GeelyInputEvent(VOICE_KEY, GeelyInputGesture.SHORT, "flyme_voice_broadcast"))
                }
            }
            val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(created, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(created, filter)
                }
                receiver = created
                notifyStatus("Flyme voice input active; sender identity is not guaranteed")
            } catch (error: RuntimeException) {
                requested.set(false)
                notifyStatus("Flyme voice input unavailable (${error.javaClass.simpleName})")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        main.post {
            receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            receiver = null
        }
    }

    private fun notifyStatus(message: String) {
        runCatching { onStatus(message) }
    }

    companion object {
        const val RIGHT_VOICE_ACTION = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
        const val VOICE_ACTION = "ecarx.intent.action.ECARX_KEY_VOICEASSIST_EVENT"
        const val ACTION_TYPE = "ecarx.extra.ECARX_KEY_ACTION_TYPE"
        const val VOICE_KEY = 231
        const val DEDUPLICATION_MILLIS = 250L
        val ACTIONS = setOf(RIGHT_VOICE_ACTION, VOICE_ACTION)
    }
}

class GeelyInputRuntime(
    context: Context,
    private val onStatus: (String) -> Unit = {},
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val adapterFactory: (GeelyInputProfile, (GeelyInputEvent) -> Boolean, (String) -> Unit) -> GeelyInputAdapter? =
        { profile, event, status ->
            when (profile) {
                GeelyInputProfile.ONE_OS -> OneOsWheelAdapter(context, event, status)
                GeelyInputProfile.FLYME_VOICE -> FlymeVoiceBroadcastAdapter(context, event, status)
                else -> null
            }
        },
) : Closeable {
    private val appContext = context.applicationContext
    private var controls: VehicleHostControls? = null
    private var sessionToken: Any? = null
    private var adapter: GeelyInputAdapter? = null
    private var selected = GeelyInputProfile.DISABLED
    private var generation = 0L
    private var lastAction: GeelyInputAction? = null
    private var lastActionAt: Long? = null
    private var closed = false
    private val poll = Runnable(::reconcile)

    fun attachHostControls(next: VehicleHostControls) {
        main.post {
            if (!closed) {
                controls = next
                reconcile()
            }
        }
    }

    fun sessionStarted(token: Any) {
        main.post {
            if (closed) return@post
            sessionToken = token
            generation += 1
            reconcile()
        }
    }

    fun sessionEnded(token: Any) {
        main.post {
            if (sessionToken !== token) return@post
            sessionToken = null
            closeAdapter()
            onStatus("waiting for an active CarPlay session")
        }
    }

    fun settingsChanged() {
        main.post { reconcile() }
    }

    override fun close() {
        main.post {
            if (closed) return@post
            closed = true
            sessionToken = null
            controls = null
            closeAdapter()
            main.removeCallbacks(poll)
        }
    }

    private fun reconcile() {
        main.removeCallbacks(poll)
        if (closed) return
        val token = sessionToken
        val target = controls
        val profile = GeelyInputSettings.loadProfile(appContext)
        val mediaOwner = GeelyMediaSettings.loadOwner(appContext)
        val blockedReason = when {
            mediaOwner != MediaOutputOwner.DIPLAY -> "input remains with external media owner"
            profile == GeelyInputProfile.DISABLED -> "Geely input disabled"
            profile == GeelyInputProfile.ECARX_XUI_DIAGNOSTIC -> xuiDiagnostic()
            profile == GeelyInputProfile.ONE_OS_2_DIAGNOSTIC -> xuiDiagnostic()
            token == null || target == null -> "waiting for an active CarPlay session"
            target.isCommunicationActive() -> "platform input paused during a call"
            else -> null
        }
        if (blockedReason != null) {
            closeAdapter()
            onStatus(blockedReason)
        } else if (adapter == null || selected != profile) {
            closeAdapter()
            selected = profile
            val currentGeneration = generation
            adapter = adapterFactory(
                profile,
                { event -> receive(token!!, currentGeneration, profile, event) },
                onStatus,
            )
            runCatching { adapter?.start() }.onFailure {
                closeAdapter()
                onStatus("platform input unavailable (${it.javaClass.simpleName})")
            }
        }
        if (sessionToken != null) main.postDelayed(poll, POLL_MILLIS)
    }

    private fun receive(
        token: Any,
        expectedGeneration: Long,
        profile: GeelyInputProfile,
        event: GeelyInputEvent,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { receive(token, expectedGeneration, profile, event) }
            return true
        }
        val target = controls ?: return false
        if (closed || sessionToken !== token || generation != expectedGeneration ||
            GeelyMediaSettings.loadOwner(appContext) != MediaOutputOwner.DIPLAY ||
            GeelyInputSettings.loadProfile(appContext) != profile || target.isCommunicationActive()
        ) return false
        val action = GeelyInputMapping.action(profile, event) ?: return false
        val timestamp = SystemClock.uptimeMillis()
        val previousActionAt = lastActionAt
        if (lastAction == action && previousActionAt != null &&
            timestamp - previousActionAt < DEDUPLICATION_MILLIS
        ) return true
        lastAction = action
        lastActionAt = timestamp
        return when (action) {
            GeelyInputAction.PLAY_PAUSE -> target.sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
            GeelyInputAction.NEXT -> target.sendMediaButton(CarPlayMediaButton.NEXT)
            GeelyInputAction.PREVIOUS -> target.sendMediaButton(CarPlayMediaButton.PREVIOUS)
            GeelyInputAction.SIRI -> target.requestSiri()
        }
    }

    private fun closeAdapter() {
        generation += 1
        lastAction = null
        lastActionAt = null
        adapter?.let { runCatching { it.close() } }
        adapter = null
        selected = GeelyInputProfile.DISABLED
    }

    private fun xuiDiagnostic(): String = runCatching {
        Class.forName("com.ecarx.xui.adaptapi.input.Input", false, appContext.classLoader)
        Class.forName("com.ecarx.xui.adaptapi.input.IKeyCallback", false, appContext.classLoader)
        "XUI input SDK detected; interception remains disabled without a verified unregister contract"
    }.getOrElse {
        "XUI input SDK not detected"
    }

    private companion object {
        const val POLL_MILLIS = 500L
        const val DEDUPLICATION_MILLIS = 250L
    }
}
