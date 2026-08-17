import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/actions.dart';
import 'package:shadr_editor/fields.dart';
import 'package:shadr_editor/model.dart';

import 'support.dart';

void main() {
  Future<({EditorModel model, FakeTransport transport, TextEditingController field})> pump(
    WidgetTester tester, {
    String initial = 'hello',
  }) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a'), block('b')]));
    model.select('a');

    final registry = EditorActionRegistry(model, () => const Size(800, 600));
    final manager = EditorShortcutManager(shortcuts: editorShortcuts);
    final controller = TextEditingController(text: initial);
    addTearDown(() {
      registry.dispose();
      manager.dispose();
      controller.dispose();
    });

    await tester.pumpWidget(harness(
      model,
      Shortcuts.manager(
        manager: manager,
        child: Actions(
          actions: registry.map,
          child: Focus(
            autofocus: true,
            child: Center(
              child: SizedBox(width: 200, child: TextField(controller: controller)),
            ),
          ),
        ),
      ),
    ));
    await tester.pump();
    return (model: model, transport: transport, field: controller);
  }

  Future<void> focusField(WidgetTester tester) async {
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    expect(textEditingHasFocus(), isTrue, reason: 'the field should hold the keyboard');
  }

  testWidgets('backspace in a field edits the text, and does not delete the element',
      (tester) async {
    final (:model, :transport, :field) = await pump(tester);
    await focusField(tester);
    field.selection = const TextSelection.collapsed(offset: 5);

    await tester.sendKeyEvent(LogicalKeyboardKey.backspace);
    await tester.pump();

    expect(field.text, 'hell');
    expect(transport.lastOfType('delete'), isNull);
    expect(model.selection, {'a'});
  });

  testWidgets('delete in a field edits the text, and does not delete the element',
      (tester) async {
    final (:model, :transport, :field) = await pump(tester);
    await focusField(tester);
    field.selection = const TextSelection.collapsed(offset: 0);

    await tester.sendKeyEvent(LogicalKeyboardKey.delete);
    await tester.pump();

    expect(field.text, 'ello');
    expect(transport.lastOfType('delete'), isNull);
  });

  testWidgets('the arrows move the caret rather than nudging the element', (tester) async {
    final (:model, :transport, :field) = await pump(tester);
    await focusField(tester);
    field.selection = const TextSelection.collapsed(offset: 5);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.pump();

    expect(field.selection.baseOffset, 4);
    expect(transport.lastOfType('patchAll'), isNull);
  });

  testWidgets('a bare letter shortcut does not fire while typing', (tester) async {
    final (:model, :transport, :field) = await pump(tester, initial: '');
    await focusField(tester);
    model.zoomTo(3, const Size(800, 600));
    final before = model.viewport;

    await tester.sendKeyEvent(LogicalKeyboardKey.keyF, character: 'f');
    await tester.pump();

    expect(model.viewport, before, reason: 'typing an f should not have zoomed to fit');
  });

  testWidgets('escape leaves the field instead of clearing the selection', (tester) async {
    final (:model, :transport, :field) = await pump(tester);
    await focusField(tester);

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();

    expect(textEditingHasFocus(), isFalse);
    expect(model.selection, {'a'}, reason: 'the first escape only leaves the field');
  });

  testWidgets('the same keys still reach the canvas when no field has focus', (tester) async {
    final (:model, :transport, :field) = await pump(tester);
    expect(textEditingHasFocus(), isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.delete);
    await tester.pump();

    expect(transport.lastOfType('delete')?['elementIds'], ['a']);
  });

  testWidgets('a value field commits on escape rather than stranding the edit',
      (tester) async {
    final (:model, :transport) = connectedModel();
    final committed = <String>[];
    final manager = EditorShortcutManager(shortcuts: editorShortcuts);
    addTearDown(manager.dispose);

    await tester.pumpWidget(harness(
      model,
      Shortcuts.manager(
        manager: manager,
        child: Center(
          child: SizedBox(
            width: 200,
            child: ValueField(value: '10', onCommit: committed.add),
          ),
        ),
      ),
    ));

    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '42');
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();

    expect(committed, ['42']);
  });
}
