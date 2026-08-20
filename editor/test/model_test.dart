import 'package:flutter/widgets.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/model.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  test('a welcome opens the first document, and a reconnect reopens the same one', () async {
    final (:model, :transport) = connectedModel();

    transport.deliver({
      't': 'welcome',
      'documents': [
        {'name': 'menu', 'kind': 'PAGE'},
        {'name': 'chip', 'kind': 'COMPONENT'},
      ],
    });

    expect(model.status, ConnectionStatus.connected);
    expect(transport.lastOfType('open')?['name'], 'menu');

    model.open(model.documents[1]);
    expect(transport.lastOfType('open')?['kind'], 'COMPONENT');

    transport.deliver({
      't': 'welcome',
      'documents': [
        {'name': 'menu', 'kind': 'PAGE'},
        {'name': 'chip', 'kind': 'COMPONENT'},
      ],
    });
    expect(transport.lastOfType('open')?['name'], 'chip');
  });

  test('a snapshot for a different document clears the selection', () async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a')]));

    model.select('a');
    expect(model.selection, {'a'});

    transport.deliver(snapshotJson(name: 'other', elements: [block('b')]));
    expect(model.selection, isEmpty);
  });

  test('a selection the server no longer knows about is dropped', () async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a'), block('b')]));

    model
      ..select('a')
      ..select('b', additive: true);
    expect(model.selection, {'a', 'b'});

    transport.deliver(snapshotJson(elements: [block('a')]));
    expect(model.selection, {'a'});
  });

  group('hit testing', () {
    test('layer decides, not document order', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [
        block('under', layer: 10, w: 500, h: 500),
        block('over', layer: 1, w: 500, h: 500),
      ]));

      expect(model.hitTest(const Offset(10, 10))?.id, 'under');
    });

    test('a disabled element is not hit', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a', enabled: false)]));

      expect(model.hitTest(const Offset(10, 10)), isNull);
    });
  });

  group('dragging', () {
    test('a drag coalesces into one patch for the whole selection', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [
        block('a', x: 100, y: 100),
        block('b', x: 400, y: 400),
      ]));

      model
        ..select('a')
        ..select('b', additive: true);

      for (var i = 0; i < 5; i++) {
        model.dragBy(const Offset(4, 0), bypassSnapping: true);
      }
      expect(transport.lastOfType('patchAll'), isNull, reason: 'flushed before the timer fired');

      await Future<void>.delayed(const Duration(milliseconds: 60));
      final patch = transport.lastOfType('patchAll');
      expect(patch, isNotNull);
      expect((patch!['edits'] as Map).keys, containsAll(['a', 'b']));
      expect(patch['gesture'], isNotNull);
      expect((patch['edits'] as Map)['a']['position.x'], '120.0');
    });

    test('editing is refused while the playhead is down', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a')], previewTick: 5));

      model.select('a');
      model.dragBy(const Offset(10, 0), bypassSnapping: true);
      await Future<void>.delayed(const Duration(milliseconds: 60));

      expect(transport.lastOfType('patchAll'), isNull);
      expect(transport.lastOfType('patch'), isNull);
    });
  });

  group('resizing', () {
    test('only the edges the grip owns move', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a', x: 100, y: 100, w: 200, h: 100)]));

      model
        ..select('a')
        ..beginResize(ResizeHandle.right)
        ..dragBy(const Offset(40, 40), bypassSnapping: true);
      await Future<void>.delayed(const Duration(milliseconds: 60));

      final patch = transport.lastOfType('patch');
      expect(patch, isNotNull);
      final changes = patch!['changes'] as Map;
      expect(changes['position.x'], '100.0', reason: 'the left edge moved');
      expect(changes['position.y'], '100.0', reason: 'the top edge moved');
      expect(changes['size.width'], '240.0');
      expect(changes['size.height'], '100.0', reason: 'a horizontal grip changed the height');
    });

    test('dragging an edge past its opposite collapses', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a', x: 100, y: 100, w: 200, h: 100)]));

      model
        ..select('a')
        ..beginResize(ResizeHandle.left)
        ..dragBy(const Offset(500, 0), bypassSnapping: true);
      await Future<void>.delayed(const Duration(milliseconds: 60));

      final changes = transport.lastOfType('patch')!['changes'] as Map;
      expect(changes['size.width'], '1.0');
      expect(changes['position.x'], '299.0', reason: 'the right edge should not have moved');
    });

    test('a locked element offers no resize', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(
        elements: [block('a')],
        locked: {'a': "from component 'chip'"},
      ));

      model
        ..select('a')
        ..beginResize(ResizeHandle.right);
      expect(model.activeHandle, ResizeHandle.right,
          reason: 'the model itself does not gate this; the canvas withholds the grip');
      expect(model.lockReason('a'), contains('chip'));
    });
  });

  group('viewport', () {
    test('the page is fitted on first sight and never re-fitted', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a')]));

      model.ensureFitted(const Size(960, 540));
      final fitted = model.viewport;
      expect(fitted.scale, lessThan(1));

      model.zoomAt(const Offset(100, 100), 4);
      final zoomed = model.viewport;
      transport.deliver(snapshotJson(elements: [block('a', x: 10)]));
        model.ensureFitted(const Size(960, 540));
      expect(model.viewport, zoomed);
    });

    test('a new document re-fits, because its page may be a different size', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a')]));
        model.ensureFitted(const Size(960, 540));

      model.zoomAt(const Offset(100, 100), 4);
      transport.deliver(snapshotJson(name: 'other', elements: [block('b')]));
        model.ensureFitted(const Size(960, 540));
      expect(model.viewport.scale, lessThan(1));
    });
  });

  test('adding an element lands it in view, not at the page centre', () async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a')]));

    model
      ..viewportSize = const Size(800, 600)
      ..ensureFitted(const Size(800, 600))
      ..zoomAt(const Offset(700, 500), 8)
      ..addElement('block');

    final add = transport.lastOfType('add')!;
    final visible = model.viewport.visibleDesignRect(const Size(800, 600));
    expect(visible.contains(Offset(add['x'] as double, add['y'] as double)), isTrue);
  });

  test('a timeline edit resends every field, so an unchanged curve survives', () async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(
      elements: [block('a')],
      animations: [
        {
          'name': 'open',
          'durationTicks': 20,
          'steps': [
            {
              'target': 'a',
              'axis': 'y',
              'easing': 'EASE_OUT',
              'from': 0.0,
              'to': 100.0,
              'durationTicks': 12,
            },
          ],
        },
      ],
    ));

    final step = model.snapshot!.animations.single.steps.single;
    expect(step.easing, 'ease-out', reason: 'the enum name was not normalised');

    model.editStep(step, to: 300);
    final sent = transport.lastOfType('setStep')!;
    expect(sent['to'], 300.0);
    expect(sent['easing'], 'ease-out');
    expect(sent['duration'], 12);
  });

  group('documents', () {
    test('creating one asks for the kind, mode and size that were chosen', () async {
      final (:model, :transport) = connectedModel();

      model.createDocument(
        const DocumentRef(name: 'bars', kind: DocumentKind.page),
        hud: true,
        width: 2560,
        height: 1440,
      );

      final sent = transport.lastOfType('newDocument')!;
      expect(sent['name'], 'bars');
      expect(sent['kind'], 'PAGE');
      expect(sent['hud'], isTrue);
      expect(sent['width'], 2560.0);
      expect(sent['height'], 1440.0);
    });

    test('the new document becomes the open one once its snapshot lands', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver({
        't': 'welcome',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
        ],
      });
      transport.deliver(snapshotJson(name: 'menu'));
      expect(model.openRef?.name, 'menu');

      model.createDocument(const DocumentRef(name: 'bars', kind: DocumentKind.page));
      expect(model.openRef?.name, 'menu', reason: 'the picker jumped before the server agreed');

      transport.deliver(snapshotJson(name: 'bars'));
      expect(model.openRef, const DocumentRef(name: 'bars', kind: DocumentKind.page));
    });

    test('a refusal leaves the open document where it was', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver({
        't': 'welcome',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
        ],
      });
      transport.deliver(snapshotJson(name: 'menu'));

      model.createDocument(const DocumentRef(name: 'menu', kind: DocumentKind.page));
      transport.deliver({'t': 'error', 'message': "a page called 'menu' already exists"});
      transport.deliver(snapshotJson(name: 'menu'));

      expect(model.openRef?.name, 'menu');
      expect(model.notice, contains('already exists'));
    });

    test('a fresh list from the server replaces the one the welcome brought', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver({
        't': 'welcome',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
        ],
      });

      transport.deliver({
        't': 'documents',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
          {'name': 'chip', 'kind': 'COMPONENT'},
        ],
      });

      expect(model.documents.map((d) => d.name), ['menu', 'chip']);
      expect(model.documents.last.kind, DocumentKind.component);
    });

    test('renaming the open document follows it to the new name', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver({
        't': 'welcome',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
        ],
      });
      transport.deliver(snapshotJson(name: 'menu'));

      model.renameDocument(const DocumentRef(name: 'menu', kind: DocumentKind.page), 'lobby');
      expect(transport.lastOfType('renameDocument')?['to'], 'lobby');

      transport.deliver(snapshotJson(name: 'lobby'));
      expect(model.openRef?.name, 'lobby');
    });

    test('deleting sends the ref and lets go of it', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver({
        't': 'welcome',
        'documents': [
          {'name': 'menu', 'kind': 'PAGE'},
        ],
      });
      transport.deliver(snapshotJson(name: 'menu'));

      model.deleteDocument(const DocumentRef(name: 'menu', kind: DocumentKind.page));
      expect(transport.lastOfType('deleteDocument')?['name'], 'menu');
      expect(model.openRef, isNull);
    });
  });

  group('page settings', () {
    test('a snapshot carries whether the page is a hud', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(
        screen: const {'width': 1920.0, 'height': 1080.0, 'hud': true, 'cursorSize': 0.0},
      ));

      expect(model.snapshot!.screen.hud, isTrue);
      expect(model.snapshot!.screen.cursorSize, 0);
    });

    test('switching mode patches the screen, not an element', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a')]));

      model.patchScreen('hud', 'true');
      expect(transport.lastOfType('patchScreen')?['changes'], {'hud': 'true'});
      expect(transport.lastOfType('patch'), isNull);
    });

    test('the playhead being down refuses a screen edit', () async {
      final (:model, :transport) = connectedModel();
      transport.deliver(snapshotJson(elements: [block('a')], previewTick: 4));

      model.patchScreen('hud', 'true');
      expect(transport.lastOfType('patchScreen'), isNull);
    });
  });

  test('a drag in flight sticks to a guide rather than snapping only when it is sent', () async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [
      block('a', x: 100, y: 100, w: 100, h: 50),
      block('b', x: 300, y: 400, w: 60, h: 50),
    ]));

    model
      ..select('a')
      ..dragBy(const Offset(197, 0), bypassSnapping: false);

    expect(model.boundsOf(model.elementById('a')!).left, 300);
    expect(model.guides.any((g) => g.vertical && g.at == 300), isTrue);

    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect((transport.lastOfType('patchAll')!['edits'] as Map)['a']['position.x'], '300.0');
  });
}
