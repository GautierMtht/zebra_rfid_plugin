import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:zebra_rfid_plugin/zebra_rfid_plugin.dart';

void main() => runApp(const ZebraApp());

class ZebraApp extends StatelessWidget {
  const ZebraApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Zebra RFID & Barcode',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
      home: const ZebraHome(),
    );
  }
}

class ZebraHome extends StatefulWidget {
  const ZebraHome({super.key});

  @override
  State<ZebraHome> createState() => _ZebraHomeState();
}

class _ZebraHomeState extends State<ZebraHome> {
  /// Singleton instance of the ZebraRfidPlugin
  final ZebraRfidPlugin _plugin = ZebraRfidPlugin.instance;

  /// Reader connection and state
  String _readerName = 'Not Connected';
  bool _connected = false;

  /// Current mode and activity state (start/stop)
  ZebraPluginMode _mode = ZebraPluginMode.off;
  bool _active = false;

  String _status = 'Idle';

  /// Last scanned RFID and barcode values, and their histories
  String _lastRfid = '-';
  String _lastBarcode = '-';
  final List<_ScanItem> _rfidHistory = [];
  final List<_ScanItem> _barcodeHistory = [];

  /// Stream subscriptions for RFID, barcode, and disconnect events
  StreamSubscription<String>? _rfidSub;
  StreamSubscription<String>? _barcodeSub;
  StreamSubscription<void>? _discSub;

  /// RFID power control parameters
  static const int _minPowerIndex = 0;
  static const int _maxPowerIndex = 300;
  double _powerSlider = 200;
  final TextEditingController _powerCtrl = TextEditingController(text: '200');

  bool get _rfidAllowed =>
      _mode == ZebraPluginMode.rfid || _mode == ZebraPluginMode.barcodeRfid;

  bool get _barcodeAllowed =>
      _mode == ZebraPluginMode.barcode || _mode == ZebraPluginMode.barcodeRfid;

  @override
  void initState() {
    super.initState();

    // Brancher les streams
    _rfidSub = _plugin.rfidStream.listen((epc) {
      setState(() {
        _lastRfid = epc;
        _rfidHistory.insert(0, _ScanItem(ScanType.rfid, epc, DateTime.now()));
        _status = 'RFID tag scanned';
      });
    });

    _barcodeSub = _plugin.barcodeStream.listen((data) {
      setState(() {
        _lastBarcode = data;
        _barcodeHistory.insert(0, _ScanItem(ScanType.barcode, data, DateTime.now()));
        _status = 'Barcode scanned';
      });
    });

    _discSub = _plugin.disconnected.listen((_) {
      if (!mounted) return;
      setState(() {
        _connected = false;
        _active = false;
        _readerName = 'Not Connected';
        _status = 'Reader disconnected';
      });
      _toast('Reader disconnected');
    });

    _silentAutoInit();
  }

  @override
  void dispose() {
    _rfidSub?.cancel();
    _barcodeSub?.cancel();
    _discSub?.cancel();
    _powerCtrl.dispose();
    super.dispose();
  }

  // ---------- High-level actions ----------

  Future<void> _silentAutoInit() async {
    try {
      final ok = await _plugin.init();
      if (!mounted) return;
      if (ok) {
        final name = await _plugin.getReaderName();
        ZebraPluginMode current = ZebraPluginMode.off;
        try {
          current = await _plugin.getMode();
        } catch (_) {}
        setState(() {
          _readerName = name ?? 'Unknown';
          _connected = true;
          _mode = current;
          _status = 'Reader Ready';
        });
      }
    } on PlatformException {
      // Silently ignore errors during auto-init
    } catch (_) {
      // Silently ignore errors
    }
  }

  Future<void> _initDefault() async {
    _setBusy('Initializing...');
    try {
      final ok = await _plugin.init();
      if (!mounted) return;
      if (ok) {
        final name = await _plugin.getReaderName();
        ZebraPluginMode current = await _plugin.getMode();
        setState(() {
          _connected = true;
          _readerName = name ?? 'Unknown';
          _mode = current;
          _status = 'Reader Ready';
        });
        _toast('Reader initialized');
      } else {
        _error('No reader found.');
      }
    } on PlatformException catch (e) {
      _error(_prettyError('Initialization failed', e));
    } catch (e) {
      _error('Initialization failed: $e');
    }
  }

