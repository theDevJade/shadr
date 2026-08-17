import 'dart:math' as math;

import 'package:flutter/widgets.dart';

@immutable
class CanvasViewport {
  const CanvasViewport({required this.scale, required this.offset});

  final double scale;

  final Offset offset;

  static const double minScale = 0.05;
  static const double maxScale = 16;

  Offset toScreen(Offset design) => Offset(design.dx * scale, design.dy * scale) + offset;

  Offset toDesign(Offset screen) {
    final local = screen - offset;
    return Offset(local.dx / scale, local.dy / scale);
  }

  Rect toScreenRect(Rect design) => Rect.fromLTWH(
        design.left * scale + offset.dx,
        design.top * scale + offset.dy,
        design.width * scale,
        design.height * scale,
      );

  Rect visibleDesignRect(Size size) =>
      Rect.fromPoints(toDesign(Offset.zero), toDesign(Offset(size.width, size.height)));

  CanvasViewport zoomedAt(Offset focal, double factor) {
    final next = (scale * factor).clamp(minScale, maxScale);
    if (next == scale) return this;
    final design = toDesign(focal);
    return CanvasViewport(
      scale: next,
      offset: focal - Offset(design.dx * next, design.dy * next),
    );
  }

  CanvasViewport panned(Offset delta) =>
      CanvasViewport(scale: scale, offset: offset + delta);

  CanvasViewport zoomedTo(double target, Size size) =>
      zoomedAt(Offset(size.width / 2, size.height / 2), target / scale);

  static CanvasViewport fit(Size size, Size design, {double margin = 24}) {
    if (design.width <= 0 || design.height <= 0 || size.width <= 0 || size.height <= 0) {
      return const CanvasViewport(scale: 1, offset: Offset.zero);
    }
    final usable = Size(
      math.max(1, size.width - margin * 2),
      math.max(1, size.height - margin * 2),
    );
    final scale = math.min(usable.width / design.width, usable.height / design.height)
        .clamp(minScale, maxScale);
    return CanvasViewport(
      scale: scale,
      offset: Offset(
        (size.width - design.width * scale) / 2,
        (size.height - design.height * scale) / 2,
      ),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is CanvasViewport && other.scale == scale && other.offset == offset;

  @override
  int get hashCode => Object.hash(scale, offset);

  @override
  String toString() => 'CanvasViewport(${(scale * 100).round()}%, $offset)';
}
