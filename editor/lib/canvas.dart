import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show mapEquals, setEquals;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';

import 'actions.dart';
import 'model.dart';
import 'protocol.dart';
import 'snapping.dart';
import 'theme.dart';
import 'viewport.dart';

class PageCanvas extends StatefulWidget {
  const PageCanvas({super.key, required this.onSizeChanged});

  final ValueChanged<Size> onSizeChanged;

  @override
  State<PageCanvas> createState() => _PageCanvasState();
}

const double _dragSlop = 3;

const Duration _doubleClickWindow = Duration(milliseconds: 320);
const double _doubleClickSlop = 5;

enum _Gesture { idle, pressed, move, marquee, resize, pan }

class _PageCanvasState extends State<PageCanvas> {
  final Map<String, ui.Image> _thumbnails = {};
  final Set<String> _decoding = {};

  ResizeHandle? _hoveredHandle;
  String? _hoveredId;

  Rect? _marquee;
  Set<String> _marqueeBase = const {};

  int? _pointer;
  _Gesture _gesture = _Gesture.idle;
  Offset _downAt = Offset.zero;
  Offset _lastLocal = Offset.zero;
  Element? _pressedOn;
  bool _pressAdditive = false;

  bool _decideOnRelease = false;

  Duration _lastClickAt = Duration.zero;
  Offset _lastClickWhere = Offset.zero;

  bool _spaceHeld = false;
  Size _size = Size.zero;

