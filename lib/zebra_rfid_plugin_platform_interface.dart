import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'zebra_rfid_plugin_method_channel.dart';

abstract class ZebraRfidPluginPlatform extends PlatformInterface {
  /// Constructs a ZebraRfidPluginPlatform.
  ZebraRfidPluginPlatform() : super(token: _token);

  static final Object _token = Object();

  static ZebraRfidPluginPlatform _instance = MethodChannelZebraRfidPlugin();

  /// The default instance of [ZebraRfidPluginPlatform] to use.
  ///
  /// Defaults to [MethodChannelZebraRfidPlugin].
  static ZebraRfidPluginPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ZebraRfidPluginPlatform] when
  /// they register themselves.
  static set instance(ZebraRfidPluginPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