  Future<void> _disconnect() async {
    _setBusy('Disconnecting...');
    try {
      await _plugin.disconnect();
      if (!mounted) return;
      setState(() {
        _connected = false;
        _active = false;
        _readerName = 'Not Connected';
        _mode = ZebraPluginMode.off;
        _status = 'Disconnected';
      });
      _toast('Disconnected');
    } on PlatformException catch (e) {
      _error('Disconnect failed: ${e.message ?? e.code}');
    } catch (e) {
      _error('Disconnect failed: $e');
    }
  }

  Future<void> _selectAndInit() async {
    final readers = await _fetchReadersOnce();
    if (!mounted) return;

    final chosen = await showModalBottomSheet<_ReaderInfo>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(children: [
                const Icon(Icons.bluetooth),
                const SizedBox(width: 8),
                Text('Select a Zebra Reader', style: Theme.of(context).textTheme.titleLarge),
              ]),
              const SizedBox(height: 8),
              if (readers.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'No reader found. Pair and power on your device.',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                )
              else
                Flexible(
                  child: ListView.separated(
                    shrinkWrap: true,
                    itemCount: readers.length,
                    separatorBuilder: (_, __) => const Divider(height: 0),
                    itemBuilder: (_, i) {
                      final r = readers[i];
                      return ListTile(
                        leading: Icon(
                          r.connected ? Icons.check_circle : Icons.radio_button_unchecked,
                          color: r.connected ? Colors.green : null,
                        ),
                        title: Text(r.name),
                        subtitle: Text(r.address.isNotEmpty ? r.address : 'No address'),
                        onTap: () => Navigator.pop(ctx, r),
                      );
                    },
                  ),
                ),
            ],
          ),
        ),
      ),
    );

    if (chosen == null) return;

    _setBusy('Connecting to ${chosen.name}...');
    try {
      final ok = await _plugin.connectReader(address: chosen.address, name: chosen.name);
      if (!mounted) return;
      if (ok) {
        final name = await _plugin.getReaderName();
        ZebraPluginMode current = await _plugin.getMode();
        setState(() {
          _readerName = name ?? chosen.name;
          _connected = true;
          _mode = current;
          _status = 'Reader Ready';
        });
        _toast('Connected: ${name ?? chosen.name}');
      } else {
        _error('Connection failed.');
      }
    } on PlatformException catch (e) {
      _error(_prettyError('Connection failed', e));
    } catch (e) {
      _error('Connection failed: $e');
    }
  }

  Future<List<_ReaderInfo>> _fetchReadersOnce() async {
    try {
    final raw = await _plugin.listReaders();
    // Map raw device info to strongly typed reader info
    return raw
      .map((e) => _ReaderInfo(
        name: (e['name'] ?? 'Unknown').toString(),
        address: (e['address'] ?? '').toString(),
        connected: e['connected'] == true,
        ))
      .toList();
    } on PlatformException catch (e) {
      final msg = (e.message ?? '').toLowerCase();
      if (msg.contains('bluetooth_connect')) {
        _error('Bluetooth permission is required. Tap "Init" first to grant it, then try again.');
      } else {
        _error('List readers failed: ${e.message ?? e.code}');
      }
      return const [];
    } catch (e) {
      _error('List readers failed: $e');
      return const [];
    }
  }

  Future<void> _setMode(ZebraPluginMode mode) async {
    if (!_connected) {
      _error('Not connected to a reader.');
      return;
    }
    _setBusy('Switching mode...');
    try {
      final applied = await _plugin.setMode(mode);
      if (!mounted) return;
      setState(() {
        _mode = applied;
        _status = 'Mode set to ${_modePretty(applied)}';
      });
      _toast('Mode: ${_modePretty(applied)}');
    } on PlatformException catch (e) {
      _error('Set mode failed: ${e.message ?? e.code}');
    } catch (e) {
      _error('Set mode failed: $e');
    }
  }

  Future<void> _start() async {
    if (!_connected) {
      _error('Not connected to a reader.');
      return;
    }
    if (_mode == ZebraPluginMode.off) {
      _error('Select a mode first.');
      return;
    }
    _setBusy('Starting...');
    try {
      final ok = await _plugin.start();
      if (!mounted) return;
      if (ok) {
        setState(() {
          _active = true;
          _status = 'Active (${_modePretty(_mode)}): press trigger to scan';
        });
        _toast('Active ON');
      } else {
        _error('Start returned false.');
      }
    } on PlatformException catch (e) {
      _error(_prettyError('Start failed', e));
    } catch (e) {
      _error('Start failed: $e');
    }
  }

  Future<void> _stop() async {
    if (!_connected) {
      _error('Not connected to a reader.');
      return;
    }
    if (!_active) {
      _error('Already stopped.');
      return;
    }
    _setBusy('Stopping...');
    try {
      final ok = await _plugin.stop();
      if (!mounted) return;
      if (ok) {
        setState(() {
          _active = false;
          _status = 'Stopped';
        });
        _toast('Active OFF');
      } else {
        _error('Stop returned false.');
      }
    } on PlatformException catch (e) {
      _error('Stop failed: ${e.message ?? e.code}');
    } catch (e) {
      _error('Stop failed: $e');
    }
  }

  Future<void> _applyPowerFromSlider() async {
    final idx = _powerSlider.round().clamp(_minPowerIndex, _maxPowerIndex);
    _powerCtrl.text = idx.toString();
    await _setPower(idx);
  }

  Future<void> _applyPowerFromField() async {
    final parsed = int.tryParse(_powerCtrl.text.trim());
    if (parsed == null) {
      _error('Invalid power value');
      return;
    }
    final idx = parsed.clamp(_minPowerIndex, _maxPowerIndex);
    setState(() => _powerSlider = idx.toDouble());
    await _setPower(idx);
  }

  Future<void> _setPower(int index) async {
    if (!_connected) {
      _error('Not connected to a reader.');
      return;
    }
    if (!_rfidAllowed) {
      _error('Power applies to RFID. Switch to a mode with RFID.');
      return;
    }
    _setBusy('Setting power ($index)...');
    try {
      await _plugin.setPower(index);
      if (!mounted) return;
      _toast('Power set to $index');
      setState(() => _status = 'Power set to $index');
    } on PlatformException catch (e) {
      _error('Set power failed: ${e.message ?? e.code}');
    } catch (e) {
      _error('Set power failed: $e');
    }
  }

  // ---------- UI ----------

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Zebra RFID & Barcode'),
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
              child: _ConnectionBadge(connected: _connected, active: _active),
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
            _rowInfo('Mode', _active ? 'Active • ${_modePretty(_mode)}' : 'Inactive • ${_modePretty(_mode)}'),
            const SizedBox(height: 12),
            _modeSelector(),
            const SizedBox(height: 8),
            _rowInfo('Last RFID', _lastRfid),
            _rowInfo('Last Barcode', _lastBarcode),
            const Divider(height: 24),
            _powerControls(),
            const SizedBox(height: 6),
            Text(
              'Tip: In Active state, press & hold the physical trigger on the sled/terminal to scan. Release to stop.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }

  // --- Responsive mode selector (never wraps labels) ---
  Widget _modeSelector() {
    final buttons = <_ModeBtn>[
      _ModeBtn(
        mode: ZebraPluginMode.off,
        icon: Icons.power_settings_new,
        label: 'OFF',
        tooltip: 'Disable scanning',
      ),
      _ModeBtn(
        mode: ZebraPluginMode.rfid,
        icon: Icons.rss_feed,
        label: 'RFID',
        tooltip: 'RFID only',
      ),
      _ModeBtn(
        mode: ZebraPluginMode.barcode,
        icon: Icons.qr_code,
        label: 'BAR',
        tooltip: 'Barcode only',
      ),
      _ModeBtn(
        mode: ZebraPluginMode.barcodeRfid,
        icon: Icons.qr_code_2,
        label: 'BOTH',
        tooltip: 'Barcode + RFID',
      ),
    ];

    final isSelected = buttons.map((b) => b.mode == _mode).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Mode', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: ToggleButtons(
            onPressed: !_connected
                ? null
                : (index) {
                    final m = buttons[index].mode;
                    if (m != _mode) _setMode(m);
                  },
            isSelected: isSelected,
            borderRadius: BorderRadius.circular(12),
            constraints: const BoxConstraints(minWidth: 92, minHeight: 44),
            children: buttons
                .map(
                  (b) => Tooltip(
                    message: b.tooltip,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(b.icon, size: 18),
                          const SizedBox(width: 6),
                          Text(b.label), // short labels => no wrapping
                        ],
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
        ),
      ],
    );
  }

  Widget _powerControls() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'RF Power (index) • Range: $_minPowerIndex–$_maxPowerIndex',
          style: Theme.of(context).textTheme.titleMedium,
        ),
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
                onChanged: !_connected || !_rfidAllowed ? null : (v) => setState(() => _powerSlider = v),
                onChangeEnd: !_connected || !_rfidAllowed ? null : (_) => _applyPowerFromSlider(),
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
                enabled: _connected && _rfidAllowed,
                onSubmitted: (_) => _applyPowerFromField(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton.icon(
              onPressed: _connected && _rfidAllowed ? _applyPowerFromField : null,
              icon: const Icon(Icons.check),
              label: const Text('Apply'),
            ),
          ],
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
              onPressed: _selectAndInit,
              icon: const Icon(Icons.bluetooth_searching),
              label: const Text('Select & Init'),
            ),
            ElevatedButton.icon(
              onPressed: !_connected ? _initDefault : _disconnect,
              icon: Icon(_connected ? Icons.link_off : Icons.power_settings_new),
              label: Text(_connected ? 'Disconnect' : 'Init'),
            ),
            ElevatedButton.icon(
              onPressed: _connected && !_active && _mode != ZebraPluginMode.off ? _start : null,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Start'),
            ),
            ElevatedButton.icon(
              onPressed: _connected && _active ? _stop : null,
              icon: const Icon(Icons.stop),
              label: const Text('Stop'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _historyList(List<_ScanItem> items, {required String emptyText}) {
    if (items.isEmpty) {
      return Center(child: Text(emptyText, style: TextStyle(color: Colors.grey[600])));
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 16),
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (_, i) {
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
      builder: (_) => Padding(
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
            ),
          ],
        ),
      ),
    );
  }

  // ---------- Helpers ----------

  void _setBusy(String msg) => setState(() => _status = msg);

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  void _error(String msg) {
    if (!mounted) return;
    setState(() => _status = 'Error');
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg), backgroundColor: Colors.red));
  }

  String _prettyError(String prefix, PlatformException e) {
    final s = (e.message ?? '').toUpperCase();
    if (s.contains('PERMISSION_PERMANENTLY_DENIED')) {
      return '$prefix: Permissions permanently denied. Open Android Settings > Apps > This app > Permissions.';
    }
    if (s.contains('PERMISSION_DENIED')) {
      return '$prefix: Permissions required (Bluetooth/Location). Please allow and retry.';
    }
    if (s.contains('BATCHMODE') || s.contains('BATCH_MODE')) {
      return '$prefix: Reader reports Batch Mode in progress. Clear batch in 123RFID or reconnect, then retry.';
    }
    if (s.contains('REGION') && s.contains('NOT')) {
      return '$prefix: Region not set on reader. Configure it once via 123RFID/StageNow, then try again.';
    }
    if (s.contains('OPERATION IN PROGRESS') || s.contains('COMMAND NOT ALLOWED')) {
      return '$prefix: Reader busy. Stop inventory before retrying.';
    }
    if (s.contains('NO_READER') || s.contains('PREFERRED_NOT_FOUND')) {
      return '$prefix: No compatible reader found nearby (or preferred reader not available).';
    }
    return '$prefix: ${e.message ?? e}';
  }

  String _fmt(DateTime d) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${d.year}-${two(d.month)}-${two(d.day)}  ${two(d.hour)}:${two(d.minute)}:${two(d.second)}';
  }

  String _modePretty(ZebraPluginMode m) {
    switch (m) {
      case ZebraPluginMode.off:
        return 'OFF';
      case ZebraPluginMode.rfid:
        return 'RFID';
      case ZebraPluginMode.barcode:
        return 'BARCODE';
      case ZebraPluginMode.barcodeRfid:
        return 'BOTH';
    }
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
}

// ---------- Data models and UI helpers ----------

enum ScanType { rfid, barcode }

class _ScanItem {
  final ScanType type;
  final String value;
  final DateTime when;
  _ScanItem(this.type, this.value, this.when);
}

class _ReaderInfo {
  final String name;
  final String address;
  final bool connected;
  const _ReaderInfo({
    required this.name,
    required this.address,
    required this.connected,
  });
}

class _ConnectionBadge extends StatelessWidget {
  final bool connected;
  final bool active;
  const _ConnectionBadge({required this.connected, required this.active});

  @override
  Widget build(BuildContext context) {
    final color = !connected ? Colors.grey : (active ? Colors.green : Colors.orange);
    final text = !connected ? 'Disconnected' : (active ? 'Active' : 'Connected');
    return Row(
      children: [
        Icon(Icons.circle, size: 10, color: color),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(color: color)),
      ],
    );
  }
}

class _ModeBtn {
  final ZebraPluginMode mode;
  final IconData icon;
  final String label;
  final String tooltip;
  const _ModeBtn({
    required this.mode,
    required this.icon,
    required this.label,
    required this.tooltip,
  });
}
