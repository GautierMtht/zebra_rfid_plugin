package com.devx.zebra_rfid_plugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.zebra.rfid.api3.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class ZebraRfidPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    // ---- Flutter/Android glue ----
    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private var activity: Activity? = null
    @Volatile private var isActivityAttached: Boolean = false

    // ---- Zebra RFID SDK objects ----
    private var readers: Readers? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private var rfidEventsListener: RfidEventsListener? = null

    // ---- DataWedge ----
    private var dataWedgeReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    private val DW_PROFILE = "RFIDPluginProfile"
    private val DW_INTENT_ACTION = "com.devx.zebra_rfid_plugin.SCANNER"

    // ---- Pending results for async flows ----
    private var pendingInitResult: MethodChannel.Result? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    // ---- Constants / logging ----
    private val TAG = "ZebraRfidPlugin"
    private val PERMISSION_REQUEST_CODE = 1001

    //region FlutterPlugin
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "zebra_rfid_plugin")
        channel.setMethodCallHandler(this)

        // Prepare DataWedge profile and broadcast receiver immediately.
        // (Safe to call here; profile binds to package name and will be used once activity is alive)
        setupDataWedgeProfile(initialScannerEnabled = false) // default: RFID mode (barcode off)
        registerDataWedgeReceiverIfNeeded()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        unregisterDataWedgeReceiverIfNeeded()
        // Readers will be disposed in onDetachedFromActivity / ensure cleanup here too:
        safeDisconnectReader()
    }
    //endregion

    //region ActivityAware
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        isActivityAttached = true

        // Permission results callback
        binding.addRequestPermissionsResultListener { requestCode, _, grantResults ->
            if (requestCode == PERMISSION_REQUEST_CODE && pendingPermissionResult != null) {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Continue initialization after permission grant
                    val result = pendingPermissionResult
                    pendingPermissionResult = null
                    if (result != null) {
                        Handler(Looper.getMainLooper()).post { initializeReader(result) }
                    }
                } else {
                    pendingPermissionResult!!.error(
                        "PERMISSION_DENIED",
                        "Required Bluetooth/Location permissions denied by user.",
                        null
                    )
                    pendingPermissionResult = null
                }
                true
            } else {
                false
            }
        }

        // If init() was called too early, resume it now.
        pendingInitResult?.let { result ->
            pendingInitResult = null
            Handler(Looper.getMainLooper()).post { initReader(result) }
        }
    }

    override fun onDetachedFromActivity() {
        isActivityAttached = false
        activity = null
        safeDisconnectReader()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        // Same as onAttachedToActivity
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        isActivityAttached = false
        activity = null
        // Keep connection; device rotates—up to you. Here we keep it simple and disconnect:
        safeDisconnectReader()
    }
    //endregion

    //region MethodChannel
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> initReader(result)

            "getReaderName" -> {
                result.success(reader?.hostName ?: "Unknown Reader")
            }

            "startRfid" -> {
                val r = reader
                if (r?.isConnected == true) {
                    try {
                        r.Actions.Inventory.perform()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("START_FAILED", e.localizedMessage, null)
                    }
                } else {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                }
            }

            "stopRfid" -> {
                val r = reader
                if (r?.isConnected == true) {
                    try {
                        r.Actions.Inventory.stop()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("STOP_FAILED", e.localizedMessage, null)
                    }
                } else {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                }
            }

            "setPower" -> {
                val level = call.arguments as? Int ?: -1
                val r = reader
                if (r?.isConnected == true) {
                    try {
                        if (level < 0) throw IllegalArgumentException("Invalid power level: $level")
                        val cfg = r.Config.Antennas.getAntennaConfig(1)
                        cfg.transmitPowerIndex = level.toShort()
                        r.Config.Antennas.setAntennaConfig(1, cfg)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SET_POWER_FAILED", e.localizedMessage, null)
                    }
                } else {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                }
            }

            "switchTrigger" -> {
                val mode = (call.arguments as? String)?.lowercase()
                val r = reader
                if (r?.isConnected == true) {
                    try {
                        when (mode) {
                            "rfid" -> {
                                // Put physical trigger in RFID mode and disable DataWedge scanner input
                                r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                                setDataWedgeScannerInputEnabled(false)
                            }
                            "barcode" -> {
                                // Put physical trigger in Barcode mode and enable DataWedge scanner input
                                r.Config.setTriggerMode(ENUM_TRIGGER_MODE.BARCODE_MODE, true)
                                setDataWedgeScannerInputEnabled(true)
                            }
                            else -> {
                                result.error("INVALID_MODE", "Mode must be 'rfid' or 'barcode'", null)
                                return
                            }
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SWITCH_TRIGGER_FAILED", e.localizedMessage, null)
                    }
                } else {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                }
            }

            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

            "dispose" -> {
                safeDisconnectReader()
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }
    //endregion

    //region Init & permissions
    /**
     * Entry point from Flutter to initialize the reader.
     * If the Activity is not yet attached, we queue the init and resume when attached.
     */
    private fun initReader(result: MethodChannel.Result) {
        if (!isActivityAttached || activity == null) {
            Log.w(TAG, "init() called before onAttachedToActivity; deferring.")
            pendingInitResult = result
            return
        }

        // Runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION // BLE scanning needs location pre-Android 12; kept here for compatibility
            )
            val denied = permissions.filter {
                ActivityCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                pendingPermissionResult = result
                ActivityCompat.requestPermissions(activity!!, denied.toTypedArray(), PERMISSION_REQUEST_CODE)
                return
            }
        } else {
            // Android < 12: request fine location if needed for BLE discovery
            val needLocation = ActivityCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
            if (needLocation) {
                pendingPermissionResult = result
                ActivityCompat.requestPermissions(
                    activity!!, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE
                )
                return
            }
        }

        // Permissions ok: proceed
        initializeReader(result)
    }

    /**
     * Connect to the first available RFID reader via Bluetooth and configure it.
     * Runs on a background thread; posts results to the main thread.
     */
    private fun initializeReader(result: MethodChannel.Result) {
        Thread {
            try {
                // Initialize Readers with BLUETOOTH transport
                readers = Readers(appContext, ENUM_TRANSPORT.BLUETOOTH)
                val devices = readers?.GetAvailableRFIDReaderList()

                if (devices.isNullOrEmpty()) {
                    postMain { result.error("NO_READER", "No RFID reader found. Ensure RFD40 is paired and ON.", null) }
                    return@Thread
                }

                // Connect to the first device (you can add selection logic if needed)
                readerDevice = devices[0]
                reader = readerDevice?.rfidReader

                val r = reader
                if (r != null && !r.isConnected) {
                    Log.d(TAG, "Connecting to reader: ${readerDevice?.name}")
                    r.connect()
                }

                if (r == null || !r.isConnected) {
                    postMain { result.error("CONNECT_FAILED", "Failed to connect to RFID reader", null) }
                    return@Thread
                }

                configureReader(r)

                // Success
                postMain { result.success(true) }

            } catch (e: OperationFailureException) {
                Log.e(TAG, "RFID connect failed: ${e.statusDescription}", e)
                val msg = e.vendorMessage ?: e.localizedMessage
                postMain { result.error("CONNECT_FAILED", msg, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing reader", e)
                postMain { result.error("INIT_ERROR", e.localizedMessage, null) }
            }
        }.start()
    }
    //endregion

    //region RFID configuration & events
    @Throws(InvalidUsageException::class, OperationFailureException::class)
    private fun configureReader(r: RFIDReader) {
        // Start/Stop triggers are immediate; we drive via code or physical trigger
        val triggerInfo = TriggerInfo().apply {
            StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
        }
        r.Config.setStartTrigger(triggerInfo.StartTrigger)
        r.Config.setStopTrigger(triggerInfo.StopTrigger)

        // Default to RFID mode (barcode disabled in DataWedge)
        r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
        setDataWedgeScannerInputEnabled(false)

        // Event subscriptions
        r.Events.apply {
            setTagReadEvent(true)
            setAttachTagDataWithReadEvent(false)
            setHandheldEvent(true)
            setReaderDisconnectEvent(true)
        }

        // Keep a reference so we can remove it on disconnect
        val listener = object : RfidEventsListener {
            override fun eventReadNotify(e: RfidReadEvents?) {
                try {
                    val tags = r.Actions.getReadTags(100)
                    tags?.forEach { tag ->
                        val epc = tag.tagID
                        if (!epc.isNullOrEmpty()) {
                            postMain { channel.invokeMethod("onScan", epc) }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Error while reading tags", ex)
                }
            }

            override fun eventStatusNotify(e: RfidStatusEvents?) {
                val data = e?.StatusEventData ?: return
                when (data.statusEventType) {
                    STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                        // Optional: react to trigger press/release if needed
                        Log.d(TAG, "Trigger event: ${data.HandheldTriggerEventData.handheldEvent}")
                    }
                    STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                        Log.w(TAG, "RFID reader disconnected")
                        postMain { channel.invokeMethod("onDisconnected", null) }
                        // Clean local state
                        safeDisconnectReader()
                    }
                    else -> Unit
                }
            }
        }
        rfidEventsListener = listener
        r.Events.addEventsListener(listener)
    }
    //endregion

    //region DataWedge profile & receiver
    /**
     * Create/update the DataWedge profile that:
     *  - enables BARCODE plugin (scanner), optionally enabled/disabled
     *  - enables INTENT plugin to deliver broadcast with our custom action
     *  - binds the profile to our application package (all activities)
     */
    private fun setupDataWedgeProfile(initialScannerEnabled: Boolean) {
        try {
            val profile = Bundle().apply {
                putString("PROFILE_NAME", DW_PROFILE)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")

                // Bind profile to this app (all activities)
                val appConfig = Bundle().apply {
                    putString("PACKAGE_NAME", appContext.packageName)
                    putStringArray("ACTIVITY_LIST", arrayOf("*"))
                }
                putParcelableArray("APP_LIST", arrayOf(appConfig))

                // BARCODE plugin
                val barcodeConfig = Bundle().apply {
                    putString("PLUGIN_NAME", "BARCODE")
                    putString("RESET_CONFIG", "true")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("scanner_selection", "auto")
                        putString("scanner_input_enabled", if (initialScannerEnabled) "true" else "false")
                        // Enable common decoders; extend as needed
                        putString("decoder_code128", "true")
                        putString("decoder_qrcode", "true")
                        putString("decoder_datamatrix", "true")
                    })
                }

                // INTENT plugin
                val intentConfig = Bundle().apply {
                    putString("PLUGIN_NAME", "INTENT")
                    putString("RESET_CONFIG", "true")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("intent_output_enabled", "true")
                        putString("intent_action", DW_INTENT_ACTION)
                        putString("intent_delivery", "BROADCAST")
                    })
                }

                putParcelableArray("PLUGIN_CONFIG", arrayOf(barcodeConfig, intentConfig))
            }

            // Send SET_CONFIG
            appContext.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
                putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
            })

            // Ensure the profile is active for this app
            appContext.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
                putExtra("com.symbol.datawedge.api.SWITCH_TO_PROFILE", DW_PROFILE)
            })

            Log.d(TAG, "DataWedge profile configured: $DW_PROFILE (scanner enabled=$initialScannerEnabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up DataWedge profile", e)
        }
    }

    /**
     * Toggle the BARCODE plugin input enable/disable without changing other settings.
     */
    private fun setDataWedgeScannerInputEnabled(enabled: Boolean) {
        try {
            val barcodeConfig = Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "false")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("scanner_input_enabled", if (enabled) "true" else "false")
                })
            }

            val setConfig = Bundle().apply {
                putString("PROFILE_NAME", DW_PROFILE)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "UPDATE") // UPDATE existing profile
                putParcelableArray("PLUGIN_CONFIG", arrayOf(barcodeConfig))
            }

            appContext.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
                putExtra("com.symbol.datawedge.api.SET_CONFIG", setConfig)
            })

            Log.d(TAG, "DataWedge scanner_input_enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling DataWedge scanner input", e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDataWedgeReceiverIfNeeded() {
        if (isReceiverRegistered) return

        dataWedgeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != DW_INTENT_ACTION) return
                // Typical key for decoded data:
                val data = intent.getStringExtra("com.symbol.datawedge.data_string")
                if (!data.isNullOrEmpty()) {
                    postMain { channel.invokeMethod("onBarcodeScan", data) }
                } else {
                    // Optionally: handle multi-decode arrays from "com.symbol.datawedge.decode_data"
                    // (Not required for most use cases)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(DW_INTENT_ACTION)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(dataWedgeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(dataWedgeReceiver, filter)
            }
            isReceiverRegistered = true
            Log.d(TAG, "DataWedge receiver registered for action $DW_INTENT_ACTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register DataWedge receiver", e)
        }
    }

    private fun unregisterDataWedgeReceiverIfNeeded() {
        if (!isReceiverRegistered) return
        try {
            appContext.unregisterReceiver(dataWedgeReceiver)
        } catch (_: Exception) { /* ignore */ }
        dataWedgeReceiver = null
        isReceiverRegistered = false
    }
    //endregion

    //region Cleanup
    private fun safeDisconnectReader() {
        try {
            val r = reader
            if (r != null) {
                // Remove listener first
                rfidEventsListener?.let {
                    try { r.Events.removeEventsListener(it) } catch (_: Exception) {}
                }
                rfidEventsListener = null

                // Default back to RFID mode and stop inventory
                try {
                    r.Actions.Inventory.stop()
                } catch (_: Exception) { /* ignore */ }
                try {
                    r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                } catch (_: Exception) { /* ignore */ }

                // Disconnect
                if (r.isConnected) {
                    try { r.disconnect() } catch (_: Exception) {}
                }
            }
            readers?.Dispose()
        } catch (_: Exception) {
        } finally {
            reader = null
            readerDevice = null
            readers = null
        }
    }
    //endregion

    //region Utils
    private fun postMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }
    //endregion
}
