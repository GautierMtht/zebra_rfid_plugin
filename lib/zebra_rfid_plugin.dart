import 'dart:async';
import 'package:flutter/services.dart';

/// Modes supportés côté natif
enum ZebraPluginMode { off, rfid, barcode, barcodeRfid }

String _modeToNative(ZebraPluginMode m) {
  switch (m) {
    case ZebraPluginMode.off: return 'OFF';
    case ZebraPluginMode.rfid: return 'RFID';
    case ZebraPluginMode.barcode: return 'BARCODE';
    case ZebraPluginMode.barcodeRfid: return 'BARCODE_RFID';
  }
}

ZebraPluginMode _modeFromNative(String? s) {
  switch ((s ?? 'OFF').toUpperCase()) {
    case 'RFID': return ZebraPluginMode.rfid;
    case 'BARCODE': return ZebraPluginMode.barcode;
    case 'BARCODE_RFID': return ZebraPluginMode.barcodeRfid;
    default: return ZebraPluginMode.off;
  }
}

class ZebraRfidPlugin {
  ZebraRfidPlugin._();
  static final ZebraRfidPlugin instance = ZebraRfidPlugin._();

  static const MethodChannel _ch = MethodChannel('zebra_rfid_plugin');

  final StreamController<String> _rfidCtrl = StreamController.broadcast();
  final StreamController<String> _barcodeCtrl = StreamController.broadcast();
  final StreamController<void> _disconnectedCtrl = StreamController.broadcast();

  Stream<String> get rfidStream => _rfidCtrl.stream;
  Stream<String> get barcodeStream => _barcodeCtrl.stream;
  Stream<void> get disconnected => _disconnectedCtrl.stream;

  bool _methodsBound = false;

  void _ensureBindCallbacks() {
    if (_methodsBound) return;
    _methodsBound = true;
    _ch.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onScan': // RFID EPC
          final epc = call.arguments as String?;
          if (epc != null && epc.isNotEmpty) _rfidCtrl.add(epc);
          break;
        case 'onBarcodeScan':
          final data = call.arguments as String?;
          if (data != null && data.isNotEmpty) _barcodeCtrl.add(data);
          break;
        case 'onDisconnected':
          _disconnectedCtrl.add(null);
          break;
      }
    });
  }

  static Future<String?> getPlatformVersion() async =>
      await _ch.invokeMethod<String>('getPlatformVersion');

  Future<bool> init() async {
    _ensureBindCallbacks();
    final ok = await _ch.invokeMethod<bool>('init');
    return ok ?? false;
  }

  Future<List<Map<String, dynamic>>> listReaders() async {
    final res = await _ch.invokeMethod<List<dynamic>>('listReaders');
    return (res ?? const [])
        .cast<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList();
  }

  Future<bool> connectReader({String? address, String? name}) async {
    final ok = await _ch.invokeMethod<bool>('connectReader', {
      if (address != null) 'address': address,
      if (name != null) 'name': name,
    });
    return ok ?? false;
  }

  Future<String?> getReaderName() => _ch.invokeMethod<String>('getReaderName');

  Future<ZebraPluginMode> getMode() async {
    final s = await _ch.invokeMethod<String>('getMode');
    return _modeFromNative(s);
  }

  Future<ZebraPluginMode> setMode(ZebraPluginMode mode) async {
    final s = await _ch.invokeMethod<String>('setMode', _modeToNative(mode));
    return _modeFromNative(s);
  }

  Future<bool> start() async {
    final ok = await _ch.invokeMethod<bool>('start');
    return ok ?? false;
  }

  Future<bool> stop() async {
    final ok = await _ch.invokeMethod<bool>('stop');
    return ok ?? false;
  }

  Future<bool> setPower(int powerIndex) async {
    final ok = await _ch.invokeMethod<bool>('setPower', powerIndex);
    return ok ?? false;
  }

  Future<bool> disconnect() async {
    final ok = await _ch.invokeMethod<bool>('disconnect');
    return ok ?? false;
  }

  Future<void> dispose() async {
    await _ch.invokeMethod('dispose');
    await _rfidCtrl.close();
    await _barcodeCtrl.close();
    await _disconnectedCtrl.close();
  }
}
