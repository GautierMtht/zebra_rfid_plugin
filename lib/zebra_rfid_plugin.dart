import 'package:flutter/services.dart';

class ZebraRfidPlugin {
  static const MethodChannel _channel = MethodChannel('zebra_rfid_plugin');

  static Future<bool> init() async {
    final result = await _channel.invokeMethod('init');
    return result == true;
  }

  static Future<void> startRfid() async {
    await _channel.invokeMethod('startRfid');
  }

  static Future<void> stopRfid() async {
    await _channel.invokeMethod('stopRfid');
  }

  static Future<void> setPower(int level) async {
    await _channel.invokeMethod('setPower', level);
  }

  static Future<void> switchTriggerMode(String mode) async {
    await _channel.invokeMethod('switchTrigger', mode); // 'rfid' or 'barcode'
  }

  static void setListener(Function(String data) onScan) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onScan' && call.arguments is String) {
        onScan(call.arguments);
      }
    });
  }

  Future<String?> getPlatformVersion() async {
    return await _channel.invokeMethod('getPlatformVersion');
  }
}
