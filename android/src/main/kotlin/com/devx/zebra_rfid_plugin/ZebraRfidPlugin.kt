
package com.devx.zebra_rfid_plugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null

    private var reader: RFIDReader? = null
    private var readerDevice: ReaderDevice? = null
    private var readers: Readers? = null

    private var dataWedgeReceiver: DataWedgeReceiver? = null
    private var pendingResult: MethodChannel.Result? = null

    private val TAG = "ZebraRfidPlugin"

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "zebra_rfid_plugin")
        channel.setMethodCallHandler(this)
        setupDataWedgeProfile()
        registerDataWedgeReceiver()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        dataWedgeReceiver?.let { context.unregisterReceiver(it) }
        dataWedgeReceiver = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener { requestCode, permissions, grantResults ->
            if (requestCode == 1001 && pendingResult != null) {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    initializeReader(pendingResult!!)
                } else {
                    pendingResult!!.error("PERMISSION_DENIED", "Bluetooth permissions denied", null)
                }
                pendingResult = null
                true
            } else false
        }
    }

    override fun onDetachedFromActivity() {
        disconnectReader()
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> initReader(result)
            "getReaderName" -> result.success(reader?.hostName ?: "Unknown Reader")
            "startRfid" -> {
                if (reader?.isConnected != true) {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                } else {
                    try {
                        reader!!.Actions.Inventory.perform()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("START_FAILED", e.localizedMessage, null)
                    }
                }
            }
            "stopRfid" -> {
                if (reader?.isConnected != true) {
                    result.error("NOT_CONNECTED", "RFID reader not connected", null)
                } else {
                    try {
                        reader!!.Actions.Inventory.stop()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("STOP_FAILED", e.localizedMessage, null)
                    }
                }
            }
            "setPower" -> {
                val level = call.arguments as? Int ?: -1
                try {
                    val config = reader!!.Config.Antennas.getAntennaConfig(1)
                    config.transmitPowerIndex = level.toShort()
                    reader!!.Config.Antennas.setAntennaConfig(1, config)
                    result.success(true)
                } catch (e: Exception) {
                    result.error("SET_POWER_FAILED", e.localizedMessage, null)
                }
            }
            "switchTrigger" -> {
                val mode = (call.arguments as? String)?.lowercase()
                try {
                    when (mode) {
                        "rfid" -> reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                        "barcode" -> reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.BARCODE_MODE, true)
                        else -> result.error("INVALID_MODE", "Mode must be 'rfid' or 'barcode'", null)
                    }
                    result.success(true)
                } catch (e: Exception) {
                    result.error("SWITCH_TRIGGER_FAILED", e.localizedMessage, null)
                }
            }
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            else -> result.notImplemented()
        }
    }

    private fun initReader(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            val denied = permissions.filter {
                ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                activity?.let {
                    pendingResult = result
                    ActivityCompat.requestPermissions(it, denied.toTypedArray(), 1001)
                } ?: result.error("NO_ACTIVITY", "Cannot request permissions", null)
                return
            }
        }
        initializeReader(result)
    }

    private fun initializeReader(result: MethodChannel.Result) {
        Thread {
            try {
                readers = Readers(context, ENUM_TRANSPORT.BLUETOOTH)
                val devices = readers!!.GetAvailableRFIDReaderList()
                if (devices.isNullOrEmpty()) {
                    runOnMainThread { result.error("NO_READER", "No RFID reader found", null) }
                    return@Thread
                }
                readerDevice = devices[0]
                reader = readerDevice!!.rfidReader
                if (!reader!!.isConnected) reader!!.connect()
                configureReader()
                runOnMainThread { result.success(true) }
            } catch (e: Exception) {
                runOnMainThread { result.error("INIT_FAILED", e.localizedMessage, null) }
            }
        }.start()
    }

    private fun configureReader() {
        val triggerInfo = TriggerInfo().apply {
            StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
        }
        reader!!.Config.setStartTrigger(triggerInfo.StartTrigger)
        reader!!.Config.setStopTrigger(triggerInfo.StopTrigger)
        reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
        reader!!.Events.setTagReadEvent(true)
        reader!!.Events.setReaderDisconnectEvent(true)
        reader!!.Events.addEventsListener(object : RfidEventsListener {
            override fun eventReadNotify(e: RfidReadEvents?) {
                val tags = reader!!.Actions.getReadTags(100)
                tags?.forEach {
                    runOnMainThread {
                        channel.invokeMethod("onScan", it.tagID)
                    }
                }
            }
            override fun eventStatusNotify(e: RfidStatusEvents?) {
                if (e?.StatusEventData?.statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                    runOnMainThread {
                        channel.invokeMethod("onDisconnected", null)
                    }
                }
            }
        })
    }

    private fun disconnectReader() {
        try {
            reader?.let {
                it.Events.removeEventsListener(null)
                if (it.isConnected) it.disconnect()
            }
            readers?.Dispose()
        } catch (_: Exception) {
        } finally {
            reader = null
            readerDevice = null
            readers = null
        }
    }

    private fun setupDataWedgeProfile() {
        val profile = Bundle().apply {
            putString("PROFILE_NAME", "RFIDPluginProfile")
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf(Bundle().apply {
                putString("PACKAGE_NAME", context.packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }))
            putParcelableArray("PLUGIN_CONFIG", arrayOf(
                Bundle().apply {
                    putString("PLUGIN_NAME", "BARCODE")
                    putString("RESET_CONFIG", "true")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("scanner_selection", "auto")
                        putString("decoder_code128", "true")
                        putString("decoder_qrcode", "true")
                    })
                },
                Bundle().apply {
                    putString("PLUGIN_NAME", "INTENT")
                    putString("RESET_CONFIG", "true")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("intent_output_enabled", "true")
                        putString("intent_action", "com.devx.zebra_rfid_plugin.SCANNER")
                        putString("intent_delivery", "BROADCAST")
                    })
                }
            ))
        }
        context.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
            putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
        })
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDataWedgeReceiver() {
        if (dataWedgeReceiver != null) return
        dataWedgeReceiver = DataWedgeReceiver()
        context.registerReceiver(dataWedgeReceiver, android.content.IntentFilter().apply {
            addAction("com.devx.zebra_rfid_plugin.SCANNER")
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    inner class DataWedgeReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val data = intent?.getStringExtra("com.symbol.datawedge.data_string")
            if (!data.isNullOrEmpty()) {
                runOnMainThread { channel.invokeMethod("onBarcodeScan", data) }
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post { action() }
    }
}
