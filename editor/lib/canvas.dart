import 'dart:math' as math;

import 'package:flutter/foundation.dart' show setEquals;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';

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

class _PageCanvasState extends State<PageCanvas> {
  Offset? _pressed;

  ResizeHandle? _hoveredHandle;
  String? _hoveredId;

  Rect? _marquee;

  bool _panning = false;
  Size _size = Size.zero;

  EditorModel get _model => EditorScope.read(context);

  bool get _additive =>
      HardwareKeyboard.instance.isShiftPressed || HardwareKeyboard.instance.isMetaPressed;

  bool get _wantsPan =>
      HardwareKeyboard.instance.logicalKeysPressed.contains(LogicalKeyboardKey.space);

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
    final rect = _model.viewport.toScreenRect(element.bounds);
    const grab = 7.0;
    for (final handle in ResizeHandle.values) {
      if ((handle.anchorOn(rect) - local).distance <= grab) return handle;
    }
    return null;
  }

  void _onPointerSignal(PointerSignalEvent event) {
    if (event is! PointerScrollEvent) return;
    final model = _model;
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

  void _onPanStart(DragStartDetails details) {
    final model = _model;
    final origin = _pressed ?? details.localPosition;

    if (_wantsPan) {
      _panning = true;
      return;
    }

    final handle = _handleAt(origin);
    if (handle != null) {
      model.beginResize(handle);
      return;
    }

    final hit = model.hitTest(model.viewport.toDesign(origin));
    if (hit == null) {
      final start = model.viewport.toDesign(origin);
      setState(() => _marquee = Rect.fromPoints(start, start));
      if (!_additive) model.clearSelection();
      return;
    }
    if (!model.selection.contains(hit.id)) model.select(hit.id, additive: _additive);
  }

  void _onPanUpdate(DragUpdateDetails details) {
    final model = _model;
    if (_panning) {
      model.pan(details.delta);
      return;
    }

    final marquee = _marquee;
    if (marquee != null) {
      final corner = model.viewport.toDesign(details.localPosition);
      setState(() => _marquee = Rect.fromPoints(marquee.topLeft, corner));
      return;
    }

    model.dragBy(
      details.delta / model.viewport.scale,
      bypassSnapping: HardwareKeyboard.instance.isAltPressed,
    );
  }

  void _onPanEnd() {
    final model = _model;
    final marquee = _marquee;
    if (marquee != null) {
      for (final element in model.snapshot?.elements ?? const <Element>[]) {
        if (element.enabled && marquee.overlaps(element.bounds)) {
          model.select(element.id, additive: true);
        }
      }
      setState(() => _marquee = null);
    }
    _panning = false;
    model.endGesture();
  }

  void _onHover(Offset local) {
    final model = _model;
    final handle = _handleAt(local);
    final hit = handle == null ? model.hitTest(model.viewport.toDesign(local)) : null;
    if (handle != _hoveredHandle || hit?.id != _hoveredId) {
      setState(() {
        _hoveredHandle = handle;
        _hoveredId = hit?.id;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final snapshot = model.snapshot;
    if (snapshot == null) return const SizedBox.expand();

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
          cursor: _panning || _wantsPan
              ? SystemMouseCursors.grab
              : _hoveredHandle?.cursor ??
                  (_hoveredId != null ? SystemMouseCursors.click : MouseCursor.defer),
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
            onPointerDown: (event) => _pressed = event.localPosition,
            onPointerSignal: _onPointerSignal,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTapDown: (details) {
                if (_handleAt(details.localPosition) != null) return;
                final hit = model.hitTest(model.viewport.toDesign(details.localPosition));
                model.select(hit?.id, additive: _additive);
              },
              onPanStart: _onPanStart,
              onPanUpdate: _onPanUpdate,
              onPanEnd: (_) => _onPanEnd(),
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
                    ),
                    size: Size.infinite,
                  ),
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
  });

  final PageSnapshot snapshot;
  final Set<String> selection;
  final List<Guide> guides;
  final CanvasViewport viewport;
  final EditorTokens tokens;
  final String? handlesOn;
  final String? hoveredId;
  final Rect? marquee;

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
      if (!element.bounds.overlaps(visible)) continue;
      _paintElement(canvas, element);
    }

    if (hoveredId != null && !selection.contains(hoveredId)) {
      final hovered = snapshot.elements.where((e) => e.id == hoveredId).firstOrNull;
      if (hovered != null) {
        canvas.drawRect(
          hovered.bounds,
          Paint()
            ..color = tokens.accent.withValues(alpha: 0.5)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1 / viewport.scale,
        );
      }
    }

    for (final element in snapshot.elements) {
      if (selection.contains(element.id)) _paintSelection(canvas, element);
    }
    _paintGuides(canvas, size);
    _paintMarquee(canvas);
    canvas.restore();
    canvas.restore();
  }

  void _paintElement(Canvas canvas, Element element) {
    final rect = element.bounds;
    final opacity = element.opacity / 255.0;

    canvas.save();
    if (element.rotationDeg != 0) {
      canvas.translate(rect.center.dx, rect.center.dy);
      canvas.rotate(element.rotationDeg * math.pi / 180);
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
      _paintText(canvas, element, opacity);
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
      case 'PROGRESS':
        final radius = math.min(rect.width, rect.height) / 2;
        canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)), paint);
      case 'BLUR':
        final rounded = RRect.fromRectAndRadius(rect, const Radius.circular(6));
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

  void _paintText(Canvas canvas, Element element, double opacity) {
    const emAtUnitScale = 11.0;
    const scaleUnit = 64.0;
    const baselineFactor = 0.1514;

    final painter = TextPainter(
      text: TextSpan(
        text: element.text,
        style: TextStyle(
          fontFamily: canvasFontFamily,
          fontSize: element.height / scaleUnit * emAtUnitScale,
          height: 1.2,
          fontWeight: element.font.contains('semibold') ? FontWeight.w600 : FontWeight.w400,
          color: Color(0xFF000000 | element.color).withValues(alpha: opacity),
        ),
      ),
      textDirection: TextDirection.ltr,
      textAlign: switch (element.textAlign) {
        'LEFT' => TextAlign.left,
        'RIGHT' => TextAlign.right,
        _ => TextAlign.center,
      },
    )..layout(maxWidth: snapshot.screen.width);

    final anchorY = element.y + baselineFactor * element.height;
    final dx = switch (element.textAlign) {
      'RIGHT' => element.x - painter.width,
      'CENTER' => element.x - painter.width / 2,
      _ => element.x,
    };
    painter.paint(canvas, Offset(dx, anchorY - painter.height / 2));
  }

  void _paintSelection(Canvas canvas, Element element) {
    final rect = element.bounds;
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
      old.marquee != marquee;
}
