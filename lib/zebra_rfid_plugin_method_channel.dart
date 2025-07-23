import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'zebra_rfid_plugin_platform_interface.dart';

/// An implementation of [ZebraRfidPluginPlatform] that uses method channels.
class MethodChannelZebraRfidPlugin extends ZebraRfidPluginPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('zebra_rfid_plugin');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
