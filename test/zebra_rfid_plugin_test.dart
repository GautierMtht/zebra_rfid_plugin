import 'package:flutter_test/flutter_test.dart';
import 'package:zebra_rfid_plugin/zebra_rfid_plugin.dart';
import 'package:zebra_rfid_plugin/zebra_rfid_plugin_platform_interface.dart';
import 'package:zebra_rfid_plugin/zebra_rfid_plugin_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockZebraRfidPluginPlatform
    with MockPlatformInterfaceMixin
    implements ZebraRfidPluginPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final ZebraRfidPluginPlatform initialPlatform = ZebraRfidPluginPlatform.instance;

  test('$MethodChannelZebraRfidPlugin is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelZebraRfidPlugin>());
  });

  test('getPlatformVersion', () async {
    ZebraRfidPlugin zebraRfidPlugin = ZebraRfidPlugin();
    MockZebraRfidPluginPlatform fakePlatform = MockZebraRfidPluginPlatform();
    ZebraRfidPluginPlatform.instance = fakePlatform;

    expect(await zebraRfidPlugin.getPlatformVersion(), '42');
  });
}
