import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/* ================================================================
   OPTIC — a viewfinder instrument for screen recording.
   Flat · hairline · tabular · red = REC only · amber = caution only.
   Safe insets: >= 48dp (3rem) from top and bottom device edges.
================================================================ */

class Optic {
  static const Color bg = Color(0xFF0A0B0C);
  static const Color ink = Color(0xFFF2F3F1);
  static const Color mut = Color(0xFF6D7276);
  static const Color dim = Color(0xFF3F4448);
  static const Color line = Color(0xFF1D2124);
  static const Color line2 = Color(0xFF2A2F33);
  static const Color rec = Color(0xFFFF453A);
  static const Color amb = Color(0xFFFFB454);
  static const String mono = 'monospace';
  static const double safe = 48.0;
}

TextStyle micro(Color c, {double ls = 2.4, double fs = 9}) =>
    TextStyle(fontFamily: Optic.mono, fontSize: fs, letterSpacing: ls, color: c);

String two(int n) => n.toString().padLeft(2, '0');

String fmtMs(int ms) {
  final s = ms ~/ 1000;
  final d = (ms % 1000) ~/ 100;
  return '${two(s ~/ 60)}:${two(s % 60)}.$d';
}

/* ---------------- logo: the monogramic viewfinder ---------------- */

class LogoPainter extends CustomPainter {
  final Color color;
  const LogoPainter(this.color);

