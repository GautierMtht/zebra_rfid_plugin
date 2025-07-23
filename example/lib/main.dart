import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'dart:async';

import 'package:zebra_rfid_plugin/zebra_rfid_plugin.dart';

void main() {
  runApp(MaterialApp(home: ZebraDemoScreen()));
}

class ZebraDemoScreen extends StatefulWidget {
  const ZebraDemoScreen({super.key});

  @override
  State<ZebraDemoScreen> createState() => _ZebraDemoScreenState();
}

class _ZebraDemoScreenState extends State<ZebraDemoScreen> {
  String lastScan = 'None';

  @override
  void initState() {
    super.initState();
    ZebraRfidPlugin.setListener((data) {
      setState(() {
        lastScan = data;
      });
    });
  }

  Future<void> init() async {
    final bluetoothGranted = await Permission.bluetoothConnect
        .request()
        .isGranted;
    final scanGranted = await Permission.bluetoothScan.request().isGranted;
    final locationGranted = await Permission.location.request().isGranted;

    if (!bluetoothGranted || !scanGranted || !locationGranted) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Missing permits')));
      }
      return;
    }

    try {
      final success = await ZebraRfidPlugin.init();
      if (mounted && !success) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to initialize Zebra Reader')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to initialize Zebra Reader : $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Zebra RFID/Barcode')),
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('Last scan: $lastScan'),
          SizedBox(height: 20),
          ElevatedButton(onPressed: init, child: Text('Init Reader')),
          ElevatedButton(
            onPressed: ZebraRfidPlugin.startRfid,
            child: Text('Start RFID'),
          ),
          ElevatedButton(
            onPressed: ZebraRfidPlugin.stopRfid,
            child: Text('Stop RFID'),
          ),
          ElevatedButton(
            onPressed: () => ZebraRfidPlugin.setPower(200),
            child: Text('Set Power to 200'),
          ),
          ElevatedButton(
            onPressed: () => ZebraRfidPlugin.switchTriggerMode('rfid'),
            child: Text('Switch to RFID'),
          ),
          ElevatedButton(
            onPressed: () => ZebraRfidPlugin.switchTriggerMode('barcode'),
            child: Text('Switch to Barcode'),
          ),
        ],
      ),
    );
  }

  Future<void> safeCall(Function action, String label) async {
    try {
      await action();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Erreur $label: $e')));
      }
    }
  }
}
