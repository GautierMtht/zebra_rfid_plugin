
import 'dart:async';
import 'package:flutter/services.dart';

/// Supported operation modes for the Zebra RFID plugin.
/// These correspond to native modes on the device.
enum ZebraPluginMode {
  /// All features disabled.
  off,

  /// RFID mode enabled only.
  rfid,

  /// Barcode mode enabled only.
  barcode,

  /// Both barcode and RFID modes enabled.
  barcodeRfid
}

String _modeToNative(ZebraPluginMode m) {
  switch (m) {
    case ZebraPluginMode.off:
      return 'OFF';
    case ZebraPluginMode.rfid:
      return 'RFID';
    case ZebraPluginMode.barcode:
      return 'BARCODE';
    case ZebraPluginMode.barcodeRfid:
      return 'BARCODE_RFID';
  }
}

ZebraPluginMode _modeFromNative(String? s) {
  switch ((s ?? 'OFF').toUpperCase()) {
    case 'RFID':
      return ZebraPluginMode.rfid;
    case 'BARCODE':
      return ZebraPluginMode.barcode;
    case 'BARCODE_RFID':
      return ZebraPluginMode.barcodeRfid;
    default:
      return ZebraPluginMode.off;
  }
}


/// Main API class for interacting with Zebra RFID and barcode devices.
/// Provides methods for device discovery, connection, mode switching, and event streams.
class ZebraRfidPlugin {
  ZebraRfidPlugin._();
  static final ZebraRfidPlugin instance = ZebraRfidPlugin._();

  /// Method channel for native communication.
  static const MethodChannel _ch = MethodChannel('zebra_rfid_plugin');

  /// Stream of RFID tag EPCs as they are scanned.
  final StreamController<String> _rfidCtrl = StreamController.broadcast();
  /// Stream of barcode data as scanned by the device.
  final StreamController<String> _barcodeCtrl = StreamController.broadcast();
  /// Stream that emits when the device disconnects.
  final StreamController<void> _disconnectedCtrl = StreamController.broadcast();

  /// Listen to RFID tag events.
  Stream<String> get rfidStream => _rfidCtrl.stream;
  /// Listen to barcode scan events.
  Stream<String> get barcodeStream => _barcodeCtrl.stream;
  /// Listen to device disconnect events.
  Stream<void> get disconnected => _disconnectedCtrl.stream;

  bool _methodsBound = false;

  /// Ensures that native callbacks are bound to Dart event streams.
  void _ensureBindCallbacks() {
    if (_methodsBound) return;
    _methodsBound = true;
    _ch.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onScan': // RFID tag event
          final epc = call.arguments as String?;
          if (epc != null && epc.isNotEmpty) _rfidCtrl.add(epc);
          break;
        case 'onBarcodeScan': // Barcode event
          final data = call.arguments as String?;
          if (data != null && data.isNotEmpty) _barcodeCtrl.add(data);
          break;
        case 'onDisconnected': // Device disconnected
          _disconnectedCtrl.add(null);
          break;
      }
    });
  }


  /// Returns the platform version as reported by the native plugin.
  static Future<String?> getPlatformVersion() async =>
    await _ch.invokeMethod<String>('getPlatformVersion');


  /// Initializes the plugin and binds event streams. Returns true if successful.
  Future<bool> init() async {
    _ensureBindCallbacks();
    final ok = await _ch.invokeMethod<bool>('init');
    return ok ?? false;
  }


  /// Lists available Zebra readers (RFID/barcode devices).
  /// Returns a list of maps with keys: name, address, connected.
  Future<List<Map<String, dynamic>>> listReaders() async {
    final res = await _ch.invokeMethod<List<dynamic>>('listReaders');
    return (res ?? const [])
        .cast<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList();
  }


  /// Connects to a Zebra reader by Bluetooth address or name.
  /// Returns true if the connection is successful.
  Future<bool> connectReader({String? address, String? name}) async {
    final ok = await _ch.invokeMethod<bool>('connectReader', {
      if (address != null) 'address': address,
      if (name != null) 'name': name,
    });
    return ok ?? false;
  }


  /// Returns the name of the currently connected reader, or null if not connected.
  Future<String?> getReaderName() => _ch.invokeMethod<String>('getReaderName');


  /// Gets the current operation mode of the device (RFID, barcode, etc).
  Future<ZebraPluginMode> getMode() async {
    final s = await _ch.invokeMethod<String>('getMode');
    return _modeFromNative(s);
  }


  /// Sets the operation mode of the device (RFID, barcode, or both).
  /// Returns the mode as confirmed by the device.
  Future<ZebraPluginMode> setMode(ZebraPluginMode mode) async {
    final s = await _ch.invokeMethod<String>('setMode', _modeToNative(mode));
    return _modeFromNative(s);
  }


  /// Starts RFID/barcode scanning depending on the current mode.
  /// Returns true if the operation was successful.
  Future<bool> start() async {
    final ok = await _ch.invokeMethod<bool>('start');
    return ok ?? false;
  }


  /// Stops RFID/barcode scanning.
  /// Returns true if the operation was successful.
  Future<bool> stop() async {
    final ok = await _ch.invokeMethod<bool>('stop');
    return ok ?? false;
  }


  /// Sets the RFID power level (index).
  /// Returns true if the operation was successful.
  Future<bool> setPower(int powerIndex) async {
    final ok = await _ch.invokeMethod<bool>('setPower', powerIndex);
    return ok ?? false;
  }


  /// Disconnects from the current reader device.
  /// Returns true if the operation was successful.
  Future<bool> disconnect() async {
    final ok = await _ch.invokeMethod<bool>('disconnect');
    return ok ?? false;
  }

  /// Disposes the plugin and closes all event streams.
  /// Should be called when the plugin is no longer needed.
  Future<void> dispose() async {
    await _ch.invokeMethod('dispose');
    await _rfidCtrl.close();
    await _barcodeCtrl.close();
    await _disconnectedCtrl.close();
  }
}
