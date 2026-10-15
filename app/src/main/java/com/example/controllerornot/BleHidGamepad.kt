package com.example.controllerornot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID

/**
 * Presents the phone as a Bluetooth LE HID-over-GATT (HOGP) gamepad.
 *
 * Unlike the classic BluetoothHidDevice API, a GATT server lets us publish our own Device Information
 * service, including the PnP ID (vendor / product ID) that hosts use to decide what kind of controller this is.
 */
@SuppressLint("MissingPermission") // the caller (MainActivity) holds BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT
class BleHidGamepad(context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)

        /** Rumble request from the host; magnitudes are 0..100, duration in milliseconds. */
        fun onRumble(strong: Int, weak: Int, durationMs: Int)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter

    private var profile = HidProfile.XBOX_SERIES
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var inputReport: BluetoothGattCharacteristic? = null
    private var originalName: String? = null
    private var running = false

    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val connected = LinkedHashSet<BluetoothDevice>()
    private val subscribed = HashSet<String>()

    private var protocolMode: Byte = 1 // report protocol
    private var latest: ByteArray = ByteArray(HidReport.INPUT_REPORT_SIZE)
    private var dirty = false
    private var notifyBusy = false
    private var notifyStartedAt = 0L

    val isRunning: Boolean get() = running

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    fun start(profile: HidProfile) {
        if (running) stop()
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus("Bluetooth is off")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            listener.onStatus("This phone cannot act as a BLE peripheral (advertising unsupported)")
            return
        }
        this.profile = profile
        advertiser = adv
        running = true
        notifyBusy = false
        dirty = false

        if (originalName == null) originalName = adapter.name
        adapter.name = profile.deviceName

        server = appContext.getSystemService(BluetoothManager::class.java).openGattServer(appContext, gattCallback)
        if (server == null) {
            running = false
            listener.onStatus("Could not open GATT server")
            return
        }
        buildServices()
        addNextService()
    }

    fun stop() {
        if (!running && server == null) return
        running = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        connected.forEach { runCatching { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        server = null
        advertiser = null
        inputReport = null
        connected.clear()
        subscribed.clear()
        pendingServices.clear()
        originalName?.let { runCatching { adapter?.name = it } }
        originalName = null
        listener.onStatus("Stopped")
    }

    // ---------------------------------------------------------------------------------------------
    // Sending input
    // ---------------------------------------------------------------------------------------------

    fun send(state: GamepadState) {
        synchronized(this) {
            latest = HidReport.build(state)
            dirty = true
        }
        pump()
    }

    private fun pump() {
        val srv = server ?: return
        val chr = inputReport ?: return
        val report: ByteArray
        synchronized(this) {
            if (!dirty) return
            // Recover if the stack never reported completion of the previous notification.
            if (notifyBusy && SystemClock.elapsedRealtime() - notifyStartedAt > 100) notifyBusy = false
            if (notifyBusy) return
            report = latest
            dirty = false
            notifyBusy = true
            notifyStartedAt = SystemClock.elapsedRealtime()
        }
        val targets = connected.filter { it.address in subscribed }
        if (targets.isEmpty()) {
            synchronized(this) { notifyBusy = false }
            return
        }
        for (device in targets) notify(srv, device, chr, report)
    }

    @Suppress("DEPRECATION")
    private fun notify(srv: BluetoothGattServer, device: BluetoothDevice, chr: BluetoothGattCharacteristic, value: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, chr, false, value)
            } else {
                chr.value = value
                srv.notifyCharacteristicChanged(device, chr, false)
            }
        } catch (e: Exception) {
            synchronized(this) { notifyBusy = false }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GATT table
    // ---------------------------------------------------------------------------------------------

    private fun buildServices() {
        pendingServices.clear()

        // Device Information: this is where the PnP ID (VID/PID) lives.
        val dis = BluetoothGattService(UUID_DIS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        dis.addCharacteristic(readOnly(UUID_MANUFACTURER, profile.manufacturer.toByteArray()))
        dis.addCharacteristic(readOnly(UUID_MODEL, profile.model.toByteArray()))
        dis.addCharacteristic(readOnly(UUID_PNP_ID, profile.pnpId()))

        val battery = BluetoothGattService(UUID_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery.addCharacteristic(readOnly(UUID_BATTERY_LEVEL, byteArrayOf(100)))

        val hid = BluetoothGattService(UUID_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hid.addCharacteristic(
            BluetoothGattCharacteristic(UUID_HID_INFO, PROPERTY_READ, PERMISSION_READ_ENCRYPTED).also {
                it.value = byteArrayOf(0x11, 0x01, 0x00, 0x01) // HID 1.11, country 0, normally connectable
            },
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(UUID_REPORT_MAP, PROPERTY_READ, PERMISSION_READ_ENCRYPTED).also {
                it.value = HidReport.REPORT_MAP
            },
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(UUID_CONTROL_POINT, PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED),
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(UUID_PROTOCOL_MODE, PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE,
                PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED).also { it.value = byteArrayOf(1) },
        )

        val input = BluetoothGattCharacteristic(UUID_REPORT, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED)
        input.value = ByteArray(HidReport.INPUT_REPORT_SIZE)
        input.addDescriptor(
            BluetoothGattDescriptor(UUID_CCC, PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED).also {
                it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            },
        )
        input.addDescriptor(reportReference(HidReport.INPUT_REPORT_ID, 1))
        hid.addCharacteristic(input)
        inputReport = input

        val output = BluetoothGattCharacteristic(
            UUID_REPORT, PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE,
            PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED,
        )
        output.value = ByteArray(8)
        output.addDescriptor(reportReference(HidReport.OUTPUT_REPORT_ID, 2))
        hid.addCharacteristic(output)

        pendingServices.add(dis)
        pendingServices.add(battery)
        pendingServices.add(hid)
    }

    private fun readOnly(uuid: UUID, value: ByteArray) =
        BluetoothGattCharacteristic(uuid, PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            .also { it.value = value }

    private fun reportReference(id: Int, type: Int) =
        BluetoothGattDescriptor(UUID_REPORT_REFERENCE, PERMISSION_READ_ENCRYPTED).also {
            it.value = byteArrayOf(id.toByte(), type.toByte())
        }

    private fun addNextService() {
        val next = pendingServices.poll()
        if (next == null) {
            startAdvertising()
            return
        }
        server?.addService(next)
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID_HID))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post { listener.onStatus("Advertising as \"${profile.deviceName}\" - pair it from the iPad's Bluetooth settings") }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post { listener.onStatus("Advertising failed (error $errorCode)") }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GATT server callbacks
    // ---------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("Adding GATT service failed ($status)")
                } else if (running) {
                    addNextService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(device)
                    listener.onStatus("Connected to ${device.name ?: device.address}")
                } else {
                    connected.remove(device)
                    subscribed.remove(device.address)
                    if (running) {
                        listener.onStatus("Disconnected - advertising again")
                        startAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = Unit

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val full = when (characteristic.uuid) {
                UUID_PROTOCOL_MODE -> byteArrayOf(protocolMode)
                UUID_REPORT -> if (characteristic === inputReport) synchronized(this@BleHidGamepad) { latest } else ByteArray(8)
                else -> characteristic.value ?: ByteArray(0)
            }
            respondRead(device, requestId, offset, full)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val full = if (descriptor.uuid == UUID_CCC) {
                if (device.address in subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                descriptor.value ?: ByteArray(0)
            }
            respondRead(device, requestId, offset, full)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == UUID_CCC && descriptor.characteristic === inputReport) {
                val enabled = value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                main.post {
                    if (enabled) subscribed.add(device.address) else subscribed.remove(device.address)
                    if (enabled) {
                        listener.onStatus("Controller ready - host is listening")
                        synchronized(this@BleHidGamepad) { dirty = true; notifyBusy = false }
                        pump()
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            when {
                characteristic.uuid == UUID_PROTOCOL_MODE && value.isNotEmpty() -> protocolMode = value[0]
                characteristic.uuid == UUID_REPORT && characteristic !== inputReport -> handleOutputReport(value)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BleHidGamepad) { notifyBusy = false }
            pump()
        }
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, full: ByteArray) {
        if (offset > full.size) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
    }

    /** Output report 3 payload: [enable, magnitudeLT, magnitudeRT, strong, weak, duration(10ms), delay, loops]. */
    private fun handleOutputReport(value: ByteArray) {
        if (value.size < 6) return
        val strong = value[3].toInt() and 0xFF
        val weak = value[4].toInt() and 0xFF
        val durationMs = (value[5].toInt() and 0xFF) * 10
        main.post { listener.onRumble(strong, weak, durationMs) }
    }

    companion object {
        private fun uuid16(short: Int) = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

        val UUID_DIS = uuid16(0x180A)
        val UUID_BATTERY = uuid16(0x180F)
        val UUID_HID = uuid16(0x1812)

        val UUID_MANUFACTURER = uuid16(0x2A29)
        val UUID_MODEL = uuid16(0x2A24)
        val UUID_PNP_ID = uuid16(0x2A50)
        val UUID_BATTERY_LEVEL = uuid16(0x2A19)
        val UUID_HID_INFO = uuid16(0x2A4A)
        val UUID_REPORT_MAP = uuid16(0x2A4B)
        val UUID_CONTROL_POINT = uuid16(0x2A4C)
        val UUID_REPORT = uuid16(0x2A4D)
        val UUID_PROTOCOL_MODE = uuid16(0x2A4E)

        val UUID_CCC = uuid16(0x2902)
        val UUID_REPORT_REFERENCE = uuid16(0x2908)
    }
}
