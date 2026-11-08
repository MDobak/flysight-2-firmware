package com.example.flysightstatus

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * GATT connection to one FlySight.
 *
 * Android permits only one GATT operation at a time, so all operations go
 * through a queue. All listener calls occur on the main thread. The caller
 * must have the BLUETOOTH_CONNECT permission.
 */
@SuppressLint("MissingPermission")
class FlySightConnection(
    private val context: Context,
    val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: State, message: String?)

        /** A read result or a notification/indication. */
        fun onValue(characteristic: UUID, value: ByteArray)

        fun onLog(message: String)
    }

    enum class State { CONNECTING, DISCOVERING, READY, DISCONNECTED }

    /** Negotiated ATT MTU. The maximum value length is MTU - 3. */
    var mtu = 23
        private set

    private class Operation(val name: String, val start: (BluetoothGatt) -> Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val operations = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var gatt: BluetoothGatt? = null
    private var closed = false

    /** If a callback does not come, continue with the next operation. */
    private val operationTimeout = Runnable {
        current?.let { listener.onLog("No callback for ${it.name}") }
        operationDone()
    }

    fun connect() {
        listener.onStateChanged(State.CONNECTING, null)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Disconnect and release the connection. The object cannot be used again. */
    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        operations.clear()
        current = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID) =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    fun read(service: UUID, characteristic: UUID) = enqueue("read ${shortName(characteristic)}") { g ->
        val c = find(g, service, characteristic) ?: return@enqueue false
        g.readCharacteristic(c)
    }

    fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean = true) =
        enqueue("write ${shortName(characteristic)}") { g ->
            val c = find(g, service, characteristic) ?: return@enqueue false
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        }

    /** Enable or disable notifications. Uses indications if the characteristic has no notify property. */
    fun setNotifications(service: UUID, characteristic: UUID, enable: Boolean) =
        enqueue("${if (enable) "subscribe" else "unsubscribe"} ${shortName(characteristic)}") { g ->
            val c = find(g, service, characteristic) ?: return@enqueue false
            val d = c.getDescriptor(FlySightProtocol.CCCD) ?: return@enqueue false
            if (!g.setCharacteristicNotification(c, enable)) return@enqueue false
            val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                notify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        }

    private fun find(g: BluetoothGatt, service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        g.getService(service)?.getCharacteristic(characteristic)

    private fun enqueue(name: String, start: (BluetoothGatt) -> Boolean) {
        if (closed) return
        operations.addLast(Operation(name, start))
        runNext()
    }

    private fun runNext() {
        if (current != null) return
        val g = gatt ?: return
        while (operations.isNotEmpty()) {
            val op = operations.removeFirst()
            if (op.start(g)) {
                current = op
                handler.postDelayed(operationTimeout, OPERATION_TIMEOUT_MS)
                return
            }
            listener.onLog("Could not ${op.name}")
        }
    }

    private fun operationDone() {
        handler.removeCallbacks(operationTimeout)
        current = null
        runNext()
    }

    /**
     * Android keeps the GATT table of bonded devices in a cache. A firmware
     * update can change the table, so clear the cache before discovery.
     * This is a hidden API. If it is not available, discovery uses the cache.
     */
    private fun refreshGattCache(g: BluetoothGatt) {
        try {
            g.javaClass.getMethod("refresh").invoke(g)
        } catch (_: Exception) {
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (closed) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onStateChanged(State.DISCOVERING, null)
                        refreshGattCache(g)
                        // Give a bonded link time to start encryption before discovery
                        handler.postDelayed({ if (!closed) g.discoverServices() }, 600)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        closed = true
                        handler.removeCallbacksAndMessages(null)
                        operations.clear()
                        current = null
                        g.close()
                        gatt = null
                        val message = if (status == BluetoothGatt.GATT_SUCCESS) null
                        else "Connection failed or lost (status $status)"
                        listener.onStateChanged(State.DISCONNECTED, message)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStateChanged(State.READY, "Service discovery failed (status $status)")
                    return@post
                }
                listener.onLog("Services: " + g.services.joinToString { shortName(it.uuid) })

                // Shorter connection interval for faster file transfer
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                enqueue("request MTU") { it.requestMtu(FlySightProtocol.REQUESTED_MTU) }

                val message = if (g.getService(FlySightProtocol.DEVICE_STATE_SERVICE) == null)
                    "This FlySight firmware has no Device State service. Install a newer firmware."
                else null
                listener.onStateChanged(State.READY, message)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (closed) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) this@FlySightConnection.mtu = mtu
                listener.onLog("MTU: $mtu (status $status)")
                operationDone()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post {
                if (closed) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) listener.onValue(characteristic.uuid, value)
                else listener.onLog("Read ${shortName(characteristic.uuid)} failed (status $status)")
                operationDone()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post {
                if (!closed) listener.onValue(characteristic.uuid, value)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Write ${shortName(characteristic.uuid)} failed (status $status)")
                }
                operationDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Subscribe ${shortName(descriptor.characteristic.uuid)} failed (status $status)")
                }
                operationDone()
            }
        }
    }

    companion object {
        private const val OPERATION_TIMEOUT_MS = 5_000L

        private val NAMES = mapOf(
            FlySightProtocol.BATTERY_SERVICE to "Battery",
            FlySightProtocol.BATTERY_LEVEL to "Battery_Level",
            FlySightProtocol.DEVICE_INFORMATION_SERVICE to "Device_Information",
            FlySightProtocol.MANUFACTURER_NAME to "Manufacturer_Name",
            FlySightProtocol.MODEL_NUMBER to "Model_Number",
            FlySightProtocol.FIRMWARE_REVISION to "Firmware_Revision",
            FlySightProtocol.FILE_TRANSFER_SERVICE to "File_Transfer",
            FlySightProtocol.FT_PACKET_OUT to "FT_Packet_Out",
            FlySightProtocol.FT_PACKET_IN to "FT_Packet_In",
            FlySightProtocol.SENSOR_DATA_SERVICE to "Sensor_Data",
            FlySightProtocol.SD_GNSS_MEASUREMENT to "SD_GNSS_Measurement",
            FlySightProtocol.SD_SENSOR_MEASUREMENT to "SD_Sensor_Measurement",
            FlySightProtocol.SD_CONTROL_POINT to "SD_Control_Point",
            FlySightProtocol.STARTER_PISTOL_SERVICE to "Starter_Pistol",
            FlySightProtocol.SP_CONTROL_POINT to "SP_Control_Point",
            FlySightProtocol.SP_RESULT to "SP_Result",
            FlySightProtocol.DEVICE_STATE_SERVICE to "Device_State",
            FlySightProtocol.DS_MODE to "DS_Mode",
            FlySightProtocol.DS_CONTROL_POINT to "DS_Control_Point",
        )

        fun shortName(uuid: UUID): String = NAMES[uuid] ?: uuid.toString().let {
            if (it.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + it.substring(4, 8) else it
        }
    }
}
