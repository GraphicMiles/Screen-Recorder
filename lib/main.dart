import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const ScreenRecorderApp());
}

class ScreenRecorderApp extends StatelessWidget {
  const ScreenRecorderApp({super.key});

  @override
  Widget build(BuildContext context) {
    final base = ColorScheme.fromSeed(
      seedColor: const Color(0xFF2F6BFF),
      brightness: Brightness.dark,
    );

    return MaterialApp(
      title: 'Screen Recorder',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: base,
        scaffoldBackgroundColor: const Color(0xFF0E1116),
        cardTheme: CardThemeData(
          color: const Color(0xFF171B22),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final ScreenRecorderApi _api = const ScreenRecorderApi();

  StreamSubscription<Map<String, dynamic>>? _eventSubscription;
  Map<String, dynamic> _status = const <String, dynamic>{};
  Map<String, dynamic> _settings = const <String, dynamic>{};
  List<Map<String, dynamic>> _recordings = const <Map<String, dynamic>>[];
  bool _busy = true;
  String? _lastNotice;
  int _tapCount = 0;
  DateTime? _firstTapAt;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _eventSubscription = _api.events.listen(_handleNativeEvent);
    unawaited(_refreshAll());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _eventSubscription?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refreshAll(showLoader: false));
    }
  }

  Future<void> _refreshAll({bool showLoader = true}) async {
    if (showLoader) {
      setState(() => _busy = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        _api.getRecordingStatus(),
        _api.getSettings(),
        _api.getSavedRecordings(),
      ]);
      if (!mounted) {
        return;
      }
      setState(() {
        _status = results[0] as Map<String, dynamic>;
        _settings = results[1] as Map<String, dynamic>;
        _recordings = results[2] as List<Map<String, dynamic>>;
        _busy = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _busy = false);
      _showSnack('Could not refresh status: $error');
    }
  }

  void _handleNativeEvent(Map<String, dynamic> event) {
    final type = event['type']?.toString() ?? 'event';
    final message = event['message']?.toString();
    if (message != null && message.isNotEmpty) {
      _lastNotice = message;
      _showSnack(message);
    }
    if (type == 'recordingSaved' ||
        type == 'recordingStopped' ||
        type == 'recordingStarted' ||
        type == 'recordingError' ||
        type == 'recordingStateChanged') {
      unawaited(_refreshAll(showLoader: false));
    }
  }

  Future<void> _toggleRecording() async {
    final state = _status['state']?.toString() ?? 'IDLE';
    final isRecording = state == 'RECORDING' ||
        state == 'STARTING' ||
        state == 'STOPPING' ||
        state == 'SAVING';

    if (isRecording) {
      await _stopRecording();
    } else {
      await _startRecording();
    }
  }

