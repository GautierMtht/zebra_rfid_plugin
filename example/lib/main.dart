import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
      home: const ZebraDashboard(),
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
  bool _connected = false;
  bool _isScanning = false;

  String _lastRfid = '-';
  String _lastBarcode = '-';

  final List<_ScanItem> _rfidHistory = [];
  final List<_ScanItem> _barcodeHistory = [];

  // Minimum power index for RFID reader
  static const int _minPowerIndex = 0;     
  // Maximum power index for RFID reader
  static const int _maxPowerIndex = 300;   
  // Current value of the power slider
  double _powerSlider = 200;              
  // Controller for the power input field
  final TextEditingController _powerCtrl =
      TextEditingController(text: '200');  

  @override
  void initState() {
    super.initState();

  // Listen for RFID events
    ZebraRfidPlugin.setListener((data) {
      logger.i('RFID tag read: $data');
      final item = _ScanItem(type: ScanType.rfid, value: data, when: DateTime.now());
      setState(() {
        _lastRfid = data;
        _rfidHistory.insert(0, item);
        _status = 'RFID tag scanned';
      });
    });

  // (Optional) Listen for barcode events
  // ZebraRfidPlugin.setBarcodeListener((data) {
  //   logger.i('Barcode read: $data');
  //   final item = _ScanItem(type: ScanType.barcode, value: data, when: DateTime.now());
  //   setState(() {
  //     _lastBarcode = data;
  //     _barcodeHistory.insert(0, item);
  //     _status = 'Barcode scanned';
  //   });
  // });
  }

  @override
  void dispose() {
    _powerCtrl.dispose();
    super.dispose();
  }

  Future<void> _initReader() async {
    _setBusy('Initializing...');
    try {
      final success = await ZebraRfidPlugin.init();
      if (!mounted) return;

      if (success) {
        final name = await ZebraRfidPlugin.getReaderName();
        setState(() {
          _readerName = name;
          _connected = true;
          _status = 'Reader Ready';
        });
        _toast('Reader initialized');
      } else {
        _error('No RFID reader found.');
      }
    } on PlatformException catch (e) {
      _error(_prettyError('Initialization failed', e));
    } catch (e, stack) {
      logger.e('Initialization failed', error: e, stackTrace: stack);
      _error('Initialization failed: $e');
    }
  }

  Future<void> _startRfid() async {
    await _do('Start RFID', () async {
      await ZebraRfidPlugin.startRfid();
      setState(() => _isScanning = true);
    });
  }

  Future<void> _stopRfid() async {
    await _do('Stop RFID', () async {
      await ZebraRfidPlugin.stopRfid();
      setState(() => _isScanning = false);
    });
  }

  Future<void> _switchToRfid() =>
      _do('Switch to RFID', () => ZebraRfidPlugin.switchTriggerMode('rfid'));

  Future<void> _switchToBarcode() =>
      _do('Switch to Barcode', () => ZebraRfidPlugin.switchTriggerMode('barcode'));

  Future<void> _applyPowerFromSlider() async {
    final idx = _powerSlider.round().clamp(_minPowerIndex, _maxPowerIndex);
    _powerCtrl.text = idx.toString();
    await _setPowerSafely(idx);
  }

  Future<void> _applyPowerFromField() async {
    final parsed = int.tryParse(_powerCtrl.text.trim());
    if (parsed == null) {
      _error('Invalid power value');
      return;
    }
    final idx = parsed.clamp(_minPowerIndex, _maxPowerIndex);
    setState(() => _powerSlider = idx.toDouble());
    await _setPowerSafely(idx);
  }

  Future<void> _setPowerSafely(int index) async {
    final wasRunning = _isScanning;
    await _do('Set Power ($index)', () async {
      if (wasRunning) {
        try {
          await ZebraRfidPlugin.stopRfid();
        } catch (_) {}
        await Future.delayed(const Duration(milliseconds: 250));
      }

      await ZebraRfidPlugin.setPower(index);

      if (wasRunning) {
        await Future.delayed(const Duration(milliseconds: 150));
        await ZebraRfidPlugin.startRfid();
      }
    });
  }

  Future<void> _do(String label, Future<void> Function() action) async {
    _setBusy('$label...');
    try {
      await action();
      if (!mounted) return;
      _toast('$label done');
      setState(() => _status = '$label done');
    } on PlatformException catch (e) {
      // Typical case encountered: Operation In Progress / Command Not Allowed
      final msg = _prettyError('Error during $label', e);
      _error(msg);
    } catch (e, stack) {
      logger.e('Error during $label', error: e, stackTrace: stack);
      _error('Error during $label: $e');
    }
  }

  void _setBusy(String msg) {
    setState(() => _status = msg);
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  void _error(String msg) {
    if (!mounted) return;
    setState(() => _status = 'Error');
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), backgroundColor: Colors.red),
    );
  }

  String _prettyError(String prefix, PlatformException e) {
    final s = (e.message ?? '').toUpperCase();
    if (s.contains('REGION') && s.contains('NOT')) {
      return '$prefix: Region not set on reader.\n'
          'Set it once via 123RFID (Mobile/Desktop) or StageNow/MDM, then try again.';
    }
    if (s.contains('OPERATION IN PROGRESS') || s.contains('COMMAND NOT ALLOWED')) {
      return '$prefix: Reader is busy (inventory running). '
          'We now stop inventory before changing power — reattempt and it should work.';
    }
    return '$prefix: ${e.message ?? e}';
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Zebra RFD40 – RFID & Barcode'),
          centerTitle: true,
          bottom: const TabBar(
            tabs: [
              Tab(icon: Icon(Icons.rss_feed), text: 'RFID Tags'),
              Tab(icon: Icon(Icons.qr_code_scanner), text: 'Barcodes'),
            ],
          ),
          actions: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: _ConnectionBadge(connected: _connected),
            ),
          ],
        ),
        body: Column(
          children: [
            _headerCard(),
            Expanded(
              child: TabBarView(
                children: [
                  _historyList(_rfidHistory, emptyText: 'No RFID tag yet'),
                  _historyList(_barcodeHistory, emptyText: 'No barcode yet'),
                ],
              ),
            ),
          ],
        ),
        bottomNavigationBar: _actionsBar(),
      ),
    );
  }

  Widget _headerCard() {
    return Card(
      margin: const EdgeInsets.all(16),
      elevation: 0,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _rowInfo('Reader', _readerName),
            _rowInfo('Status', _status),
            const SizedBox(height: 8),
            _rowInfo('Last RFID', _lastRfid),
            _rowInfo('Last Barcode', _lastBarcode),
            const Divider(height: 24),
            _powerControls(),
          ],
        ),
      ),
    );
  }

  Widget _powerControls() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
    // Power controls for RFID reader
    Text('RF Power (index)  •  Range: $_minPowerIndex – $_maxPowerIndex',
      style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: Slider(
                value: _powerSlider,
                min: _minPowerIndex.toDouble(),
                max: _maxPowerIndex.toDouble(),
                divisions: _maxPowerIndex - _minPowerIndex,
                label: _powerSlider.round().toString(),
                onChanged: (v) => setState(() => _powerSlider = v),
                onChangeEnd: (_) => _applyPowerFromSlider(),
              ),
            ),
            const SizedBox(width: 12),
            SizedBox(
              width: 96,
              child: TextField(
                controller: _powerCtrl,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(
                  labelText: 'Index',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                onSubmitted: (_) => _applyPowerFromField(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton.icon(
              onPressed: _applyPowerFromField,
              icon: const Icon(Icons.check),
              label: const Text('Apply'),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          'Tip: RFD40 generally accepts 0–300. The native plugin rejects out-of-range values.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  Widget _actionsBar() {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
        child: Wrap(
          spacing: 10,
          runSpacing: 8,
          alignment: WrapAlignment.center,
          children: [
            FilledButton.icon(
              onPressed: _initReader,
              icon: const Icon(Icons.power_settings_new),
              label: const Text('Init Reader'),
            ),
            ElevatedButton.icon(
              onPressed: _startRfid,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Start RFID'),
            ),
            ElevatedButton.icon(
              onPressed: _stopRfid,
              icon: const Icon(Icons.stop),
              label: const Text('Stop RFID'),
            ),
            OutlinedButton.icon(
              onPressed: _switchToRfid,
              icon: const Icon(Icons.nfc),
              label: const Text('RFID Mode'),
            ),
            OutlinedButton.icon(
              onPressed: _switchToBarcode,
              icon: const Icon(Icons.qr_code),
              label: const Text('Barcode Mode'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _rowInfo(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          SizedBox(
            width: 120,
            child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          Expanded(child: Text(value, overflow: TextOverflow.ellipsis)),
        ],
      ),
    );
  }

  Widget _historyList(List<_ScanItem> items, {required String emptyText}) {
    if (items.isEmpty) {
      return Center(
        child: Text(emptyText, style: TextStyle(color: Colors.grey[600])),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 16),
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (ctx, i) {
        final it = items[i];
        return ListTile(
          onTap: () => _showDetails(it),
          shape: RoundedRectangleBorder(
            side: BorderSide(color: Theme.of(context).colorScheme.outlineVariant),
            borderRadius: BorderRadius.circular(10),
          ),
          leading: CircleAvatar(
            child: Icon(it.type == ScanType.rfid ? Icons.rss_feed : Icons.qr_code),
          ),
          title: Text(it.value, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text(_fmt(it.when)),
          trailing: IconButton(
            icon: const Icon(Icons.copy),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: it.value));
              _toast('Copied');
            },
          ),
        );
      },
    );
  }

  void _showDetails(_ScanItem it) {
    showModalBottomSheet(
      context: context,
      showDragHandle: true,
      builder: (_) {
        return Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(it.type == ScanType.rfid ? Icons.rss_feed : Icons.qr_code),
                  const SizedBox(width: 8),
                  Text(
                    it.type == ScanType.rfid ? 'RFID Tag' : 'Barcode',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ],
              ),
              const SizedBox(height: 8),
              SelectableText(it.value, style: const TextStyle(fontSize: 16)),
              const SizedBox(height: 8),
              Text('When: ${_fmt(it.when)}', style: const TextStyle(color: Colors.grey)),
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: it.value));
                    Navigator.pop(context);
                    _toast('Copied');
                  },
                  icon: const Icon(Icons.copy),
                  label: const Text('Copy'),
                ),
              )
            ],
          ),
        );
      },
    );
  }

  String _fmt(DateTime d) {
    final hh = d.hour.toString().padLeft(2, '0');
    final mm = d.minute.toString().padLeft(2, '0');
    final ss = d.second.toString().padLeft(2, '0');
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-'
        '${d.day.toString().padLeft(2, '0')}  $hh:$mm:$ss';
  }
}

enum ScanType { rfid, barcode }

class _ScanItem {
  final ScanType type;
  final String value;
  final DateTime when;

  _ScanItem({required this.type, required this.value, required this.when});
}

class _ConnectionBadge extends StatelessWidget {
  final bool connected;
  const _ConnectionBadge({required this.connected});

  @override
  Widget build(BuildContext context) {
    final color = connected ? Colors.green : Colors.grey;
    final text = connected ? 'Connected' : 'Disconnected';
    return Row(
      children: [
        Icon(Icons.circle, size: 10, color: color),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(color: color)),
      ],
    );
  }
}
