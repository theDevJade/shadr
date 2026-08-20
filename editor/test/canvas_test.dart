import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/canvas.dart';
import 'package:shadr_editor/model.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  Future<EditorModel> pumpCanvas(
    WidgetTester tester, {
    required List<Element> elements,
    Map<String, String> locked = const {},
    int? previewTick,
    Set<String> select = const {},
  }) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(
      elements: elements,
      locked: locked,
      previewTick: previewTick,
    ));

    await tester.pumpWidget(harness(
      model,
      SizedBox(
        width: 800,
        height: 600,
        child: PageCanvas(onSizeChanged: (_) {}),
      ),
    ));
    await tester.pump();

    model
      ..fit(const Size(800, 600))
      ..zoomAt(Offset.zero, 1 / model.viewport.scale)
      ..pan(-model.viewport.offset);
    for (final id in select) {
      model.select(id, additive: true);
    }
    await tester.pump();
    expect(model.viewport.scale, closeTo(1, 1e-9));
    expect(model.viewport.offset, const Offset(0, 0));
    return model;
  }

  testWidgets('dragging a corner grip resizes', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      select: {'a'},
    );

    await tester.dragFrom(const Offset(300, 200), const Offset(40, 20));
    await tester.pump();

    expect(model.activeHandle, isNull, reason: 'the gesture should have ended');
    await tester.pump(const Duration(milliseconds: 60));
    expect(model.selection, {'a'});
  });

  testWidgets('a locked element offers no grip, so a corner drag moves it', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      locked: {'a': "from component 'chip'"},
      select: {'a'},
    );

    await tester.dragFrom(const Offset(300, 200), const Offset(40, 20));
    await tester.pump();
    expect(model.activeHandle, isNull);
  });

  testWidgets('dragging empty space marquees, and selects what it covers', (tester) async {
    final model = await pumpCanvas(tester, elements: [
      block('inside', x: 100, y: 100, w: 50, h: 50),
      block('outside', x: 600, y: 400, w: 50, h: 50),
    ]);

    await tester.dragFrom(const Offset(50, 50), const Offset(300, 300));
    await tester.pump();

    expect(model.selection, {'inside'});
  });

  testWidgets('clicking an element selects it; clicking the void clears', (tester) async {
    final model = await pumpCanvas(tester, elements: [block('a', x: 100, y: 100, w: 200, h: 100)]);

    await tester.tapAt(const Offset(200, 150));
    await tester.pump();
    expect(model.selection, {'a'});

    await tester.tapAt(const Offset(700, 550));
    await tester.pump();
    expect(model.selection, isEmpty);
  });

  testWidgets('scrolling pans, and ctrl-scrolling zooms about the pointer', (tester) async {
    final model = await pumpCanvas(tester, elements: [block('a')]);
    final before = model.viewport;

    final pointer = TestPointer(1, PointerDeviceKind.mouse);
    pointer.hover(const Offset(400, 300));
    await tester.sendEventToBinding(pointer.scroll(const Offset(0, 120)));
    await tester.pump();

    expect(model.viewport.scale, before.scale, reason: 'a plain scroll changed the zoom');
    expect(model.viewport.offset, isNot(before.offset));
  });

  testWidgets('a grip is not offered while the playhead is down', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      previewTick: 5,
      select: {'a'},
    );

    await tester.dragFrom(const Offset(300, 200), const Offset(40, 20));
    await tester.pump(const Duration(milliseconds: 60));

    expect(model.activeHandle, isNull);
  });

  testWidgets('a drag started on one of several selected elements keeps them all', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [
        block('a', x: 100, y: 100, w: 200, h: 100),
        block('b', x: 500, y: 100, w: 100, h: 50),
      ],
      select: {'a', 'b'},
    );

    final gesture = await tester.startGesture(
      const Offset(150, 150),
      kind: PointerDeviceKind.mouse,
    );
    await tester.pump(const Duration(milliseconds: 250));
    await gesture.moveBy(const Offset(30, 0));
    await tester.pump();
    await gesture.up();
    await tester.pump();

    expect(
      model.selection,
      {'a', 'b'},
      reason: 'pressing to drag collapsed the selection to whichever element was grabbed',
    );
  });

  testWidgets('a dragged element follows the pointer before the server answers', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      select: {'a'},
    );
    model.setSnapping(false);

    final gesture = await tester.startGesture(
      const Offset(150, 150),
      kind: PointerDeviceKind.mouse,
    );
    await gesture.moveBy(const Offset(40, 20));
    await tester.pump();

    expect(model.boundsOf(model.elementById('a')!), const Rect.fromLTWH(140, 120, 200, 100));
    expect(model.snapshot!.elements.single.x, 100, reason: 'the snapshot should be untouched');

    await gesture.up();
    await tester.pump();
  });

  testWidgets('the drawn position gives way once the snapshot catches up', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a', x: 100, y: 100, w: 200, h: 100)]));
    await tester.pumpWidget(harness(
      model,
      SizedBox(width: 800, height: 600, child: PageCanvas(onSizeChanged: (_) {})),
    ));
    model
      ..select('a')
      ..setSnapping(false)
      ..dragBy(const Offset(40, 0), bypassSnapping: false)
      ..endGesture();

    expect(model.boundsOf(model.elementById('a')!).left, 140);

    transport.deliver(snapshotJson(elements: [block('a', x: 140, y: 100, w: 200, h: 100)]));
    expect(model.liveRects, isEmpty, reason: 'the preview outlived the snapshot that confirmed it');
    expect(model.boundsOf(model.elementById('a')!).left, 140);
  });

  testWidgets('the middle button pans without disturbing the selection', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      select: {'a'},
    );
    final before = model.viewport.offset;

    final gesture = await tester.startGesture(
      const Offset(150, 150),
      kind: PointerDeviceKind.mouse,
      buttons: kMiddleMouseButton,
    );
    await gesture.moveBy(const Offset(60, 40));
    await tester.pump();
    await gesture.up();
    await tester.pump();

    expect(model.viewport.offset, before + const Offset(60, 40));
    expect(model.selection, {'a'});
    expect(model.snapshot!.elements.single.x, 100, reason: 'panning moved the element');
  });

  testWidgets('a click takes the group, a double click reaches inside it', (tester) async {
    final model = await pumpCanvas(tester, elements: [
      block('card', x: 100, y: 100, w: 200, h: 100, sourcePath: '0'),
      block('label', x: 120, y: 120, w: 60, h: 20, layer: 1, sourcePath: '0.children.0'),
    ]);

    final pointer = TestPointer(1, PointerDeviceKind.mouse);
    Future<void> clickAt(Offset at, Duration when) async {
      await tester.sendEventToBinding(pointer.down(at, timeStamp: when));
      await tester.sendEventToBinding(pointer.up(timeStamp: when));
      await tester.pump();
    }

    await clickAt(const Offset(140, 130), const Duration(seconds: 1));
    expect(model.selection, {'card'}, reason: 'a click landed on the label inside the card');

    await clickAt(const Offset(140, 130), const Duration(milliseconds: 1100));
    expect(model.selection, {'label'}, reason: 'a double click did not drill in');

    await clickAt(const Offset(140, 130), const Duration(seconds: 5));
    expect(model.selection, {'label'}, reason: 'a slow click should hold what is already selected');
  });

  testWidgets('a marquee shows what it has swept up while it is still being dragged',
      (tester) async {
    final model = await pumpCanvas(tester, elements: [
      block('inside', x: 100, y: 100, w: 50, h: 50),
      block('outside', x: 600, y: 400, w: 50, h: 50),
    ]);

    final gesture = await tester.startGesture(
      const Offset(50, 50),
      kind: PointerDeviceKind.mouse,
    );
    await gesture.moveBy(const Offset(300, 300));
    await tester.pump();
    expect(model.selection, {'inside'}, reason: 'the marquee only selected on release');

    await gesture.moveBy(const Offset(300, 200));
    await tester.pump();
    expect(model.selection, {'inside', 'outside'});

    await gesture.up();
    await tester.pump();
    expect(model.selection, {'inside', 'outside'});
  });

  testWidgets('a cancelled drag does not leave a grip armed', (tester) async {
    final model = await pumpCanvas(
      tester,
      elements: [block('a', x: 100, y: 100, w: 200, h: 100)],
      select: {'a'},
    );

    final gesture = await tester.startGesture(
      const Offset(300, 200),
      kind: PointerDeviceKind.mouse,
    );
    await gesture.moveBy(const Offset(20, 20));
    await tester.pump();
    expect(model.activeHandle, ResizeHandle.bottomRight);

    await gesture.cancel();
    await tester.pump();
    expect(model.activeHandle, isNull);
    expect(model.isGesturing, isFalse);
  });
}
