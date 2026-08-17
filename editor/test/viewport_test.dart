import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/viewport.dart';

void main() {
  test('design and screen space round-trip', () {
    const viewport = CanvasViewport(scale: 2.5, offset: Offset(37, -14));
    const point = Offset(123, 456);
    final back = viewport.toDesign(viewport.toScreen(point));
    expect(back.dx, closeTo(point.dx, 1e-9));
    expect(back.dy, closeTo(point.dy, 1e-9));
  });

  test('zooming holds the focal point still', () {
    const viewport = CanvasViewport(scale: 1, offset: Offset(20, 30));
    const focal = Offset(400, 260);
    final before = viewport.toDesign(focal);

    final zoomed = viewport.zoomedAt(focal, 3.7);
    final after = zoomed.toDesign(focal);

    expect(after.dx, closeTo(before.dx, 1e-9));
    expect(after.dy, closeTo(before.dy, 1e-9));
    expect(zoomed.scale, closeTo(3.7, 1e-9));
  });

  test('zoom clamps', () {
    const viewport = CanvasViewport(scale: 1, offset: Offset.zero);
    expect(viewport.zoomedAt(Offset.zero, 1e6).scale, CanvasViewport.maxScale);
    expect(viewport.zoomedAt(Offset.zero, 1e-6).scale, CanvasViewport.minScale);
  });

  test('a zoom that would not move the scale returns the same viewport', () {
    const viewport = CanvasViewport(scale: CanvasViewport.maxScale, offset: Offset(5, 5));
    expect(viewport.zoomedAt(const Offset(10, 10), 2), viewport);
  });

  group('fit', () {
    test('centres the page and leaves a margin', () {
      final viewport = CanvasViewport.fit(const Size(1000, 1000), const Size(1920, 1080));
      expect(viewport.scale, closeTo(952 / 1920, 1e-9));

      final page = viewport.toScreenRect(const Rect.fromLTWH(0, 0, 1920, 1080));
      expect(page.center.dx, closeTo(500, 1e-6));
      expect(page.center.dy, closeTo(500, 1e-6));
    });

    test('the fitted page is strictly inside the viewport', () {
      final viewport = CanvasViewport.fit(const Size(800, 600), const Size(1920, 1080));
      final page = viewport.toScreenRect(const Rect.fromLTWH(0, 0, 1920, 1080));
      expect(page.left, greaterThan(0));
      expect(page.right, lessThan(800));
    });

    test('a degenerate size does not produce an infinite or zero scale', () {
      final viewport = CanvasViewport.fit(Size.zero, const Size(1920, 1080));
      expect(viewport.scale, 1);
      expect(viewport.scale.isFinite, isTrue);
    });
  });

  test('the visible rect is what the viewport actually shows', () {
    const viewport = CanvasViewport(scale: 2, offset: Offset(-100, -50));
    final visible = viewport.visibleDesignRect(const Size(400, 200));
    expect(visible.left, closeTo(50, 1e-9));
    expect(visible.top, closeTo(25, 1e-9));
    expect(visible.width, closeTo(200, 1e-9));
    expect(visible.height, closeTo(100, 1e-9));
  });
}
