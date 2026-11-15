package com.autocockpit.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.autocockpit.core.handoff.HandoffCharacteristic
import com.autocockpit.core.handoff.HandoffProtocol
import com.autocockpit.core.handoff.HandoffRepository
import com.autocockpit.core.handoff.HandoffServerCore
import com.autocockpit.core.handoff.StatusMessage
import java.util.UUID

/**
 * Head-unit GATT peripheral for the companion handoff.
 *
 * GATT plumbing only: bytes are handed to [HandoffServerCore], which owns reassembly, CRC
 * validation, retransmit bitmaps and idempotent commits. That means the protocol is identical to
 * the headless tests; this class is the part that cannot run without a Bluetooth stack.
 */
class HandoffGattServer(
    context: Context,
    repository: HandoffRepository,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val serverCore = HandoffServerCore(repository)

    private var gattServer: BluetoothGattServer? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notificationsEnabled = false

    var status: String = "stopped"
        private set
    var lastError: String? = null
        private set

    fun isBluetoothAvailable(): Boolean = adapter?.isEnabled == true

    /**
     * Opens the GATT server, registers the handoff service and starts connectable advertising.
     * Returns a human-readable status string; never throws.
     */
    @SuppressLint("MissingPermission")
    fun start(): String {
        if (adapter == null) {
            lastError = "no Bluetooth adapter"
            status = "unavailable: no adapter"
            return status
        }
        if (!adapter.isEnabled) {
            lastError = "Bluetooth is off"
            status = "unavailable: Bluetooth off"
            return status
        }
        if (gattServer != null) return status
        return runCatching {
            val server = manager!!.openGattServer(appContext, callback)
                ?: error("openGattServer returned null")

            val service = BluetoothGattService(
                HandoffProtocol.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            val write = BluetoothGattCharacteristic(
                HandoffProtocol.WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val control = BluetoothGattCharacteristic(
                HandoffProtocol.CONTROL_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val statusChar = BluetoothGattCharacteristic(
                HandoffProtocol.STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            statusChar.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(write)
            service.addCharacteristic(control)
            service.addCharacteristic(statusChar)
            check(server.addService(service)) { "addService failed" }

            gattServer = server
            statusCharacteristic = statusChar
            val advertising = advertise()
            status = if (advertising) "advertising" else "server ready"
            status
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            status = "error: $lastError"
            Log.w(TAG, "GATT server start failed", error)
            status
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertise(): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HandoffProtocol.SERVICE_UUID))
            .build()
        return runCatching {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            true
        }.getOrElse { false }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            status = "advertising"
        }

        override fun onStartFailure(errorCode: Int) {
            lastError = "advertise failed: $errorCode"
            status = "server ready (advertising failed)"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        statusCharacteristic = null
        connectedDevice = null
        notificationsEnabled = false
        status = "stopped"
    }

    @SuppressLint("MissingPermission")
    private fun notifyStatus(device: BluetoothDevice, message: StatusMessage) {
        val characteristic = statusCharacteristic ?: return
        if (!notificationsEnabled) return
        val server = gattServer ?: return
        val value = HandoffProtocol.encodeStatus(message)
        runCatching {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device
                this@HandoffGattServer.status = "connected: ${device.name ?: device.address}"
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDevice = null
                notificationsEnabled = false
                this@HandoffGattServer.status = "advertising (phone disconnected)"
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
            val responses = when (characteristic.uuid) {
                HandoffProtocol.WRITE_UUID -> serverCore.onChunkRaw(value)
                HandoffProtocol.CONTROL_UUID -> serverCore.onControl(value)
                else -> emptyList()
            }
            responses.forEach { notifyStatus(device, it) }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                HandoffProtocol.CONTROL_UUID,
                HandoffProtocol.STATUS_UUID,
                -> HandoffProtocol.encodeStatus(serverCore.statusSnapshot(CONTROL_READ_MTU))
                else -> ByteArray(0)
            }
            runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                notificationsEnabled = true
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }
    }

    companion object {
        private const val TAG = "HandoffGattServer"
        private const val CONTROL_READ_MTU = HandoffProtocol.REQUESTED_MTU

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Characteristic table exposed by the peripheral, for docs and diagnostics. */
        fun characteristicTable(): List<Triple<String, String, String>> = listOf(
            Triple("HANDOFF_WRITE", HandoffProtocol.WRITE_UUID.toString(), "write-without-response: chunked frame"),
            Triple("HANDOFF_CONTROL", HandoffProtocol.CONTROL_UUID.toString(), "write BEGIN/COMMIT/ABORT/RESUME, read schema+MTU"),
            Triple("HANDOFF_STATUS", HandoffProtocol.STATUS_UUID.toString(), "notify progress/retransmit/commit/abort"),
        )
    }
}