  Future<void> _startRecording() async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    try {
      final permission = await _api.requestScreenCapture();
      final granted = permission['granted'] == true;
      if (!granted) {
        setState(() => _busy = false);
        _showSnack(permission['message']?.toString() ?? 'Screen capture was denied.');
        await _refreshAll(showLoader: false);
        return;
      }
      await _api.startRecording();
      _showSnack('Recording started.');
      await _refreshAll(showLoader: false);
    } catch (error) {
      if (mounted) {
        setState(() => _busy = false);
      }
      _showSnack('Could not start recording: $error');
    }
  }

  Future<void> _stopRecording() async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    try {
      await _api.stopRecording();
      await _refreshAll(showLoader: false);
    } catch (error) {
      if (mounted) {
        setState(() => _busy = false);
      }
      _showSnack('Could not stop recording: $error');
    }
  }

  void _handleLocalTripleTap() {
    final now = DateTime.now();
    if (_firstTapAt == null || now.difference(_firstTapAt!).inMilliseconds > 700) {
      _firstTapAt = now;
      _tapCount = 1;
      return;
    }
    _tapCount += 1;
    if (_tapCount >= 3) {
      _tapCount = 0;
      _firstTapAt = null;
      unawaited(_toggleRecording());
    }
  }

  void _showSnack(String message) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _openSettings() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => SettingsPage(api: _api, initialSettings: _settings),
      ),
    );
    await _refreshAll(showLoader: false);
  }

  Future<void> _openRecording(String uri) async {
    try {
      await _api.openRecording(uri);
    } catch (error) {
      _showSnack('Could not open recording: $error');
    }
  }

  @override
  Widget build(BuildContext context) {
    final statusLabel = _status['stateLabel']?.toString() ?? 'Ready';
    final state = _status['state']?.toString() ?? 'IDLE';
    final isRecording = state == 'RECORDING' || state == 'STARTING';
    final lastError = _status['lastError']?.toString();
    final supportNote = _status['globalTapSupportMessage']?.toString() ??
        'Android does not allow invisible global triple-tap capture.';

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _handleLocalTripleTap,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Screen Recorder'),
          centerTitle: false,
          actions: [
            IconButton(
              tooltip: 'Settings',
              onPressed: _openSettings,
              icon: const Icon(Icons.settings_rounded),
            ),
          ],
        ),
        body: _busy && _status.isEmpty
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _refreshAll,
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                  children: [
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                _StatusDot(active: isRecording),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Text(
                                    statusLabel,
                                    style: Theme.of(context)
                                        .textTheme
                                        .headlineSmall,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 16),
                            Text(
                              isRecording
                                  ? 'Local triple-tap or use the Quick Settings tile / notification to stop.'
                                  : 'Local triple-tap to start while this screen is open.',
                              style: Theme.of(context).textTheme.bodyLarge,
                            ),
                            const SizedBox(height: 16),
                            Wrap(
                              spacing: 12,
                              runSpacing: 12,
                              children: [
                                FilledButton.icon(
                                  onPressed: _busy
                                      ? null
                                      : () => unawaited(_toggleRecording()),
                                  icon: Icon(isRecording
                                      ? Icons.stop_circle_outlined
                                      : Icons.fiber_manual_record),
                                  label: Text(isRecording
                                      ? 'Stop recording'
                                      : 'Start recording'),
                                ),
                                OutlinedButton.icon(
                                  onPressed: _busy
                                      ? null
                                      : () => unawaited(_refreshAll(showLoader: false)),
                                  icon: const Icon(Icons.refresh_rounded),
                                  label: const Text('Refresh'),
                                ),
                              ],
                            ),
                            if (lastError != null && lastError.isNotEmpty) ...[
                              const SizedBox(height: 16),
                              Text(
                                lastError,
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                const Icon(Icons.info_outline_rounded),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Text(
                                    'Global control reality',
                                    style: Theme.of(context).textTheme.titleLarge,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            Text(supportNote),
                            const SizedBox(height: 8),
                            const Text(
                              'Closest reliable global fallback: add Screen Recorder to Quick Settings. The recording engine remains independent from gesture detection for future upgrades.',
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Current settings',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                            const SizedBox(height: 12),
                            _InfoRow(
                              label: 'Video quality',
                              value: _settings['quality']?.toString() ?? 'automatic',
                            ),
                            _InfoRow(
                              label: 'Save location',
                              value: _settings['saveModeLabel']?.toString() ?? 'Device / Gallery',
                            ),
                            _InfoRow(
                              label: 'Custom folder',
                              value: _settings['customLocationDescription']?.toString() ?? 'Not selected',
                            ),
                            _InfoRow(
                              label: 'Audio',
                              value: _settings['audioModeLabel']?.toString() ?? 'No audio',
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            'Recent recordings',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                        ),
                        if (_lastNotice != null)
                          Flexible(
                            child: Text(
                              _lastNotice!,
                              textAlign: TextAlign.end,
                              style: Theme.of(context).textTheme.bodySmall,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    if (_recordings.isEmpty)
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(20),
                          child: Text(
                            'No recordings yet. Start a recording, use the phone normally, then stop it to save an MP4.',
                            style: Theme.of(context).textTheme.bodyLarge,
                          ),
                        ),
                      )
                    else
                      ..._recordings.map(
                        (recording) => Card(
                          margin: const EdgeInsets.only(bottom: 12),
                          child: ListTile(
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(20),
                            ),
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 20,
                              vertical: 8,
                            ),
                            onTap: () => _openRecording(
                              recording['uri']?.toString() ?? '',
                            ),
                            leading: const Icon(Icons.movie_creation_outlined),
                            title: Text(
                              recording['displayName']?.toString() ?? 'Recording',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              '${recording['locationLabel'] ?? 'Saved'} • ${_formatTimestamp(recording['savedAtMs'])} • ${_formatBytes(recording['sizeBytes'])}',
                            ),
                            trailing: const Icon(Icons.open_in_new_rounded),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
      ),
    );
  }

  String _formatTimestamp(dynamic raw) {
    if (raw is! num) {
      return 'Unknown time';
    }
    final date = DateTime.fromMillisecondsSinceEpoch(raw.toInt());
    String two(int value) => value.toString().padLeft(2, '0');
    return '${date.year}-${two(date.month)}-${two(date.day)} ${two(date.hour)}:${two(date.minute)}';
  }

  String _formatBytes(dynamic raw) {
    if (raw is! num || raw <= 0) {
      return 'size unknown';
    }
    const units = ['B', 'KB', 'MB', 'GB'];
    double value = raw.toDouble();
    int index = 0;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index += 1;
    }
    return '${value.toStringAsFixed(value >= 100 || index == 0 ? 0 : 1)} ${units[index]}';
  }
}

class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.api,
    required this.initialSettings,
  });

  final ScreenRecorderApi api;
  final Map<String, dynamic> initialSettings;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late String _quality;
  late String _saveMode;
  late String _customLocationDescription;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _quality = widget.initialSettings['quality']?.toString() ?? 'automatic';
    _saveMode = widget.initialSettings['saveMode']?.toString() ?? 'gallery';
    _customLocationDescription =
        widget.initialSettings['customLocationDescription']?.toString() ??
            'Not selected';
  }

  Future<void> _saveSettings() async {
    setState(() => _loading = true);
    try {
      final updated = await widget.api.saveSettings(
        quality: _quality,
        saveMode: _saveMode,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _quality = updated['quality']?.toString() ?? _quality;
        _saveMode = updated['saveMode']?.toString() ?? _saveMode;
        _customLocationDescription =
            updated['customLocationDescription']?.toString() ??
                _customLocationDescription;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _loading = false);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('Could not save settings: $error')));
    }
  }

  Future<void> _chooseLocation() async {
    setState(() => _loading = true);
    try {
      final updated = await widget.api.chooseSaveLocation();
      if (!mounted) {
        return;
      }
      setState(() {
        _saveMode = updated['saveMode']?.toString() ?? 'custom';
        _customLocationDescription =
            updated['customLocationDescription']?.toString() ??
                _customLocationDescription;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _loading = false);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('Could not choose folder: $error')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Video quality',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment<String>(
                        value: 'automatic',
                        label: Text('Automatic'),
                      ),
                      ButtonSegment<String>(
                        value: 'high',
                        label: Text('High'),
                      ),
                      ButtonSegment<String>(
                        value: 'standard',
                        label: Text('Standard'),
                      ),
                    ],
                    selected: <String>{_quality},
                    onSelectionChanged: _loading
                        ? null
                        : (selection) {
                            final value = selection.first;
                            setState(() => _quality = value);
                            unawaited(_saveSettings());
                          },
                    multiSelectionEnabled: false,
                    showSelectedIcon: false,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Automatic selects a bitrate from the current display size and refresh rate. High uses more bitrate. Standard keeps files smaller without forcing an unusually soft picture.',
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Save location',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment<String>(
                        value: 'gallery',
                        icon: Icon(Icons.photo_library_outlined),
                        label: Text('Device / Gallery'),
                      ),
                      ButtonSegment<String>(
                        value: 'custom',
                        icon: Icon(Icons.folder_open_rounded),
                        label: Text('Custom location'),
                      ),
                    ],
                    selected: <String>{_saveMode},
                    onSelectionChanged: _loading
                        ? null
                        : (selection) async {
                            final value = selection.first;
                            setState(() => _saveMode = value);
                            await _saveSettings();
                            if (value == 'custom' &&
                                _customLocationDescription == 'Not selected') {
                              await _chooseLocation();
                            }
                          },
                    multiSelectionEnabled: false,
                    showSelectedIcon: false,
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'Device / Gallery saves with MediaStore to Movies/Screen Recorder so the video appears in normal media apps.',
                  ),
                  const SizedBox(height: 8),
                  Text('Custom folder: $_customLocationDescription'),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: _loading ? null : _chooseLocation,
                    icon: const Icon(Icons.folder_open_rounded),
                    label: const Text('Choose custom folder'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Audio',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'This first build records screen video only. Audio is intentionally off to keep the native pipeline small, understandable, and reliable.',
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.active});

  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 14,
      height: 14,
      decoration: BoxDecoration(
        color: active ? const Color(0xFFFF4D4F) : const Color(0xFF4CAF50),
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(
            color: (active ? const Color(0xFFFF4D4F) : const Color(0xFF4CAF50))
                .withValues(alpha: 0.35),
            blurRadius: 14,
            spreadRadius: 1,
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(
              label,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
        ],
      ),
    );
  }
}