  static Path hand() {
    final p = Path();
    p.moveTo(132, 200);
    p.cubicTo(118, 200, 110, 191, 114, 181);
    p.cubicTo(118, 170, 132, 163, 150, 161);
    p.cubicTo(200, 154, 252, 150, 294, 148);
    p.cubicTo(302, 118, 322, 96, 354, 92);
    p.cubicTo(394, 87, 422, 112, 426, 148);
    p.cubicTo(429, 186, 414, 210, 401, 232);
    p.cubicTo(392, 248, 388, 268, 388, 292);
    p.cubicTo(388, 305, 381, 312, 372, 312);
    p.lineTo(372, 200);
    p.lineTo(140, 200);
    p.cubicTo(137, 200, 134, 200, 132, 200);
    p.close();
    return p;
  }

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 512;
    canvas.scale(s);
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    final h = hand();
    canvas.drawPath(h, paint);
    canvas.save();
    canvas.translate(512, 512);
    canvas.rotate(math.pi);
    canvas.drawPath(h, paint);
    canvas.restore();
    final donut = Path()..fillType = PathFillType.evenOdd;
    donut.addOval(Rect.fromCircle(center: const Offset(236, 256), radius: 34));
    donut.addOval(Rect.fromCircle(center: const Offset(236, 256), radius: 15));
    canvas.drawPath(donut, paint);
    canvas.drawOval(
        Rect.fromCircle(center: const Offset(330, 226), radius: 11), paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/* ---------------- dial: ticks + sweep ---------------- */

class DialPainter extends CustomPainter {
  final double fraction; // 0..1, one revolution per minute
  final bool armed;
  const DialPainter({required this.fraction, required this.armed});

  @override
  void paint(Canvas canvas, Size size) {
    final sc = size.width / 224;
    canvas.scale(sc);
    final c = const Offset(112, 112);

    final tick = Paint()..style = PaintingStyle.stroke..strokeWidth = 1;
    for (var i = 0; i < 60; i++) {
      final a = i * 6 * math.pi / 180;
      final major = i % 5 == 0;
      final r1 = major ? 99.0 : 103.0;
      final r2 = 108.0;
      tick.color = major ? Optic.mut : Optic.dim;
      canvas.drawLine(
        Offset(c.dx + r1 * math.sin(a), c.dy - r1 * math.cos(a)),
        Offset(c.dx + r2 * math.sin(a), c.dy - r2 * math.cos(a)),
        tick,
      );
    }

    final hair = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1
      ..color = Optic.line;
    canvas.drawCircle(c, 88, hair);

    if (armed || fraction > 0) {
      final sweep = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round
        ..color = Optic.ink;
      final f = fraction <= 0 ? 0.0001 : fraction;
      canvas.drawArc(
        Rect.fromCircle(center: c, radius: 97),
        -math.pi / 2,
        2 * math.pi * f,
        false,
        sweep,
      );
    }
  }

  @override
  bool shouldRepaint(covariant DialPainter old) =>
      old.fraction != fraction || old.armed != armed;
}

/* ---------------- channels ---------------- */

const method = MethodChannel('com.graphicmiles.screenrecorder/recorder');
const eventChannel = EventChannel('com.graphicmiles.screenrecorder/events');

/* ---------------- app ---------------- */

void main() {
  runApp(const OpticApp());
}

class OpticApp extends StatelessWidget {
  const OpticApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Screen Recorder',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Optic.bg,
        colorScheme: const ColorScheme.dark(
          surface: Optic.bg,
          onSurface: Optic.ink,
          primary: Optic.ink,
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int tab = 0;
  Map<String, dynamic> status = <String, dynamic>{};
  Map<String, dynamic> settings = <String, dynamic>{};
  List<Map<String, dynamic>> clips = <Map<String, dynamic>>[];
  DateTime? recStart;
  Timer? clock;
  String? flash;
  Timer? flashTimer;

  @override
  void initState() {
    super.initState();
    eventChannel.receiveBroadcastStream().listen(_onEvent);
    clock = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (recStart != null && mounted) setState(() {});
    });
    refresh();
  }

  @override
  void dispose() {
    clock?.cancel();
    flashTimer?.cancel();
    super.dispose();
  }

  Future<void> refresh() async {
    try {
      final s = await method.invokeMapMethod<String, dynamic>('getRecordingStatus');
      final st = await method.invokeMapMethod<String, dynamic>('getSettings');
      final cl = await method.invokeListMethod<Map<dynamic, dynamic>>('getSavedRecordings');
      if (!mounted) return;
      setState(() {
        status = Map<String, dynamic>.from(s ?? {});
        settings = Map<String, dynamic>.from(st ?? {});
        clips = (cl ?? [])
            .map((e) => Map<String, dynamic>.from(e))
            .toList();
      });
    } catch (_) {}
  }

  void _onEvent(dynamic raw) {
    final e = Map<String, dynamic>.from(raw as Map);
    final type = e['type'];
    if (type == 'recordingStarted') {
      recStart = DateTime.now();
    } else if (type == 'recordingSaved') {
      recStart = null;
      _flash('SAVED');
      refresh();
      return;
    } else if (type == 'recordingError') {
      recStart = null;
      _flash((e['message'] ?? 'ERROR').toString().toUpperCase());
    } else if (type == 'recordingStopped' && recStart == null) {
      // stopping/saving phases keep the clock until saved/error
    }
    refresh();
  }

  void _flash(String text) {
    setState(() => flash = text);
    flashTimer?.cancel();
    flashTimer = Timer(const Duration(milliseconds: 1400), () {
      if (mounted) setState(() => flash = null);
    });
  }

  bool get active =>
      ['STARTING', 'RECORDING', 'STOPPING', 'SAVING'].contains(status['state']);

  int get elapsedMs =>
      recStart == null ? 0 : DateTime.now().difference(recStart!).inMilliseconds;

  Future<void> onShutter() async {
    if (active) {
      await method.invokeMethod('stopRecording');
      return;
    }
    try {
      final r = await method.invokeMapMethod<String, dynamic>('requestScreenCapture');
      if (r?['granted'] == true) {
        await method.invokeMethod('startRecording');
      } else {
        _flash('CONSENT REQUIRED');
      }
    } catch (_) {
      _flash('CONSENT REQUIRED');
    }
  }

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final top = math.max(mq.padding.top, Optic.safe);
    final bottom = math.max(mq.padding.bottom, Optic.safe);

    return Scaffold(
      backgroundColor: Optic.bg,
      body: Stack(
        children: [
          Column(
            children: [
              SizedBox(height: top),
              _topBar(),
              Expanded(
                child: [ _view(), _clips(), _setup() ][tab],
              ),
              _nav(),
              SizedBox(height: bottom),
            ],
          ),
          if (flash != null)
            Positioned(
              top: top + 62,
              left: 0,
              right: 0,
              child: Center(
                child: Text(flash!, style: micro(Optic.amb, ls: 3)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _topBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 12, 22, 4),
      child: Row(
        children: [
          CustomPaint(size: const Size(17, 17), painter: const LogoPainter(Optic.ink)),
          const SizedBox(width: 9),
          Text('OPTIC SR', style: micro(Optic.mut, ls: 3)),
          const Spacer(),
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active ? Optic.rec : const Color(0xFF30D158),
            ),
          ),
        ],
      ),
    );
  }

  /* ---------------- VIEW ---------------- */

  Widget _view() {
    final bit = {'standard': '6.0', 'automatic': '8.9', 'high': '12.0'}[
            settings['quality'] ?? 'automatic'] ??
        '8.9';
    final save = (settings['saveMode'] ?? 'gallery').toString().toUpperCase();
    final frac = active ? (elapsedMs / 1000 % 60) / 60 : 0.0;
    final stateLabel = active
        ? (status['state'] == 'RECORDING' ? 'REC' : (status['stateLabel'] ?? '').toString().toUpperCase())
        : 'READY';

    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 6, 22, 10),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text.rich(TextSpan(children: [
                TextSpan(text: '60', style: micro(Optic.ink)),
                TextSpan(text: ' FPS', style: micro(Optic.mut)),
              ])),
              Text.rich(TextSpan(children: [
                TextSpan(text: bit, style: micro(Optic.ink)),
                TextSpan(text: ' M', style: micro(Optic.mut)),
              ])),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: 224,
            height: 224,
            child: Stack(
              alignment: Alignment.center,
              children: [
                CustomPaint(
                  size: const Size(224, 224),
                  painter: DialPainter(fraction: frac, armed: active),
                ),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      active ? fmtMs(elapsedMs) : '00:00.0',
                      style: const TextStyle(
                        fontFamily: Optic.mono,
                        fontSize: 30,
                        letterSpacing: 0.6,
                        color: Optic.ink,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      stateLabel,
                      style: micro(active && status['state'] == 'RECORDING'
                          ? Optic.rec
                          : Optic.mut, ls: 3.4),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('H.264 · MP4', style: micro(Optic.dim, ls: 1.6)),
              Text(save == 'CUSTOM' ? 'CUSTOM' : 'GALLERY', style: micro(Optic.dim, ls: 1.6)),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _cbtn(
                onTap: () => setState(() => tab = 1),
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Icon(Icons.video_library_outlined, size: 15, color: Optic.mut),
                    Positioned(
                      top: -6,
                      right: -8,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                        decoration: BoxDecoration(
                          color: Optic.bg,
                          border: Border.all(color: Optic.line2),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text('${clips.length}', style: micro(Optic.mut, fs: 7.5, ls: 1)),
                      ),
                    ),
                  ],
                ),
              ),
              GestureDetector(
                onTap: onShutter,
                child: Container(
                  width: 70,
                  height: 70,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(color: Optic.ink, width: 2),
                  ),
                  alignment: Alignment.center,
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    width: active ? 26 : 50,
                    height: active ? 26 : 50,
                    decoration: BoxDecoration(
                      color: Optic.ink,
                      borderRadius: BorderRadius.circular(active ? 10 : 25),
                    ),
                  ),
                ),
              ),
              _cbtn(
                onTap: () => setState(() => tab = 2),
                child: Icon(Icons.tune_outlined, size: 15, color: Optic.mut),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            active ? 'TAP TO STOP & SAVE' : 'TAP SHUTTER TO RECORD',
            style: micro(Optic.dim, ls: 3),
          ),
        ],
      ),
    );
  }

  Widget _cbtn({required VoidCallback onTap, required Widget child}) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: Optic.line2),
        ),
        alignment: Alignment.center,
        child: child,
      ),
    );
  }

  /* ---------------- CLIPS ---------------- */

  Widget _clips() {
    final totalMb = clips.fold<double>(
        0, (a, c) => a + ((c['sizeBytes'] as int? ?? 0) / 1e6));
    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 10, 22, 10),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('CLIPS', style: micro(Optic.ink, ls: 3, fs: 11)),
              Text('${clips.length} · ${totalMb.toStringAsFixed(1)} MB',
                  style: micro(Optic.mut, ls: 1.6)),
            ],
          ),
          const SizedBox(height: 8),
          Expanded(
            child: clips.isEmpty
                ? Center(child: Text('NO CLIPS YET', style: micro(Optic.dim, ls: 2)))
                : ListView.builder(
                    itemCount: clips.length,
                    itemBuilder: (_, i) {
                      final c = clips[i];
                      final mb = ((c['sizeBytes'] as int? ?? 0) / 1e6);
                      return GestureDetector(
                        onTap: () => method.invokeMethod(
                            'openRecording', {'uri': c['uri']}),
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 13),
                          decoration: BoxDecoration(
                            border: Border(
                              top: const BorderSide(color: Optic.line),
                              bottom: i == clips.length - 1
                                  ? const BorderSide(color: Optic.line)
                                  : BorderSide.none,
                            ),
                          ),
                          child: Row(
                            children: [
                              Text(two(i + 1), style: micro(Optic.dim)),
                              const SizedBox(width: 12),
                              Container(
                                width: 74,
                                height: 44,
                                decoration: BoxDecoration(
                                  border: Border.all(color: Optic.line2),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                alignment: Alignment.center,
                                child: CustomPaint(
                                    size: const Size(24, 24),
                                    painter: const LogoPainter(Optic.dim)),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      (c['displayName'] ?? 'CLIP').toString(),
                                      style: const TextStyle(
                                          fontFamily: Optic.mono,
                                          fontSize: 9.5,
                                          color: Optic.ink),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      '${mb.toStringAsFixed(1)} MB · ${c['locationLabel'] ?? ''}',
                                      style: micro(Optic.mut, ls: 1),
                                    ),
                                  ],
                                ),
                              ),
                              Container(
                                width: 26,
                                height: 26,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(color: Optic.line2),
                                ),
                                alignment: Alignment.center,
                                child: const Icon(Icons.play_arrow,
                                    size: 10, color: Optic.ink),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  /* ---------------- SETUP ---------------- */

  Widget _setup() {
    final q = (settings['quality'] ?? 'automatic').toString();
    final saveMode = (settings['saveMode'] ?? 'gallery').toString();
    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 10, 22, 10),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('SETUP', style: micro(Optic.ink, ls: 3, fs: 11)),
              Text('SR-03 · V0.4', style: micro(Optic.mut, ls: 1.6)),
            ],
          ),
          const SizedBox(height: 8),
          _srow('QUALITY', _seg(
            [['STD', 'standard'], ['AUTO', 'automatic'], ['HIGH', 'high']],
            q,
            (v) async {
              await method.invokeMethod('saveSettings', {'quality': v});
              refresh();
            },
          )),
          _srow('SAVE TO', _seg(
            [['GALLERY', 'gallery'], ['CUSTOM', 'custom']],
            saveMode,
            (v) async {
              if (v == 'custom') {
                await method.invokeMethod('chooseSaveLocation');
              } else {
                await method.invokeMethod('saveSettings', {'saveMode': 'gallery'});
              }
              refresh();
            },
          )),
          _srow('QS TILE', Text('ADD IN QUICK SETTINGS', style: micro(Optic.mut, ls: 1.6))),
          _srow('DEBUG LOG', GestureDetector(
            onTap: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => const LogsPage())),
            child: Text('VIEW', style: micro(Optic.ink, ls: 2)),
          )),
          _srow('AUDIO', Text('NOT CAPTURED', style: micro(Optic.dim, ls: 1.6))),
        ],
      ),
    );
  }

  Widget _srow(String label, Widget right) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 13),
      decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Optic.line))),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [Text(label, style: micro(Optic.mut, ls: 2.2)), right],
      ),
    );
  }

  Widget _seg(List<List<String>> opts, String current,
      Future<void> Function(String) onPick) {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: Optic.line2),
        borderRadius: BorderRadius.circular(7),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(opts.length, (i) {
          final on = opts[i][1] == current;
          return GestureDetector(
            onTap: () => onPick(opts[i][1]),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
              decoration: BoxDecoration(
                color: on ? Optic.ink : Colors.transparent,
                border: i > 0
                    ? const Border(left: BorderSide(color: Optic.line2))
                    : null,
              ),
              child: Text(opts[i][0],
                  style: micro(on ? Optic.bg : Optic.mut, ls: 1.4, fs: 8)),
            ),
          );
        }),
      ),
    );
  }

  /* ---------------- nav ---------------- */

  Widget _nav() {
    final labels = ['VIEW', 'CLIPS', 'SETUP'];
    return Container(
      decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Optic.line))),
      padding: const EdgeInsets.fromLTRB(26, 8, 26, 4),
      child: Row(
        children: List.generate(labels.length, (i) {
          final on = tab == i;
          return Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                setState(() => tab = i);
                refresh();
              },
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                      width: 14,
                      height: 2,
                      color: on ? Optic.ink : Colors.transparent),
                  const SizedBox(height: 6),
                  Text(labels[i], style: micro(on ? Optic.ink : Optic.dim, ls: 2.8)),
                  const SizedBox(height: 6),
                ],
              ),
            ),
          );
        }),
      ),
    );
  }
}

/* ---------------- logs ---------------- */

class LogsPage extends StatelessWidget {
  const LogsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Optic.bg,
      body: SafeArea(
        child: FutureBuilder<dynamic>(
          future: method.invokeMethod<String>('getDebugLogs'),
          builder: (_, snap) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 16, 22, 8),
                  child: GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    child: Text('‹ BACK', style: micro(Optic.mut, ls: 2.4)),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 8, 22, 12),
                  child: Text('DEBUG LOG', style: micro(Optic.ink, ls: 3, fs: 11)),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(22, 0, 22, 24),
                    child: Text(
                      (snap.data?.toString() ?? '').isEmpty
                          ? 'EMPTY'
                          : snap.data.toString(),
                      style: const TextStyle(
                        fontFamily: Optic.mono,
                        fontSize: 9.5,
                        height: 1.8,
                        color: Optic.mut,
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
