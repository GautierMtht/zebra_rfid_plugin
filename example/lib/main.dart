import 'package:flutter/material.dart';
import 'package:logger/logger.dart';
import 'package:zebra_rfid_plugin/zebra_rfid_plugin.dart';

final logger = Logger();

void main() {
  runApp(const ZebraApp());
}

class ZebraApp extends StatelessWidget {
  const ZebraApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: ZebraDashboard(),
    );
  }
}

class ZebraDashboard extends StatefulWidget {
  const ZebraDashboard({super.key});

  @override
  State<ZebraDashboard> createState() => _ZebraDashboardState();
}

class _ZebraDashboardState extends State<ZebraDashboard> {
  String _readerName = 'Not Connected';
  String _status = 'Idle';
  String _lastRfid = '-';
  String _lastBarcode = '-';

  @override
  void initState() {
    super.initState();

    ZebraRfidPlugin.setListener((data) {
      logger.i('RFID tag read: $data');
      setState(() {
        _lastRfid = data;
        _status = 'Tag scanned';
      });
    });

    // Optionally handle barcode scan events in the future.
  }

  Future<void> _initReader() async {
    setState(() => _status = 'Initializing...');
    logger.i('Initializing reader...');
    try {
      final success = await ZebraRfidPlugin.init();
      if (!mounted) return;

      if (success) {
        logger.i('Reader initialized successfully.');
        final name = await ZebraRfidPlugin.getReaderName();
        logger.i('Reader name: $name');

        setState(() {
          _readerName = name;
          _status = 'Reader Ready';
        });

        _showSnack('Reader initialized successfully.');
      } else {
        logger.w('No RFID reader found.');
        _showError('No RFID reader found.');
      }
    } catch (e, stack) {
      logger.e('Initialization failed', error: e, stackTrace: stack);
      _showError('Initialization failed: $e');
    }
  }

  Future<void> _startRfid() => _perform('Start RFID', ZebraRfidPlugin.startRfid);
  Future<void> _stopRfid() => _perform('Stop RFID', ZebraRfidPlugin.stopRfid);
  Future<void> _switchToRfid() => _perform('Switch to RFID', () => ZebraRfidPlugin.switchTriggerMode('rfid'));
  Future<void> _switchToBarcode() => _perform('Switch to Barcode', () => ZebraRfidPlugin.switchTriggerMode('barcode'));
  Future<void> _setPower() => _perform('Set Power to 200', () => ZebraRfidPlugin.setPower(200));

  Future<void> _perform(String label, Future<void> Function() action) async {
    logger.i('Performing: $label');
    setState(() => _status = '$label...');
    try {
      await action();
      if (!mounted) return;
      logger.i('$label successful.');
      setState(() => _status = '$label done');
    } catch (e, stack) {
      logger.e('Error during $label', error: e, stackTrace: stack);
      _showError('Error during $label: $e');
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    logger.w('Displaying error to user: $message');
    setState(() => _status = 'Error');
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.red),
    );
  }

  void _showSnack(String message) {
    if (!mounted) return;
    logger.i('Displaying snack: $message');
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Zebra RFID/Barcode Scanner'),
        centerTitle: true,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildInfoTile('Reader:', _readerName),
            _buildInfoTile('Status:', _status),
            const SizedBox(height: 20),
            _buildInfoTile('Last RFID Tag:', _lastRfid),
            _buildInfoTile('Last Barcode:', _lastBarcode),
            const SizedBox(height: 30),
            _buildButton('Init Reader', _initReader),
            _buildButton('Start RFID Scan', _startRfid),
            _buildButton('Stop RFID Scan', _stopRfid),
            _buildButton('Set Power to 200', _setPower),
            _buildButton('Switch to RFID Mode', _switchToRfid),
            _buildButton('Switch to Barcode Mode', _switchToBarcode),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoTile(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          SizedBox(width: 140, child: Text(label, style: const TextStyle(fontWeight: FontWeight.bold))),
          Expanded(child: Text(value, overflow: TextOverflow.ellipsis)),
        ],
      ),
    );
  }

  Widget _buildButton(String label, VoidCallback onPressed) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: SizedBox(
        width: double.infinity,
        child: ElevatedButton(onPressed: onPressed, child: Text(label)),
      ),
    );
  }
}
