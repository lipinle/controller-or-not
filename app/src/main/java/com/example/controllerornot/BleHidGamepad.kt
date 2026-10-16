package com.example.controllerornot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.Process
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Rolling send statistics: [avgMs] = mean time from input to radio hand-off, [perSecond] = reports sent last second. */
class LinkStats(val avgMs: Float, val maxMs: Float, val perSecond: Int)

/** Connection stage, used by the UI to colour the status indicator. */
enum class Link { STOPPED, ADVERTISING, CONNECTED, READY, ERROR }

/**
 * Presents the phone as a Bluetooth LE HID-over-GATT (HOGP) gamepad.
 *
 * Unlike the classic BluetoothHidDevice API, a GATT server lets us publish our own Device Information
 * service, including the PnP ID (vendor / product ID) that hosts use to decide what kind of controller this is.
 */
@SuppressLint("MissingPermission") // the caller (MainActivity) holds BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT
class BleHidGamepad(context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(link: Link, text: String)

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
    private var keyboardReport: BluetoothGattCharacteristic? = null
    private var keyboardEnabled = false
    private var originalName: String? = null
    private var running = false

    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val connected = LinkedHashSet<BluetoothDevice>()                // main thread only
    private val subscribed = ConcurrentHashMap<String, BluetoothDevice>()   // devices that enabled gamepad notifications
    private val kbSubscribed = ConcurrentHashMap<String, BluetoothDevice>() // ... and keyboard notifications
    private val fastLinks = HashMap<String, BluetoothGatt>()                // main thread only

    private var protocolMode: Byte = 1 // report protocol
    @Volatile private var latest: ByteArray = ByteArray(HidReport.INPUT_REPORT_SIZE)

    /**
     * Latency extras: ask for the 2M PHY and (experimental) open a client link to the host only to request a
     * short connection interval, since BluetoothGattServer has no such call of its own.
     */
    @Volatile
    var lowLatency = true
        set(v) {
            field = v
            main.post {
                if (v) subscribed.values.forEach { optimizeLink(it) } else closeAllFastLinks()
            }
        }

    // Outgoing report queue, drained by a dedicated high-priority thread. Everything below is guarded by [queue].
    private class Pending(var data: ByteArray, val queuedAtNs: Long, val kind: Int = KIND_GAMEPAD)
    private val queue = ArrayDeque<Pending>()
    private var notifyBusy = false
    private var notifyStartedAt = 0L
    private var inFlightQueuedNs = 0L
    private var inFlightKind = KIND_GAMEPAD
    private var failures = 0
    private var latEmaMs = 0f
    private var winStartNs = 0L
    private var winCount = 0
    private var winMaxMs = 0f
    private var lastRate = 0
    private var lastMax = 0f

    private var senderThread: HandlerThread? = null
    @Volatile private var sender: Handler? = null
    private val pumpPosted = AtomicBoolean(false)
    private val pumpTask = Runnable { pumpPosted.set(false); pump() }

    val isRunning: Boolean get() = running

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    fun start(profile: HidProfile, keyboard: Boolean = false) {
        if (running) stop()
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus(Link.ERROR, "Bluetooth ปิดอยู่")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            listener.onStatus(Link.ERROR, "มือถือเครื่องนี้ไม่รองรับการทำตัวเป็นอุปกรณ์ BLE (advertise ไม่ได้)")
            return
        }
        this.profile = profile
        this.keyboardEnabled = keyboard
        advertiser = adv
        running = true
        synchronized(queue) {
            queue.clear()
            notifyBusy = false
            failures = 0
            latEmaMs = 0f; winStartNs = 0L; winCount = 0; winMaxMs = 0f; lastRate = 0; lastMax = 0f
        }
        val thread = HandlerThread("hid-send", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        thread.start()
        senderThread = thread
        sender = Handler(thread.looper)
        pumpPosted.set(false)

        if (originalName == null) originalName = adapter.name
        adapter.name = profile.deviceName

        server = appContext.getSystemService(BluetoothManager::class.java).openGattServer(appContext, gattCallback)
        if (server == null) {
            running = false
            listener.onStatus(Link.ERROR, "เปิด GATT server ไม่ได้")
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
        keyboardReport = null
        closeAllFastLinks()
        connected.clear()
        subscribed.clear()
        kbSubscribed.clear()
        pendingServices.clear()
        sender?.removeCallbacksAndMessages(null)
        sender = null
        senderThread?.quitSafely()
        senderThread = null
        synchronized(queue) { queue.clear(); notifyBusy = false }
        originalName?.let { runCatching { adapter?.name = it } }
        originalName = null
        listener.onStatus(Link.STOPPED, "หยุดอยู่")
    }

    // ---------------------------------------------------------------------------------------------
    // Sending input
    // ---------------------------------------------------------------------------------------------

    /** Latency statistics of the last second, for the settings screen. */
    fun stats(): LinkStats = synchronized(queue) { LinkStats(latEmaMs, lastMax, lastRate) }

    fun send(state: GamepadState) {
        val report = HidReport.build(state)
        latest = report
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(queue) {
            val tail = queue.peekLast()
            if (tail != null && tail.kind == KIND_GAMEPAD && sameDigital(tail.data, report)) {
                // only analog values moved: newest wins, no point sending the stale one
                tail.data = report
            } else {
                // a button / trigger / hat edge must reach the host even if it is released again right away
                if (queue.count { it.kind == KIND_GAMEPAD } >= MAX_QUEUE) {
                    val iter = queue.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().kind == KIND_GAMEPAD) { iter.remove(); break }
                    }
                }
                queue.addLast(Pending(report, now, KIND_GAMEPAD))
            }
        }
        schedulePump()
    }

    /** True once the host has turned on keyboard notifications (i.e. it sees the keyboard). */
    val keyboardReady: Boolean get() = kbSubscribed.isNotEmpty()

    /** Queues keyboard reports (in order, none dropped). Returns false if the host is not listening for them. */
    fun typeReports(reports: List<ByteArray>): Boolean {
        if (!running || kbSubscribed.isEmpty()) return false
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(queue) { for (r in reports) queue.addLast(Pending(r, now, KIND_KEYBOARD)) }
        schedulePump()
        return true
    }

    private fun sameDigital(a: ByteArray, b: ByteArray): Boolean {
        for (i in DIGITAL_START until HidReport.INPUT_REPORT_SIZE) if (a[i] != b[i]) return false
        return true
    }

    private fun schedulePump() {
        val h = sender ?: return
        if (pumpPosted.compareAndSet(false, true)) h.post(pumpTask)
    }

    /** Runs on the sender thread only: sends the next queued report once the previous one has left the phone. */
    private fun pump() {
        val srv = server ?: return
        val item: Pending
        synchronized(queue) {
            if (queue.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            // recover if the stack never confirmed the previous notification
            if (notifyBusy && now - notifyStartedAt > BUSY_TIMEOUT_MS) notifyBusy = false
            if (notifyBusy) return
            item = queue.pollFirst()!!
            notifyBusy = true
            notifyStartedAt = now
            inFlightQueuedNs = item.queuedAtNs
            inFlightKind = item.kind
        }
        val typing = item.kind == KIND_KEYBOARD
        val chr = if (typing) keyboardReport else inputReport
        if (chr == null) {
            synchronized(queue) { notifyBusy = false }
            return
        }
        val targets = if (typing) kbSubscribed.values else subscribed.values
        if (targets.isEmpty()) {
            synchronized(queue) { notifyBusy = false; queue.clear() }
            return
        }
        var ok = true
        for (d in targets) if (!notify(srv, d, chr, item.data)) ok = false
        if (ok) {
            failures = 0
        } else {
            synchronized(queue) {
                notifyBusy = false
                if (++failures < MAX_FAILURES) queue.addFirst(item)
            }
            sender?.postDelayed({ pump() }, 2)
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(srv: BluetoothGattServer, device: BluetoothDevice, chr: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, chr, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                chr.value = value
                srv.notifyCharacteristicChanged(device, chr, false)
            }
        } catch (e: Exception) {
            false
        }

    // ---------------------------------------------------------------------------------------------
    // Link optimisation
    // ---------------------------------------------------------------------------------------------

    private fun optimizeLink(device: BluetoothDevice) {
        val srv = server ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                srv.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            } catch (e: Exception) {
                // not supported by this controller
            }
        }
        // iOS ignores the peripheral's preferred parameters, so the short interval has to be requested explicitly.
        main.postDelayed({ openFastLink(device) }, 1000)
    }

    private fun openFastLink(device: BluetoothDevice) {
        if (!running || !lowLatency || !subscribed.containsKey(device.address) || fastLinks.containsKey(device.address)) return
        try {
            val g = device.connectGatt(appContext, false, fastLinkCallback, BluetoothDevice.TRANSPORT_LE)
            if (g != null) fastLinks[device.address] = g
        } catch (e: Exception) {
            // best effort only
        }
    }

    private val fastLinkCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) return
            try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (e: Exception) { }
            // ask again later: some stacks ignore a request made right after connecting
            main.postDelayed({
                try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (e: Exception) { }
            }, 4000)
        }
    }

    private fun closeFastLink(address: String) {
        fastLinks.remove(address)?.let {
            try { it.disconnect(); it.close() } catch (e: Exception) { }
        }
    }

    private fun closeAllFastLinks() {
        fastLinks.keys.toList().forEach { closeFastLink(it) }
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
                it.value = HidReport.reportMap(keyboardEnabled)
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

        if (keyboardEnabled) {
            val keys = BluetoothGattCharacteristic(UUID_REPORT, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED)
            keys.value = ByteArray(HidReport.KEYBOARD_REPORT_SIZE)
            keys.addDescriptor(
                BluetoothGattDescriptor(UUID_CCC, PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED).also {
                    it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                },
            )
            keys.addDescriptor(reportReference(HidReport.KEYBOARD_REPORT_ID, 1))
            hid.addCharacteristic(keys)
            keyboardReport = keys
        }

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
            main.post { listener.onStatus(Link.ADVERTISING, "กำลังประกาศชื่อ \"${profile.deviceName}\" – ไปจับคู่ในตั้งค่า Bluetooth ของ iPad") }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post { listener.onStatus(Link.ERROR, "ประกาศไม่สำเร็จ (error $errorCode)") }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GATT server callbacks
    // ---------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus(Link.ERROR, "เพิ่ม GATT service ไม่สำเร็จ ($status)")
                } else if (running) {
                    addNextService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                subscribed.remove(device.address)
                kbSubscribed.remove(device.address)
            }
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(device)
                    listener.onStatus(Link.CONNECTED, "เชื่อมต่อกับ ${device.name ?: device.address} แล้ว")
                } else {
                    connected.remove(device)
                    closeFastLink(device.address)
                    if (running) {
                        listener.onStatus(Link.ADVERTISING, "หลุดการเชื่อมต่อ – กำลังประกาศใหม่")
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
                UUID_REPORT -> when {
                    characteristic === inputReport -> latest
                    characteristic === keyboardReport -> ByteArray(HidReport.KEYBOARD_REPORT_SIZE)
                    else -> ByteArray(8)
                }
                else -> characteristic.value ?: ByteArray(0)
            }
            respondRead(device, requestId, offset, full)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val full = if (descriptor.uuid == UUID_CCC) {
                val on = if (descriptor.characteristic === keyboardReport) kbSubscribed.containsKey(device.address) else subscribed.containsKey(device.address)
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
            if (descriptor.uuid == UUID_CCC && descriptor.characteristic === keyboardReport && keyboardReport != null) {
                val enabled = value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                if (enabled) kbSubscribed[device.address] = device else kbSubscribed.remove(device.address)
            } else if (descriptor.uuid == UUID_CCC && descriptor.characteristic === inputReport) {
                val enabled = value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                if (enabled) subscribed[device.address] = device else subscribed.remove(device.address)
                if (enabled) {
                    // give the host the current state straight away
                    synchronized(queue) {
                        queue.clear()
                        queue.addLast(Pending(latest, SystemClock.elapsedRealtimeNanos()))
                        notifyBusy = false
                    }
                    schedulePump()
                }
                main.post {
                    if (enabled) {
                        listener.onStatus(Link.READY, "พร้อมใช้งาน – เครื่องปลายทางรับสัญญาณแล้ว")
                        if (lowLatency) optimizeLink(device)
                    } else {
                        closeFastLink(device.address)
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
                characteristic.uuid == UUID_REPORT && characteristic !== inputReport && characteristic !== keyboardReport -> handleOutputReport(value)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            synchronized(queue) {
                notifyBusy = false
                if (inFlightKind == KIND_GAMEPAD) {
                    val latMs = (now - inFlightQueuedNs) / 1_000_000f
                    latEmaMs = if (latEmaMs == 0f) latMs else latEmaMs + (latMs - latEmaMs) * 0.1f
                    if (latMs > winMaxMs) winMaxMs = latMs
                    winCount++
                    if (now - winStartNs >= 1_000_000_000L) {
                        lastRate = winCount; lastMax = winMaxMs
                        winCount = 0; winMaxMs = 0f; winStartNs = now
                    }
                }
            }
            schedulePump()
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
        private const val KIND_GAMEPAD = 0
        private const val KIND_KEYBOARD = 1
        private const val MAX_QUEUE = 8
        private const val DIGITAL_START = 8 // bytes 8.. of the input report: triggers, hat, buttons, share
        private const val BUSY_TIMEOUT_MS = 100L
        private const val MAX_FAILURES = 50

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
