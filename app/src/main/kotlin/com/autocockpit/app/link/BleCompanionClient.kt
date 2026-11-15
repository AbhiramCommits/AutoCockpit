package com.autocockpit.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import com.autocockpit.core.handoff.HandoffCharacteristic
import com.autocockpit.core.handoff.HandoffProtocol
import com.autocockpit.core.handoff.HandoffTransport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android BLE implementation of [HandoffTransport] for the phone side. The protocol is driven by
 * the pure-JVM `CompanionClient`; this class only performs connect, MTU negotiation, characteristic
 * writes and notification collection, all with bounded waits.
 */
@SuppressLint("MissingPermission")
class BleCompanionClient(
    private val context: Context,
    private val deviceAddress: String,
    private val connectTimeoutMillis: Long = 10_000L,
) : HandoffTransport {

    override val name: String = "ble:$deviceAddress"

    @Volatile
    override var connected: Boolean = false
        private set

    @Volatile
    override var mtu: Int = HandoffProtocol.DEFAULT_MTU - HandoffProtocol.ATT_HEADER_BYTES
        private set

    private val notifications = ConcurrentLinkedQueue<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var readyLatch: CountDownLatch? = null
    private var mtuLatch: CountDownLatch? = null
    @Volatile private var negotiatedMtu: Int = 0

    override fun connect(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val device: BluetoothDevice = runCatching { manager.adapter.getRemoteDevice(deviceAddress) }
            .getOrElse { return false }
        val latch = CountDownLatch(1)
        readyLatch = latch
        val gattClient = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return false
        gatt = gattClient
        val ready = latch.await(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        connected = ready
        return ready
    }

    override fun negotiateMtu(requestedMtu: Int): Int {
        val gattClient = gatt ?: return 0
        val latch = CountDownLatch(1)
        mtuLatch = latch
        if (!gattClient.requestMtu(requestedMtu)) return 0
        latch.await(MTU_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return negotiatedMtu
    }

    override fun writeCharacteristic(characteristic: HandoffCharacteristic, value: ByteArray): Boolean {
        val gattClient = gatt ?: return false
        val target = when (characteristic) {
            HandoffCharacteristic.WRITE -> writeCharacteristic
            HandoffCharacteristic.CONTROL -> controlCharacteristic
            HandoffCharacteristic.STATUS -> statusCharacteristic
        } ?: return false
        return runCatching {
            target.writeType = if (characteristic == HandoffCharacteristic.WRITE) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            @Suppress("DEPRECATION")
            target.value = value
            @Suppress("DEPRECATION")
            gattClient.writeCharacteristic(target)
        }.getOrDefault(false)
    }

    override fun notifications(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) out += notifications.poll() ?: break
        return out
    }

    override fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        connected = false
        notifications.clear()
    }

    override fun nowMillis(): Long = SystemClock.elapsedRealtime()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                readyLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyLatch?.countDown()
                return
            }
            val service = gatt.getService(HandoffProtocol.SERVICE_UUID) ?: run {
                readyLatch?.countDown()
                return
            }
            writeCharacteristic = service.getCharacteristic(HandoffProtocol.WRITE_UUID)
            controlCharacteristic = service.getCharacteristic(HandoffProtocol.CONTROL_UUID)
            statusCharacteristic = service.getCharacteristic(HandoffProtocol.STATUS_UUID)
            statusCharacteristic?.let { characteristic ->
                runCatching { gatt.setCharacteristicNotification(characteristic, true) }
                characteristic.getDescriptor(HandoffGattServer.CCCD_UUID)?.let { descriptor ->
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    runCatching { gatt.writeDescriptor(descriptor) }
                }
            }
            connected = writeCharacteristic != null && controlCharacteristic != null
            readyLatch?.countDown()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleCompanionClient.mtu = mtu - HandoffProtocol.ATT_HEADER_BYTES
                negotiatedMtu = mtu
            }
            mtuLatch?.countDown()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            notifications += value.copyOf()
        }
    }

    companion object {
        private const val MTU_TIMEOUT_SECONDS = 5L
    }
}
