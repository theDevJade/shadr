import 'dart:ui' show Rect;

import 'protocol.dart';

typedef Guide = ({bool vertical, double at});

class SnapResult {
  const SnapResult({required this.dx, required this.dy, required this.guides});

  final double dx;
  final double dy;

  final List<Guide> guides;
}

class Snapper {
  const Snapper({this.threshold = 6, this.grid = 1});

  final double threshold;

  final double grid;

  SnapResult snap({
    required List<Element> moving,
    required List<Element> others,
    required double dx,
    required double dy,
    required ScreenDef screen,
    bool enabled = true,
  }) =>
      snapRects(
        moving: moving.map((e) => e.bounds).toList(),
        others: others.map((e) => e.bounds).toList(),
        dx: dx,
        dy: dy,
        screen: screen,
        enabled: enabled,
      );

  SnapResult snapRects({
    required List<Rect> moving,
    required List<Rect> others,
    required double dx,
    required double dy,
    required ScreenDef screen,
    bool enabled = true,
  }) {
    if (!enabled || moving.isEmpty) {
      return SnapResult(dx: dx, dy: dy, guides: const []);
    }

    var left = double.infinity, right = -double.infinity;
    var top = double.infinity, bottom = -double.infinity;
    for (final rect in moving) {
      left = left < rect.left + dx ? left : rect.left + dx;
      top = top < rect.top + dy ? top : rect.top + dy;
      final r = rect.right + dx;
      final b = rect.bottom + dy;
      right = right > r ? right : r;
      bottom = bottom > b ? bottom : b;
    }

    final targetsX = <double>[0, screen.width / 2, screen.width];
    final targetsY = <double>[0, screen.height / 2, screen.height];
    for (final rect in others) {
      targetsX.addAll([rect.left, rect.center.dx, rect.right]);
      targetsY.addAll([rect.top, rect.center.dy, rect.bottom]);
    }

    final guides = <Guide>[];

    final adjustX = _closest([left, (left + right) / 2, right], targetsX);
    final adjustY = _closest([top, (top + bottom) / 2, bottom], targetsY);

    var resultX = dx;
    var resultY = dy;
    if (adjustX != null) {
      resultX = dx + adjustX.shift;
      guides.add((vertical: true, at: adjustX.target));
    } else {
      resultX = (dx / grid).roundToDouble() * grid;
    }
    if (adjustY != null) {
      resultY = dy + adjustY.shift;
      guides.add((vertical: false, at: adjustY.target));
    } else {
      resultY = (dy / grid).roundToDouble() * grid;
    }
    return SnapResult(dx: resultX, dy: resultY, guides: guides);
  }

  double? nearestTarget({
    required double value,
    required bool vertical,
    required List<Element> others,
    required ScreenDef screen,
    bool enabled = true,
  }) =>
      nearestEdge(
        value: value,
        vertical: vertical,
        others: others.map((e) => e.bounds).toList(),
        screen: screen,
        enabled: enabled,
      );

  double? nearestEdge({
    required double value,
    required bool vertical,
    required List<Rect> others,
    required ScreenDef screen,
    bool enabled = true,
  }) {
    if (!enabled) return null;
    final targets = vertical
        ? <double>[0, screen.width / 2, screen.width]
        : <double>[0, screen.height / 2, screen.height];
    for (final rect in others) {
      if (vertical) {
        targets.addAll([rect.left, rect.center.dx, rect.right]);
      } else {
        targets.addAll([rect.top, rect.center.dy, rect.bottom]);
      }
    }
    return _closest([value], targets)?.target;
  }

  ({double shift, double target})? _closest(List<double> edges, List<double> targets) {
    double? bestShift;
    double? bestTarget;
    var bestDistance = threshold;

    for (final edge in edges) {
      for (final target in targets) {
        final distance = (target - edge).abs();
        if (distance < bestDistance) {
          bestDistance = distance;
          bestShift = target - edge;
          bestTarget = target;
        }
      }
    }
    if (bestShift == null || bestTarget == null) return null;
    return (shift: bestShift, target: bestTarget);
  }
}