class ScreenRecorderApi {
  const ScreenRecorderApi();

  static const MethodChannel _methodChannel =
      MethodChannel('com.graphicmiles.screenrecorder/recorder');
  static const EventChannel _eventChannel =
      EventChannel('com.graphicmiles.screenrecorder/events');

  Stream<Map<String, dynamic>> get events => _eventChannel
      .receiveBroadcastStream()
      .map((dynamic event) => Map<String, dynamic>.from(event as Map));

  Future<Map<String, dynamic>> requestScreenCapture() =>
      _invokeMap('requestScreenCapture');

  Future<bool> startRecording() async {
    final result = await _methodChannel.invokeMethod<dynamic>('startRecording');
    return result == true;
  }

  Future<bool> stopRecording() async {
    final result = await _methodChannel.invokeMethod<dynamic>('stopRecording');
    return result == true;
  }

  Future<Map<String, dynamic>> getRecordingStatus() =>
      _invokeMap('getRecordingStatus');

  Future<Map<String, dynamic>> getSettings() => _invokeMap('getSettings');

  Future<Map<String, dynamic>> saveSettings({
    required String quality,
    required String saveMode,
  }) =>
      _invokeMap('saveSettings', <String, dynamic>{
        'quality': quality,
        'saveMode': saveMode,
      });

  Future<Map<String, dynamic>> chooseSaveLocation() =>
      _invokeMap('chooseSaveLocation');

  Future<List<Map<String, dynamic>>> getSavedRecordings() async {
    final raw = await _methodChannel.invokeMethod<dynamic>('getSavedRecordings');
    final list = (raw as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => Map<String, dynamic>.from(item as Map))
        .toList(growable: false);
    return list;
  }

  Future<bool> openRecording(String uri) async {
    final result = await _methodChannel
        .invokeMethod<dynamic>('openRecording', <String, dynamic>{'uri': uri});
    return result == true;
  }

  Future<Map<String, dynamic>> _invokeMap(String method,
      [Map<String, dynamic>? arguments]) async {
    final raw = await _methodChannel.invokeMethod<dynamic>(method, arguments);
    return Map<String, dynamic>.from(raw as Map);
  }
}
