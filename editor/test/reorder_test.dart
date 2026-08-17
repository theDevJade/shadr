import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/model.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  ({EditorModel model, FakeTransport transport}) page(List<Element> elements) {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: elements));
    return (model: model, transport: transport);
  }

  group('children follow their parent', () {
    test('a drag carries everything nested inside the selection', () {
      final (:model, :transport) = page([
        block('card', sourcePath: '1', x: 0, y: 0),
        block('title', sourcePath: '1.children.0', x: 10, y: 10),
        block('body', sourcePath: '1.children.1', x: 10, y: 40),
        block('cell', sourcePath: '1.grid/0', x: 10, y: 70),
        block('outside', sourcePath: '2', x: 500, y: 0),
      ]);
      model.select('card');

      expect(model.movingSelection, {'card', 'title', 'body', 'cell'});

      model
        ..setSnapping(false)
        ..dragBy(const Offset(30, 20), bypassSnapping: true)
        ..endGesture();

      final edits = transport.lastOfType('patchAll')!['edits'] as Map<String, dynamic>;
      expect(edits.keys.toSet(), {'card', 'title', 'body', 'cell'});
      expect(edits['title']['position.x'], '40');
      expect(edits['outside'], isNull);
    });

    test('a nested selection does not drag its parent', () {
      final (:model, :transport) = page([
        block('card', sourcePath: '1'),
        block('title', sourcePath: '1.children.0'),
      ]);
      model.select('title');

      expect(model.movingSelection, {'title'});
    });

    test('a root element with no source path moves alone', () {
      final (:model, :transport) = page([block('a'), block('b')]);
      model.select('a');

      expect(model.movingSelection, {'a'});
    });
  });

  group('reordering', () {
    test('bringing forward swaps layers with the next sibling up', () {
      final (:model, :transport) = page([
        block('low', sourcePath: '0', layer: 1),
        block('mid', sourcePath: '1', layer: 2),
        block('high', sourcePath: '2', layer: 3),
      ]);
      model.select('mid');

      model.bringForward();

      final edits = transport.lastOfType('patchAll')!['edits'] as Map<String, dynamic>;
      expect(edits['mid']['layer'], '3.0');
      expect(edits['high']['layer'], '2.0');
    });

    test('sending backward swaps the other way', () {
      final (:model, :transport) = page([
        block('low', sourcePath: '0', layer: 1),
        block('mid', sourcePath: '1', layer: 2),
      ]);
      model.select('mid');

      model.sendBackward();

      final edits = transport.lastOfType('patchAll')!['edits'] as Map<String, dynamic>;
      expect(edits['mid']['layer'], '1.0');
      expect(edits['low']['layer'], '2.0');
    });

    test('the topmost element cannot go further forward', () {
      final (:model, :transport) = page([
        block('low', sourcePath: '0', layer: 1),
        block('high', sourcePath: '1', layer: 2),
      ]);
      model.select('high');

      model.bringForward();

      expect(transport.lastOfType('patchAll'), isNull);
    });

    test('to front and to back clear the whole stack', () {
      final (:model, :transport) = page([
        block('a', sourcePath: '0', layer: 1),
        block('b', sourcePath: '1', layer: 2),
        block('c', sourcePath: '2', layer: 3),
      ]);
      model.select('a');

      model.bringToFront();
      expect(transport.lastOfType('patch')!['changes']['layer'], '4.0');

      model.sendToBack();
      expect(transport.lastOfType('patch')!['changes']['layer'], '0.0');
    });

    test('reordering only considers siblings at the same depth', () {
      final (:model, :transport) = page([
        block('card', sourcePath: '1', layer: 1),
        block('title', sourcePath: '1.children.0', layer: 50),
        block('other', sourcePath: '2', layer: 2),
      ]);
      model.select('card');

      model.bringForward();

      final edits = transport.lastOfType('patchAll')!['edits'] as Map<String, dynamic>;
      expect(edits.keys.toSet(), {'card', 'other'},
          reason: 'a nested child is not a sibling and should not be swapped with');
    });

    test('a blur panel is left out of the stack, its layer is reserved', () {
      final (:model, :transport) = page([
        block('panel', type: 'BLUR', sourcePath: '0', layer: 14),
        block('a', sourcePath: '1', layer: 1),
      ]);
      model.select('panel');

      model.bringForward();
      model.bringToFront();

      expect(transport.lastOfType('patchAll'), isNull);
      expect(transport.lastOfType('patch'), isNull);
    });
  });
}
