package meshsurvey.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.LinkState
import meshsurvey.core.radio.RadioLink
import java.util.UUID

/**
 * Meshtastic phone API over BLE. FromRadio is read one message per characteristic read until
 * empty; FromNum notifications signal new data; ToRadio writes carry one message each.
 * All GATT operations are serialised through a queue on the main looper.
 */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val address: String, private val name: String) : RadioLink {
    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override val kind = LinkKind.BLE
    override val description = "Bluetooth $name"
    private val h = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var listener: RadioLink.Listener? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null
    private var closed = false
    private var connectedSignalled = false

    private sealed class Op { class Write(val b: ByteArray) : Op(); object Read : Op(); object Subscribe : Op() }
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private var readPending = false
    private var lastReadMs = 0L
    private var connectAttempts = 0

    override fun start(listener: RadioLink.Listener) {
        this.listener = listener
        listener.onLinkState(LinkState.CONNECTING, description)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { listener.onLinkState(LinkState.FAILED, "Bluetooth is off"); return }
        connectGatt()
    }

    private fun connectGatt() {
        connectAttempts++
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        gatt = adapter.getRemoteDevice(address).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Called about once per second. Polls FromRadio when idle, in case FromNum notifications
     * are not delivered (seen on some phones / bonding states); an empty read costs very little.
     */
    override fun tick() {
        if (closed || fromRadio == null || busy || queue.isNotEmpty()) return
        if (android.os.SystemClock.elapsedRealtime() - lastReadMs > 2000) enqueue(Op.Read)
    }

    private fun deliver(value: ByteArray) {
        try { listener?.onFromRadio(value) } catch (e: Exception) {
            listener?.onDebugText("error handling data from radio: ${e.message}")
        }
    }

    override fun send(toRadio: ByteArray): Boolean {
        if (closed || this.toRadio == null) return false
        enqueue(Op.Write(toRadio)); return true
    }

    override fun close() {
        closed = true
        h.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        listener?.onLinkState(LinkState.DISCONNECTED, "closed")
    }

    private fun enqueue(op: Op) {
        if (op is Op.Read) { if (readPending) return; readPending = true }
        queue.addLast(op); pump()
    }

    private fun pump() {
        if (busy || closed) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        busy = true
        val ok = when (op) {
            is Op.Write -> write(g, toRadio!!, op.b)
            Op.Read -> { readPending = false; lastReadMs = android.os.SystemClock.elapsedRealtime(); g.readCharacteristic(fromRadio) }
            Op.Subscribe -> subscribe(g)
        }
        if (!ok) { busy = false; h.postDelayed({ if (op is Op.Read) enqueue(Op.Read) else { queue.addFirst(op); pump() } }, 300) }
    }

    private fun done() { busy = false; pump() }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = v; g.writeCharacteristic(c) }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt): Boolean {
        val c = fromNum ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS else { d.value = v; g.writeDescriptor(d) }
    }

    private fun onRead(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener?.onLinkState(LinkState.FAILED, "Read failed ($status) — pair the node in Android Bluetooth settings (default PIN 123456)")
            done(); return
        }
        if (value.isNotEmpty()) { deliver(value); enqueue(Op.Read) }
        done()
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { h.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(512)) g.discoverServices()
            } else if (!closed && !connectedSignalled && connectAttempts < 3) {
                // Status 133 and similar first-connection failures are common on Android: retry quietly.
                listener?.onDebugText("Bluetooth connect failed ($status), retrying (${connectAttempts + 1} of 3)")
                try { g.close() } catch (_: Exception) {}
                gatt = null
                h.postDelayed({ if (!closed) connectGatt() }, 1500)
            } else if (!closed) {
                listener?.onLinkState(if (connectedSignalled) LinkState.DISCONNECTED else LinkState.FAILED, "Bluetooth link lost ($status)")
                try { g.close() } catch (_: Exception) {}
                gatt = null
            }
        } }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { h.post { if (g == gatt) g.discoverServices() } }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { h.post {
            if (g != gatt) return@post
            val s = g.getService(SERVICE)
            toRadio = s?.getCharacteristic(TO_RADIO); fromRadio = s?.getCharacteristic(FROM_RADIO); fromNum = s?.getCharacteristic(FROM_NUM)
            if (toRadio == null || fromRadio == null || fromNum == null) { listener?.onLinkState(LinkState.FAILED, "Not a Meshtastic device"); return@post }
            enqueue(Op.Subscribe)
            connectedSignalled = true
            listener?.onLinkState(LinkState.CONNECTED, description) // RadioClient now writes want_config
        } }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { h.post {
            if (g != gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) listener?.onLinkState(LinkState.FAILED, "Write failed ($status) — is the node paired?")
            else enqueue(Op.Read) // the node has data for us after each write (e.g. config download)
            done()
        } }

        @Deprecated("pre-33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) { val v = c.value?.copyOf() ?: ByteArray(0); h.post { if (g == gatt) onRead(v, status) } }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val v = value.copyOf(); h.post { if (g == gatt) onRead(v, status) }
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == FROM_NUM) h.post { if (g == gatt) enqueue(Op.Read) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROM_NUM) h.post { if (g == gatt) enqueue(Op.Read) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { h.post { if (g == gatt) done() } }
    }
}