  EditorModel get _model => EditorScope.read(context);

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    super.dispose();
  }

  bool _onKey(KeyEvent event) {
    final held = !textEditingHasFocus() &&
        HardwareKeyboard.instance.logicalKeysPressed.contains(LogicalKeyboardKey.space);
    if (held != _spaceHeld && mounted) setState(() => _spaceHeld = held);
    return false;
  }

  bool get _additive =>
      HardwareKeyboard.instance.isShiftPressed || HardwareKeyboard.instance.isMetaPressed;

  bool get _bypassSnapping => HardwareKeyboard.instance.isAltPressed;

  Element? get _resizable {
    final model = _model;
    if (model.isPreviewing) return null;
    final element = model.soleSelection;
    if (element == null) return null;
    return model.lockReason(element.id) == null ? element : null;
  }

  ResizeHandle? _handleAt(Offset local) {
    final element = _resizable;
    if (element == null) return null;
    final model = _model;
    final rect = model.viewport.toScreenRect(model.renderBoundsOf(element));
    const grab = 8.0;
    for (final handle in ResizeHandle.values) {
      if ((handle.anchorOn(rect) - local).distance <= grab) return handle;
    }
    return null;
  }

  void _onPointerSignal(PointerSignalEvent event) {
    final model = _model;
    if (event is PointerScaleEvent) {
      model.zoomAt(event.localPosition, event.scale);
      return;
    }
    if (event is! PointerScrollEvent) return;
    final zooming = HardwareKeyboard.instance.isControlPressed ||
        HardwareKeyboard.instance.isMetaPressed;
    if (zooming) {
      model.zoomAt(event.localPosition, math.exp(-event.scrollDelta.dy / 250));
    } else {
      final delta = HardwareKeyboard.instance.isShiftPressed
          ? Offset(-event.scrollDelta.dy, 0)
          : -event.scrollDelta;
      model.pan(delta);
    }
  }

  void _onPointerDown(PointerDownEvent event) {
    if (_pointer != null) return;
    final model = _model;
    final local = event.localPosition;

    if (event.buttons & kMiddleMouseButton != 0 || _spaceHeld) {
      _pointer = event.pointer;
      _downAt = local;
      _lastLocal = local;
      setState(() => _gesture = _Gesture.pan);
      return;
    }
    if (event.kind == PointerDeviceKind.mouse && event.buttons & kPrimaryMouseButton == 0) {
      return;
    }

    _pointer = event.pointer;
    _downAt = local;
    _lastLocal = local;
    _pressAdditive = _additive;

    final handle = _handleAt(local);
    if (handle != null) {
      model.beginResize(handle);
      _gesture = _Gesture.resize;
      return;
    }

    final leaf = model.hitTest(model.viewport.toDesign(local));
    if (leaf == null) {
      _pressedOn = null;
      _decideOnRelease = false;
      _marqueeBase = _pressAdditive ? {...model.selection} : const {};
      _gesture = _Gesture.pressed;
      return;
    }

    final target = _grabbed(model, leaf);
    _pressedOn = target;
    _gesture = _Gesture.pressed;
    if (model.selection.contains(target.id)) {
      _decideOnRelease = true;
    } else {
      _decideOnRelease = false;
      model.select(target.id, additive: _pressAdditive);
    }
  }

  Element _grabbed(EditorModel model, Element leaf) {
    final lineage = model.lineageOf(leaf);
    for (final element in lineage) {
      if (model.selection.contains(element.id)) return element;
    }
    return lineage.last;
  }

  void _onPointerMove(PointerMoveEvent event) {
    if (event.pointer != _pointer) return;
    final model = _model;
    final local = event.localPosition;
    final delta = local - _lastLocal;
    _lastLocal = local;

    switch (_gesture) {
      case _Gesture.pan:
        model.pan(delta);
      case _Gesture.move:
      case _Gesture.resize:
        model.dragBy(delta / model.viewport.scale, bypassSnapping: _bypassSnapping);
      case _Gesture.marquee:
        _stretchMarquee(model, local);
      case _Gesture.pressed:
        if ((local - _downAt).distance < _dragSlop) return;
        if (_pressedOn == null) {
          if (!_pressAdditive) model.clearSelection();
          setState(() => _gesture = _Gesture.marquee);
          _stretchMarquee(model, local);
        } else {
          _gesture = _Gesture.move;
          model.dragBy((local - _downAt) / model.viewport.scale, bypassSnapping: _bypassSnapping);
        }
      case _Gesture.idle:
        break;
    }
  }

  void _onPointerUp(PointerUpEvent event) {
    if (event.pointer != _pointer) return;
    final model = _model;

    switch (_gesture) {
      case _Gesture.pressed:
        _click(model, event.timeStamp);
      case _Gesture.marquee:
        setState(() => _marquee = null);
      case _Gesture.move:
      case _Gesture.resize:
        model.endGesture();
      case _Gesture.pan:
      case _Gesture.idle:
        break;
    }
    _release();
  }

  void _onPointerCancel(PointerCancelEvent event) {
    if (event.pointer != _pointer) return;
    if (_gesture == _Gesture.move || _gesture == _Gesture.resize) _model.endGesture();
    if (_marquee != null) setState(() => _marquee = null);
    _release();
  }

  void _release() {
    _pointer = null;
    _pressedOn = null;
    _decideOnRelease = false;
    _marqueeBase = const {};
    if (_gesture != _Gesture.idle) setState(() => _gesture = _Gesture.idle);
  }

  void _click(EditorModel model, Duration at) {
    final target = _pressedOn;
    final near = (_downAt - _lastClickWhere).distance <= _doubleClickSlop;
    final quick = at - _lastClickAt <= _doubleClickWindow;
    _lastClickAt = at;
    _lastClickWhere = _downAt;

    if (target == null) {
      if (!_pressAdditive) model.clearSelection();
      return;
    }

    if (quick && near) {
      _drillInto(model, target);
      return;
    }
    if (_decideOnRelease) model.select(target.id, additive: _pressAdditive);
  }

  void _drillInto(EditorModel model, Element grabbed) {
    final leaf = model.hitTest(model.viewport.toDesign(_downAt));
    if (leaf == null) return;
    final lineage = model.lineageOf(leaf);
    final at = lineage.indexWhere((e) => e.id == grabbed.id);
    final next = at > 0 ? lineage[at - 1] : leaf;
    model.select(next.id);
  }

  void _stretchMarquee(EditorModel model, Offset local) {
    final viewport = model.viewport;
    final rect = Rect.fromPoints(viewport.toDesign(_downAt), viewport.toDesign(local));
    setState(() => _marquee = rect);
    model.setSelection({
      ..._marqueeBase,
      for (final element in model.snapshot?.elements ?? const <Element>[])
        if (element.enabled && rect.overlaps(model.renderBoundsOf(element))) element.id,
    });
  }

  void _onHover(Offset local) {
    final model = _model;
    final handle = _handleAt(local);
    final hit = handle == null ? model.hitTest(model.viewport.toDesign(local)) : null;
    final id = hit == null ? null : _grabbed(model, hit).id;
    if (handle != _hoveredHandle || id != _hoveredId) {
      setState(() {
        _hoveredHandle = handle;
        _hoveredId = id;
      });
    }
  }

  MouseCursor get _cursor {
    if (_gesture == _Gesture.pan) return SystemMouseCursors.grabbing;
    if (_spaceHeld) return SystemMouseCursors.grab;
    return _hoveredHandle?.cursor ??
        (_hoveredId != null ? SystemMouseCursors.click : MouseCursor.defer);
  }

  void _syncThumbnails(EditorModel model) {
    for (final video in model.videos) {
      if (!video.hasThumbnail) continue;
      final key = _thumbnailKey(video);
      if (_thumbnails.containsKey(key) || _decoding.contains(key)) continue;
      _decoding.add(key);
      _decode(key, video.thumbnail);
    }
  }

  static String _thumbnailKey(VideoEntry video) => '${video.name}:${video.thumbnail.length}';

  Future<void> _decode(String key, String data) async {
    try {
      final codec = await ui.instantiateImageCodec(base64Decode(data));
      final frame = await codec.getNextFrame();
      if (!mounted) return;
      setState(() {
        _thumbnails
          ..removeWhere((existing, _) => existing.split(':').first == key.split(':').first)
          ..[key] = frame.image;
      });
    } catch (_) {
      if (mounted) setState(() {});
    } finally {
      _decoding.remove(key);
    }
  }

  Map<String, ui.Image> _thumbnailsByClip(EditorModel model) {
    final byClip = <String, ui.Image>{};
    for (final video in model.videos) {
      final image = _thumbnails[_thumbnailKey(video)];
      if (image != null) byClip[video.name] = image;
    }
    return byClip;
  }

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final snapshot = model.snapshot;
    if (snapshot == null) return const SizedBox.expand();
    _syncThumbnails(model);

    return LayoutBuilder(
      builder: (context, constraints) {
        final size = Size(constraints.maxWidth, constraints.maxHeight);
        if (size != _size) {
          _size = size;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            widget.onSizeChanged(size);
            model.viewportSize = size;
            model.ensureFitted(size);
          });
        }

        return MouseRegion(
          cursor: _cursor,
          onHover: (event) => _onHover(event.localPosition),
          onExit: (_) {
            if (_hoveredHandle != null || _hoveredId != null) {
              setState(() {
                _hoveredHandle = null;
                _hoveredId = null;
              });
            }
          },
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: _onPointerDown,
            onPointerMove: _onPointerMove,
            onPointerUp: _onPointerUp,
            onPointerCancel: _onPointerCancel,
            onPointerSignal: _onPointerSignal,
            child: ClipRect(
              child: RepaintBoundary(
                child: CustomPaint(
                  painter: _PagePainter(
                    snapshot: snapshot,
                    selection: model.selection,
                    guides: model.guides,
                    viewport: model.viewport,
                    tokens: context.tokens,
                    handlesOn: _resizable?.id,
                    hoveredId: _hoveredId,
                    marquee: _marquee,
                    thumbnails: _thumbnailsByClip(model),
                    live: model.liveRects,
                    showHitRegions: model.showHitRegions,
                  ),
                  size: Size.infinite,
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _PagePainter extends CustomPainter {
  _PagePainter({
    required this.snapshot,
    required this.selection,
    required this.guides,
    required this.viewport,
    required this.tokens,
    required this.handlesOn,
    required this.hoveredId,
    required this.marquee,
    required this.thumbnails,
    required this.live,
    this.showHitRegions = false,
  });

  final PageSnapshot snapshot;
  final Set<String> selection;
  final List<Guide> guides;
  final CanvasViewport viewport;
  final EditorTokens tokens;
  final String? handlesOn;
  final String? hoveredId;
  final Rect? marquee;

  final Map<String, ui.Image> thumbnails;

  final Map<String, Rect> live;

  final bool showHitRegions;

  Rect _rectOf(Element element) => live[element.id] ?? snapshot.renderRectOf(element);

  Rect _hitRectOf(Element element) {
    final moved = live[element.id];
    if (moved == null) return snapshot.hitRectOf(element);
    final render = snapshot.renderRectOf(element);
    final hit = snapshot.hitRectOf(element);
    return hit.shift(moved.topLeft - render.topLeft);
  }

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.clipRect(Offset.zero & size);

    canvas.drawRect(Offset.zero & size, Paint()..color = tokens.canvasVoid);

    final page = Rect.fromLTWH(0, 0, snapshot.screen.width, snapshot.screen.height);
    final pageOnScreen = viewport.toScreenRect(page);
    canvas.drawRect(pageOnScreen, Paint()..color = tokens.canvasPage);
    canvas.drawRect(
      pageOnScreen,
      Paint()
        ..color = tokens.border
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1,
    );

    canvas.save();
    canvas.translate(viewport.offset.dx, viewport.offset.dy);
    canvas.scale(viewport.scale);

    final ordered = [...snapshot.elements]
      ..sort((a, b) => a.effectiveLayer.compareTo(b.effectiveLayer));
    final visible = viewport.visibleDesignRect(size);
    for (final element in ordered) {
      if (!element.enabled) continue;
      if (!_rectOf(element).overlaps(visible)) continue;
      _paintElement(canvas, element);
    }

    if (hoveredId != null && !selection.contains(hoveredId)) {
      final hovered = snapshot.elements.where((e) => e.id == hoveredId).firstOrNull;
      if (hovered != null) {
        canvas.drawRect(
          _rectOf(hovered),
          Paint()
            ..color = tokens.accent.withValues(alpha: 0.5)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1 / viewport.scale,
        );
      }
    }

    if (showHitRegions) _paintHitRegions(canvas, visible);

    for (final element in snapshot.elements) {
      if (selection.contains(element.id)) _paintSelection(canvas, element);
    }
    _paintSafeArea(canvas);
    _paintGuides(canvas, size);
    _paintMarquee(canvas);
    canvas.restore();
    canvas.restore();
  }

  void _paintSafeArea(Canvas canvas) {
    final page = Rect.fromLTWH(0, 0, snapshot.screen.width, snapshot.screen.height);
    final paint = Paint()
      ..color = tokens.border
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1 / viewport.scale;
    canvas.drawRect(page, paint);
  }

  void _paintHitRegions(Canvas canvas, Rect visible) {
    for (final element in snapshot.elements) {
      final geometry = snapshot.geometryOf(element.id);
      if (geometry == null || !geometry.takesInput) continue;
      final hit = _hitRectOf(element);
      if (!hit.overlaps(visible)) continue;
      canvas.drawRect(hit, Paint()..color = tokens.hitbox.withValues(alpha: 0.10));
      canvas.drawRect(
        hit,
        Paint()
          ..color = tokens.hitbox.withValues(alpha: 0.7)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1 / viewport.scale,
      );
    }
  }

  void _paintElement(Canvas canvas, Element element) {
    final rect = _rectOf(element);
    final opacity = element.opacity / 255.0;

    canvas.save();
    final rotation = snapshot.rotationOf(element);
    if (rotation != 0) {
      canvas.translate(rect.center.dx, rect.center.dy);
      canvas.rotate(rotation * math.pi / 180);
      canvas.translate(-rect.center.dx, -rect.center.dy);
    }

    if (element.isHitbox) {
      canvas.drawRect(rect, Paint()..color = tokens.hitbox.withValues(alpha: 0.14));
      canvas.drawRect(
        rect,
        Paint()
          ..color = tokens.hitbox.withValues(alpha: 0.55)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1 / viewport.scale,
      );
      canvas.restore();
      return;
    }

    if (element.isText) {
      _paintText(canvas, element, rect, opacity);
      canvas.restore();
      return;
    }

    final outline = element.outline;
    if (outline != null && outline.size > 0) {
      _paintBox(
        canvas,
        rect.inflate(outline.size),
        Color(0xFF000000 | outline.color).withValues(alpha: opacity),
        element,
      );
    }
    _paintBox(canvas, rect, Color(0xFF000000 | element.color).withValues(alpha: opacity), element);
    canvas.restore();
  }

  void _paintBox(Canvas canvas, Rect rect, Color color, Element element) {
    final paint = Paint()..color = color;
    final opacity = color.a;
    switch (element.type) {
      case 'CIRCLE':
        canvas.drawOval(rect, paint);
      case 'GRADIENT':
        canvas.drawRect(
          rect,
          Paint()
            ..shader = LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [color, color.withValues(alpha: 0)],
            ).createShader(rect),
        );
      case 'VIDEO':
        final image = thumbnails[element.item ?? ''];
        if (image == null) {
          canvas.drawRect(rect, Paint()..color = tokens.surfaceSunken);
          canvas.drawRect(
            rect,
            Paint()
              ..color = tokens.border
              ..style = PaintingStyle.stroke
              ..strokeWidth = 1 / viewport.scale,
          );
        } else {
          canvas.drawImageRect(
            image,
            Offset.zero & Size(image.width.toDouble(), image.height.toDouble()),
            rect,
            Paint()..color = Colors.white.withValues(alpha: color.a),
          );
        }
      case 'PROGRESS':
        final barHeight = math.max(1.0, rect.height / 4);
        final bar = Rect.fromLTWH(
            rect.left, rect.top + (rect.height - barHeight) / 2, rect.width, barHeight);
        canvas.drawRRect(RRect.fromRectAndRadius(bar, Radius.circular(barHeight / 2)), paint);
      case 'TEXT_INPUT':
        final input = element.input ?? const TextInputDef();
        final fieldRadius = element.rounding?.resolvedRadius(rect.width, rect.height) ?? 0;
        final field = RRect.fromRectAndRadius(rect, Radius.circular(fieldRadius));
        canvas.drawRRect(field, paint);
        canvas.drawRRect(
          field,
          Paint()
            ..color = Colors.white.withValues(alpha: 0.18)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1 / viewport.scale,
        );
        final shown = input.display();
        final labelTop = rect.top + rect.height / 2 - 0.1514 * input.fontSize;
        if (shown.isNotEmpty) {
          _paintGlyphRun(
            canvas,
            text: shown,
            font: element.font,
            scale: input.fontSize,
            lineWidth: element.lineWidth,
            left: rect.left + input.padding,
            top: labelTop,
            color: input.value.isEmpty
                ? const Color(0xFF6A6A7A)
                : Color(0xFF000000 | element.color),
          );
        }
        final caretX = rect.left + input.padding;
        final inset = rect.height * 0.28;
        canvas.drawLine(
          Offset(caretX, rect.top + inset),
          Offset(caretX, rect.bottom - inset),
          Paint()
            ..color = Colors.white.withValues(alpha: 0.45)
            ..strokeWidth = 1.5 / viewport.scale,
        );
      case 'TOGGLE':
        final toggle = element.toggle ?? const ToggleDef();
        final trackRadius = rect.height / 2;
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect, Radius.circular(trackRadius)),
          Paint()..color = Color(0xFF000000 | toggle.trackColor()).withValues(alpha: opacity),
        );
        final knob = math.max(2.0, rect.height - 6);
        final travel = math.max(0.0, rect.width - knob - 6);
        canvas.drawOval(
          Rect.fromLTWH(rect.left + 3 + (toggle.value ? travel : 0), rect.top + 3, knob, knob),
          Paint()..color = Color(0xFF000000 | toggle.knobColor),
        );
      case 'SLIDER':
        final slider = element.slider ?? const SliderDef();
        final trackHeight = math.max(2.0, rect.height * 0.35);
        final track = Rect.fromLTWH(
          rect.left, rect.top + (rect.height - trackHeight) / 2, rect.width, trackHeight);
        canvas.drawRRect(
          RRect.fromRectAndRadius(track, Radius.circular(trackHeight / 2)),
          Paint()..color = Color(0xFF000000 | slider.trackColor).withValues(alpha: opacity),
        );
        final knob = math.max(trackHeight, rect.height);
        final travel = math.max(0.0, rect.width - knob);
        final filled = knob / 2 + travel * slider.fraction;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(track.left, track.top, filled, trackHeight),
            Radius.circular(trackHeight / 2),
          ),
          Paint()..color = Color(0xFF000000 | slider.fillColor).withValues(alpha: opacity),
        );
        canvas.drawOval(
          Rect.fromLTWH(rect.left + travel * slider.fraction, rect.top, knob, knob),
          Paint()..color = Color(0xFF000000 | slider.knobColor),
        );
      case 'BLUR':
        final blurRadius = element.rounding?.resolvedRadius(rect.width, rect.height) ?? 0;
        final rounded = RRect.fromRectAndRadius(rect, Radius.circular(blurRadius));
        canvas.drawRRect(rounded, Paint()..color = color.withValues(alpha: color.a * 0.45));
        canvas.drawRRect(
          rounded,
          Paint()
            ..color = Colors.white.withValues(alpha: 0.22)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1 / viewport.scale,
        );
      default:
        final radius = element.supportsRounding
            ? (element.rounding?.resolvedRadius(rect.width, rect.height) ?? 0)
            : 0.0;
        if (radius > 0) {
          canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)), paint);
        } else {
          canvas.drawRect(rect, paint);
        }
    }
  }

  void _paintText(Canvas canvas, Element element, Rect rect, double opacity) {
    _paintGlyphRun(
      canvas,
      text: element.text,
      font: element.font,
      scale: element.height,
      lineWidth: element.lineWidth,
      left: rect.left,
      top: rect.top,
      color: Color(0xFF000000 | element.color).withValues(alpha: opacity),
    );
  }

  void _paintGlyphRun(
    Canvas canvas, {
    required String text,
    required String font,
    required double scale,
    required int lineWidth,
    required double left,
    required double top,
    required Color color,
  }) {
    if (text.isEmpty) return;
    final metrics = snapshot.metrics;
    final perFontPixel = metrics.designPerFontPixel(scale);
    final lines = metrics.wrap(font, text, lineWidth);
    final lineHeight = metrics.font(font).lineHeight * perFontPixel;

    final fontSize = scale / MetricsTable.scaleUnit * 11.0;
    var y = top;

    for (final line in lines) {
      var pen = left;
      for (final codepoint in line.runes) {
        final advance = metrics.advanceOf(font, codepoint) * perFontPixel;
        if (!metrics.covers(font, codepoint)) {
          _paintMissingGlyph(canvas, pen, y, advance, lineHeight, color);
          pen += advance;
          continue;
        }
        final painter = TextPainter(
          text: TextSpan(
            text: String.fromCharCode(codepoint),
            style: TextStyle(
              fontFamily: canvasFontFamily,
              fontSize: fontSize,
              height: 1.0,
              fontWeight: font.contains('semibold') ? FontWeight.w600 : FontWeight.w400,
              color: color,
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout();
        painter.paint(canvas, Offset(pen + (advance - painter.width) / 2, y));
        pen += advance;
      }
      y += lineHeight;
    }
  }

  void _paintMissingGlyph(
    Canvas canvas,
    double x,
    double y,
    double width,
    double height,
    Color color,
  ) {
    canvas.drawRect(
      Rect.fromLTWH(x, y, math.max(width - 1, 1), math.max(height - 1, 1)),
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = math.max(0.5, 1 / viewport.scale),
    );
  }

  void _paintSelection(Canvas canvas, Element element) {
    final rect = _rectOf(element);
    canvas.drawRect(
      rect,
      Paint()
        ..color = tokens.accent
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5 / viewport.scale,
    );
    if (element.id != handlesOn) return;

    final half = 4.0 / viewport.scale;
    final fill = Paint()..color = tokens.accent;
    final edge = Paint()
      ..color = tokens.canvasVoid
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1 / viewport.scale;
    for (final handle in ResizeHandle.values) {
      final grip = Rect.fromCenter(
        center: handle.anchorOn(rect),
        width: half * 2,
        height: half * 2,
      );
      canvas.drawRect(grip, fill);
      canvas.drawRect(grip, edge);
    }
  }

  void _paintGuides(Canvas canvas, Size size) {
    if (guides.isEmpty) return;
    final visible = viewport.visibleDesignRect(size);
    final paint = Paint()
      ..color = tokens.guide
      ..strokeWidth = 1 / viewport.scale;
    for (final guide in guides) {
      if (guide.vertical) {
        canvas.drawLine(Offset(guide.at, visible.top), Offset(guide.at, visible.bottom), paint);
      } else {
        canvas.drawLine(Offset(visible.left, guide.at), Offset(visible.right, guide.at), paint);
      }
    }
  }

  void _paintMarquee(Canvas canvas) {
    final rect = marquee;
    if (rect == null) return;
    canvas.drawRect(rect, Paint()..color = tokens.accentMuted);
    canvas.drawRect(
      rect,
      Paint()
        ..color = tokens.accent
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1 / viewport.scale,
    );
  }

  @override
  bool shouldRepaint(_PagePainter old) =>
      !identical(old.snapshot, snapshot) ||
      !setEquals(old.selection, selection) ||
      old.guides != guides ||
      old.viewport != viewport ||
      old.handlesOn != handlesOn ||
      old.hoveredId != hoveredId ||
      old.marquee != marquee ||
      old.showHitRegions != showHitRegions ||
      !mapEquals(old.live, live) ||
      !mapEquals(old.thumbnails, thumbnails);
}
