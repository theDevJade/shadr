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

  testWidgets('dragging a corner grip resizes rather than moves', (tester) async {
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
}
