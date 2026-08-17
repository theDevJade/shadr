import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/protocol.dart';
import 'package:shadr_editor/snapping.dart';

Element block(String id, double x, double y, {double w = 100, double h = 50}) => Element(
      id: id,
      type: 'BLOCK',
      x: x,
      y: y,
      width: w,
      height: h,
      layer: 0,
      color: 0xFFFFFF,
      opacity: 255,
      text: '',
      font: 'shadr',
      textAlign: 'CENTER',
      enabled: true,
      rotationDeg: 0,
    );

const screen = ScreenDef(width: 1920, height: 1080);

void main() {
  const snapper = Snapper(threshold: 6);

  test('a near edge pulls the drag onto it and reports a guide', () {
    final moving = [block('a', 100, 100)];
    final others = [block('b', 300, 400, w: 60)];

    final result = snapper.snap(
      moving: moving,
      others: others,
      dx: 197,
      dy: 0,
      screen: screen,
    );

    expect(100 + result.dx, 300);
    expect(result.guides.any((g) => g.vertical && g.at == 300), isTrue);
  });

  test('nothing near falls back to the pixel grid', () {
    final result = snapper.snap(
      moving: [block('a', 100, 100)],
      others: [block('b', 900, 900)],
      dx: 12.4,
      dy: -7.6,
      screen: screen,
    );

    expect(result.dx, 12);
    expect(result.dy, -8);
    expect(result.guides, isEmpty);
  });

  test('the screen centre attracts, which is what centring a dialog relies on', () {
    final result = snapper.snap(
      moving: [block('a', 0, 0)],
      others: const [],
      dx: 912,
      dy: 0,
      screen: screen,
    );
    expect(0 + result.dx + 50, 960);
  });

  test('disabling snapping leaves the delta exactly as dragged', () {
    final result = snapper.snap(
      moving: [block('a', 100, 100)],
      others: [block('b', 300, 400)],
      dx: 197.4,
      dy: 3.2,
      screen: screen,
      enabled: false,
    );
    expect(result.dx, 197.4);
    expect(result.dy, 3.2);
    expect(result.guides, isEmpty);
  });

  group('edge snapping, for resize handles', () {
    test('an edge within reach lands exactly on its target', () {
      final target = snapper.nearestTarget(
        value: 297,
        vertical: true,
        others: [block('b', 300, 0, w: 60)],
        screen: screen,
      );
      expect(target, 300);
    });

    test('nothing within reach reports no target, so the caller keeps the raw value', () {
      final target = snapper.nearestTarget(
        value: 517,
        vertical: true,
        others: [block('b', 300, 0, w: 60)],
        screen: screen,
      );
      expect(target, isNull);
    });

    test('the axis is respected, and a vertical edge never snaps to a horizontal one', () {
      final target = snapper.nearestTarget(
        value: 302,
        vertical: true,
        others: [block('b', 900, 300, w: 60)],
        screen: screen,
      );
      expect(target, isNull);
    });

    test('disabled snapping reports nothing at all', () {
      final target = snapper.nearestTarget(
        value: 300,
        vertical: true,
        others: [block('b', 300, 0)],
        screen: screen,
        enabled: false,
      );
      expect(target, isNull);
    });
  });

  test('whichever edge is nearest wins, not always the leading one', () {
    final result = snapper.snap(
      moving: [block('a', 500, 0)],
      others: [block('b', 200, 0, w: 100)],
      dx: -202,
      dy: 0,
      screen: screen,
    );
    expect(500 + result.dx, 300);
  });
}
