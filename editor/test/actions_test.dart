import 'package:flutter/material.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/properties.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  Map<String, dynamic> withInteraction(Element e, Map<String, dynamic> interaction) {
    final json = snapshotJson(elements: [e]);
    (json['elements'] as List)[0]['interaction'] = interaction;
    return json;
  }

  test('interaction arrives from the wire, actions flattened to editable lines', () {
    final (:model, :transport) = connectedModel();
    transport.deliver(withInteraction(block('a'), {
      'interactive': true,
      'hoverText': 'Press',
      'onClick': [
        {'verb': 'sound', 'argument': 'shadr.click'},
        {'verb': 'close', 'argument': ''},
      ],
    }));

    final it = model.snapshot!.elements.single.interaction;
    expect(it.hoverText, 'Press');
    expect(it.onClick, ['sound shadr.click', 'close']);
    expect(it.actionable, isTrue);
  });

  test('an element with nothing bound is reported as inert', () {
    final (:model, :transport) = connectedModel();
    transport.deliver(withInteraction(block('a'), {'interactive': true}));

    expect(model.snapshot!.elements.single.interaction.actionable, isFalse);
  });

  testWidgets('the Interact tab edits actions and sends them as verb-argument lines',
      (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(withInteraction(block('a'), {'interactive': true}));
    model.select('a');

    await tester.pumpWidget(harness(
      model,
      const SizedBox(width: 320, height: 700, child: PropertiesPanel()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Design'), findsOneWidget);
    await tester.tap(find.text('Interact'));
    await tester.pumpAndSettle();

    expect(find.text('On click'.toUpperCase()), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, 'sound shadr.click\nclose');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();

    final patch = transport.lastOfType('patch');
    expect(patch?['changes']['interaction.onClick'], 'sound shadr.click\nclose');
  });
}
