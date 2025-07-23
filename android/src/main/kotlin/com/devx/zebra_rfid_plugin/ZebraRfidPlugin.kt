package com.devx.zebra_rfid_plugin

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class ZebraRfidPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var reader: RFIDReader? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "zebra_rfid_plugin")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {}
    override fun onDetachedFromActivity() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}
    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> initReader(result)
            "startRfid" -> startRfid(result)
            "stopRfid" -> stopRfid(result)
            "setPower" -> setPower(call.arguments as Int, result)
            "switchTrigger" -> switchTriggerMode(call.arguments as String, result)
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            else -> result.notImplemented()
        }
    }

    private fun initReader(result: MethodChannel.Result) {
        try {
            val readers = Readers(context, ENUM_TRANSPORT.BLUETOOTH)
            val devices = readers.GetAvailableRFIDReaderList()

            if (!devices.isNullOrEmpty()) {
                val device = devices[0]
                reader = device.rfidReader
                reader?.connect()

                // Default trigger mode is RFID. You can switch to Barcode mode via "switchTrigger"
                reader?.Config?.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)

                reader?.Events?.apply {
                    addEventsListener(object : RfidEventsListener {
                        override fun eventReadNotify(e: RfidReadEvents) {
                            try {
                                val tags = reader?.Actions?.getReadTags(100)
                                tags?.forEach { tag ->
                                    val data = tag.tagID
                                    if (!data.isNullOrEmpty()) {
                                        // Barcode and RFID data both pass through this callback in BARCODE_MODE or RFID_MODE
                                        channel.invokeMethod("onScan", data)
                                    }
                                }
                            } catch (ex: Exception) {
                                Log.e("ZebraPlugin", "Read error", ex)
                            }
                        }

                        override fun eventStatusNotify(e: RfidStatusEvents) {
                            // Optional: handle trigger, battery, or other status events if needed
                        }
                    })
                    setTagReadEvent(true)
                    setHandheldEvent(true)
                }

                result.success(true)
            } else {
                result.error("NO_READER", "No RFID reader found", null)
            }

        } catch (e: Exception) {
            Log.e("ZebraPlugin", "initReader error", e)
            result.error("INIT_ERROR", e.localizedMessage, null)
        }
    }

    private fun startRfid(result: MethodChannel.Result) {
        try {
            reader?.Actions?.Inventory?.perform()
            result.success(true)
        } catch (e: Exception) {
            result.error("START_RFID_FAILED", e.localizedMessage, null)
        }
    }

    private fun stopRfid(result: MethodChannel.Result) {
        try {
            reader?.Actions?.Inventory?.stop()
            result.success(true)
        } catch (e: Exception) {
            result.error("STOP_RFID_FAILED", e.localizedMessage, null)
        }
    }

    private fun setPower(powerLevel: Int, result: MethodChannel.Result) {
        try {
            val antennas = reader?.Config?.Antennas
            val config = antennas?.getAntennaConfig(1)

            if (config != null) {
                config.transmitPowerIndex = powerLevel.toShort()
                antennas.setAntennaConfig(1, config)
                result.success(true)
            } else {
                result.error("ANTENNA_NULL", "Antenna config is null", null)
            }
        } catch (e: Exception) {
            result.error("SET_POWER_FAILED", e.localizedMessage, null)
        }
    }

    private fun switchTriggerMode(mode: String, result: MethodChannel.Result) {
        try {
            when (mode.lowercase()) {
                "rfid" -> {
                    reader?.Config?.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                    result.success("Trigger mode set to RFID")
                }
                "barcode" -> {
                    reader?.Config?.setTriggerMode(ENUM_TRIGGER_MODE.BARCODE_MODE, true)
                    result.success("Trigger mode set to Barcode")
                }
                else -> result.error("INVALID_MODE", "Mode must be 'rfid' or 'barcode'", null)
            }
        } catch (e: Exception) {
            result.error("SWITCH_TRIGGER_FAILED", e.localizedMessage, null)
        }
    }
}
