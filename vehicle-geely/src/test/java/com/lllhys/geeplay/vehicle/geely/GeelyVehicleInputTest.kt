package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.vehicle.VehicleHostControls
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GeelyVehicleInputTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun clearSettings() {
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.DISABLED)
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.OEM_BLUETOOTH)
    }

    @Test
    fun `OneOS protocol gets input service and registers exact media keys`() {
        val input = RecordingInputBinder()
        val manager = RecordingManagerBinder(input)
        val listener = Binder()

        val resolved = OneOsWheelProtocol.getInputService(manager)
        OneOsWheelProtocol.register(resolved, listener, "com.lllhys.geeplay")
        OneOsWheelProtocol.unregister(resolved, listener, "com.lllhys.geeplay")

        assertSame(input, resolved)
        assertEquals(OneOsWheelProtocol.INPUT_SERVICE_TYPE, manager.requestedType)
        assertSame(listener, input.listener)
        assertEquals("com.lllhys.geeplay", input.packageName)
        assertArrayEquals(OneOsWheelProtocol.mediaKeys, input.keys)
        assertTrue(input.unregistered)
    }

    @Test
    fun `OneOS listener emits only supported short long and double gestures`() {
        val events = ArrayList<GeelyInputEvent>()
        val listener = OneOsWheelListener { code, gesture -> events += GeelyInputEvent(code, gesture, "test") }

        sendListenerEvent(listener, 2, OneOsWheelProtocol.KEY_PLAY_PAUSE)
        sendListenerEvent(listener, 5, OneOsWheelProtocol.KEY_NEXT)
        sendListenerEvent(listener, 6, OneOsWheelProtocol.KEY_PREVIOUS)
        sendListenerEvent(listener, 2, 123)

        assertEquals(
            listOf(GeelyInputGesture.SHORT, GeelyInputGesture.LONG, GeelyInputGesture.DOUBLE),
            events.map { it.gesture },
        )
    }

    @Test
    fun `mapping ignores long gestures and maps documented OneOS keys`() {
        assertEquals(
            GeelyInputAction.PLAY_PAUSE,
            GeelyInputMapping.action(
                GeelyInputProfile.ONE_OS,
                GeelyInputEvent(OneOsWheelProtocol.KEY_PLAY_PAUSE, GeelyInputGesture.SHORT, "oneos"),
            ),
        )
        assertEquals(
            GeelyInputAction.NEXT,
            GeelyInputMapping.action(
                GeelyInputProfile.ONE_OS,
                GeelyInputEvent(OneOsWheelProtocol.KEY_NEXT, GeelyInputGesture.SHORT, "oneos"),
            ),
        )
        assertEquals(
            null,
            GeelyInputMapping.action(
                GeelyInputProfile.ONE_OS,
                GeelyInputEvent(OneOsWheelProtocol.KEY_NEXT, GeelyInputGesture.LONG, "oneos"),
            ),
        )
    }

    @Test
    fun `Flyme aliases are deduplicated`() {
        var now = 1_000L
        val events = ArrayList<GeelyInputEvent>()
        val adapter = FlymeVoiceBroadcastAdapter(context, { events += it; true }, now = { now })
        adapter.start()
        shadowOf(Looper.getMainLooper()).idle()

        context.sendBroadcast(Intent(FlymeVoiceBroadcastAdapter.RIGHT_VOICE_ACTION).putExtra(FlymeVoiceBroadcastAdapter.ACTION_TYPE, 0))
        context.sendBroadcast(Intent(FlymeVoiceBroadcastAdapter.VOICE_ACTION).putExtra(FlymeVoiceBroadcastAdapter.ACTION_TYPE, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.size)

        now += FlymeVoiceBroadcastAdapter.DEDUPLICATION_MILLIS
        context.sendBroadcast(Intent(FlymeVoiceBroadcastAdapter.VOICE_ACTION).putExtra(FlymeVoiceBroadcastAdapter.ACTION_TYPE, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, events.size)
        adapter.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `runtime owns input only for local media outside calls`() {
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.DIPLAY)
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.ONE_OS)
        var created: RecordingAdapter? = null
        val controls = RecordingControls()
        val runtime = GeelyInputRuntime(context, adapterFactory = { _, callback, _ ->
            RecordingAdapter(callback).also { created = it }
        })
        val session = Any()

        runtime.attachHostControls(controls)
        runtime.sessionStarted(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(created!!.started)

        created!!.emit(GeelyInputEvent(OneOsWheelProtocol.KEY_NEXT, GeelyInputGesture.SHORT, "oneos"))
        assertEquals(listOf(CarPlayMediaButton.NEXT), controls.media)

        controls.inCall = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(created!!.closed)
        runtime.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `changing media owner detaches the active input adapter`() {
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.DIPLAY)
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.ONE_OS)
        var created: RecordingAdapter? = null
        val runtime = GeelyInputRuntime(context, adapterFactory = { _, callback, _ ->
            RecordingAdapter(callback).also { created = it }
        })
        runtime.attachHostControls(RecordingControls())
        runtime.sessionStarted(Any())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(created!!.started)

        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.OEM_BLUETOOTH)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertTrue(created!!.closed)
        runtime.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `OEM Bluetooth owner does not attach a vehicle input adapter`() {
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.OEM_BLUETOOTH)
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.ONE_OS)
        var created = false
        val runtime = GeelyInputRuntime(context, adapterFactory = { _, _, _ ->
            created = true
            RecordingAdapter { true }
        })

        runtime.attachHostControls(RecordingControls())
        runtime.sessionStarted(Any())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(created)
        runtime.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `any non local media owner leaves vehicle input untouched`() {
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.PHONE_OR_EXTERNAL)
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.ONE_OS)
        var created = false
        val runtime = GeelyInputRuntime(context, adapterFactory = { _, _, _ ->
            created = true
            RecordingAdapter { true }
        })

        runtime.attachHostControls(RecordingControls())
        runtime.sessionStarted(Any())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(created)
        runtime.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `OneOS disconnect invalidates the old listener and reconnects once`() {
        val binding = RecordingBindingContext(context)
        val input = RecordingInputBinder()
        val manager = RecordingManagerBinder(input)
        val events = ArrayList<GeelyInputEvent>()
        val adapter = OneOsWheelAdapter(binding, { events += it; true }, worker = ImmediateExecutor())

        adapter.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, binding.bindCount)
        binding.connect(manager)
        val staleListener = input.listener!!

        binding.disconnect()
        sendListenerEvent(staleListener, 2, OneOsWheelProtocol.KEY_NEXT)
        assertTrue(events.isEmpty())
        assertTrue(input.unregistered)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OneOsWheelAdapter.RECONNECT_MILLIS))
        assertEquals(2, binding.bindCount)

        adapter.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `OneOS bind timeout unbinds and retries with a new epoch`() {
        val binding = RecordingBindingContext(context)
        val statuses = ArrayList<String>()
        val adapter = OneOsWheelAdapter(binding, { true }, statuses::add, ImmediateExecutor())

        adapter.start()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OneOsWheelAdapter.BIND_TIMEOUT_MILLIS))
        assertEquals(1, binding.unbindCount)
        assertTrue(statuses.any { it.contains("timed out") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OneOsWheelAdapter.RECONNECT_MILLIS))
        assertEquals(2, binding.bindCount)

        adapter.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `failed unregister quarantines the Binder before reconnect`() {
        val binding = RecordingBindingContext(context)
        val input = RecordingInputBinder().apply { rejectUnregister = true }
        val manager = RecordingManagerBinder(input)
        val adapter = OneOsWheelAdapter(binding, { true }, worker = ImmediateExecutor())

        adapter.start()
        shadowOf(Looper.getMainLooper()).idle()
        binding.connect(manager)
        assertEquals(1, input.registerCount)
        binding.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OneOsWheelAdapter.RECONNECT_MILLIS))
        binding.connect(manager)

        assertEquals("A quarantined Binder must not be registered twice", 1, input.registerCount)
        adapter.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RecordingAdapter(private val callback: (GeelyInputEvent) -> Boolean) : GeelyInputAdapter {
        var started = false
        var closed = false
        override fun start() { started = true }
        override fun close() { closed = true }
        fun emit(event: GeelyInputEvent): Boolean = callback(event)
    }

    private class RecordingControls : VehicleHostControls {
        val media = ArrayList<Int>()
        var siri = 0
        var inCall = false
        override fun setClusterUiShown(shown: Boolean) = Unit
        override fun sendMediaButton(index: Int): Boolean { media += index; return true }
        override fun requestSiri(): Boolean { siri += 1; return true }
        override fun isCommunicationActive(): Boolean = inCall
    }

    private class RecordingManagerBinder(private val input: IBinder) : Binder() {
        var requestedType = -1
        init { attachInterface(null, OneOsWheelProtocol.MANAGER_DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(OneOsWheelProtocol.MANAGER_DESCRIPTOR)
                return true
            }
            data.enforceInterface(OneOsWheelProtocol.MANAGER_DESCRIPTOR)
            requestedType = data.readInt()
            reply!!.writeNoException()
            reply.writeStrongBinder(input)
            return true
        }
    }

    private class RecordingInputBinder : Binder() {
        var listener: IBinder? = null
        var packageName: String? = null
        var keys: IntArray? = null
        var unregistered = false
        var registerCount = 0
        var rejectUnregister = false
        init { attachInterface(null, OneOsWheelProtocol.INPUT_DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(OneOsWheelProtocol.INPUT_DESCRIPTOR)
                return true
            }
            data.enforceInterface(OneOsWheelProtocol.INPUT_DESCRIPTOR)
            when (code) {
                OneOsWheelProtocol.REGISTER -> {
                    registerCount += 1
                    listener = data.readStrongBinder()
                    packageName = data.readString()
                    keys = data.createIntArray()
                }
                OneOsWheelProtocol.UNREGISTER -> {
                    assertSame(listener, data.readStrongBinder())
                    assertEquals(packageName, data.readString())
                    unregistered = true
                    if (rejectUnregister) return false
                }
            }
            reply?.writeNoException()
            return true
        }
    }

    private class RecordingBindingContext(base: Context) : ContextWrapper(base) {
        var connection: ServiceConnection? = null
        var bindCount = 0
        var unbindCount = 0

        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            bindCount += 1
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            if (connection === conn) connection = null
            unbindCount += 1
        }

        fun connect(manager: IBinder) {
            connection!!.onServiceConnected(OneOsWheelProtocol.COMPONENT, manager)
        }

        fun disconnect() {
            connection!!.onServiceDisconnected(OneOsWheelProtocol.COMPONENT)
        }
    }

    private class ImmediateExecutor : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private fun sendListenerEvent(listener: IBinder, transaction: Int, rawCode: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OneOsWheelProtocol.LISTENER_DESCRIPTOR)
            data.writeInt(rawCode)
            data.writeInt(0)
            listener.transact(transaction, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
