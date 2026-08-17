import 'package:flutter/material.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/fields.dart';
import 'package:shadr_editor/model.dart';
import 'package:shadr_editor/properties.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  Map<String, dynamic> withInteraction(
    Element e,
    Map<String, dynamic> interaction,
  ) {
    final json = snapshotJson(elements: [e]);
    (json['elements'] as List)[0]['interaction'] = interaction;
    return json;
  }

  Map<String, dynamic> effectsMessage() => {
        't': 'effects',
        'effects': [
          {
            'id': 'lift',
            'name': 'lift',
            'moveY': -4.0,
            'scaleXPercent': 4.0,
            'scaleYPercent': 4.0,
            'durationMs': 250,
          },
          {'id': 'press', 'name': 'press', 'scaleXPercent': -6.0, 'durationMs': 90},
          {'id': 'glow', 'name': 'glow', 'opacityDelta': 40, 'durationMs': 200},
        ],
      };

  test('the effect list arrives from the wire', () {
    final (:model, :transport) = connectedModel();
    transport.deliver(effectsMessage());

    expect(model.effects.map((e) => e.id), ['lift', 'press', 'glow']);
    expect(model.effects.first.summary, contains('250ms'));
    expect(model.effects.first.summary, contains('scales 4%'));
  });

  test('an effect with no visible change still summarises', () {
    final (:model, :transport) = connectedModel();
    transport.deliver({
      't': 'effects',
      'effects': [
        {'id': 'still', 'name': 'still', 'durationMs': 120},
      ],
    });

    expect(model.effects.single.summary, 'no visible change over 120ms');
  });

  Future<void> openInteract(WidgetTester tester, EditorModel model) async {
    tester.view.physicalSize = const Size(420, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(harness(
      model,
      const SizedBox(width: 340, height: 900, child: PropertiesPanel()),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Interact'));
    await tester.pumpAndSettle();
  }

  Finder hoverFxInput() => find.descendant(
        of: find.byType(SuggestField).first,
        matching: find.byType(TextField),
      );

  Future<void> focusHoverFx(WidgetTester tester) async {
    await tester.ensureVisible(find.byType(SuggestField).first);
    await tester.pumpAndSettle();
    await tester.tap(hoverFxInput());
    await tester.pumpAndSettle();
  }

  testWidgets('the hover effect field describes the effect that is set',
      (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(effectsMessage());
    transport.deliver(withInteraction(block('a'), {
      'interactive': true,
      'hoverEffect': 'lift',
    }));
    model.select('a');

    await openInteract(tester, model);

    expect(find.byType(SuggestField), findsNWidgets(2));
    expect(find.textContaining('moves 0, -4'), findsOneWidget);
  });

  testWidgets('an effect id that does not exist is called out', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(effectsMessage());
    transport.deliver(withInteraction(block('a'), {
      'interactive': true,
      'hoverEffect': 'liftt',
    }));
    model.select('a');

    await openInteract(tester, model);

    expect(find.textContaining('no effect called "liftt"'), findsOneWidget);
  });

  testWidgets('focusing the field offers every effect, typing narrows it',
      (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(effectsMessage());
    transport.deliver(withInteraction(block('a'), {'interactive': true}));
    model.select('a');

    await openInteract(tester, model);
    await focusHoverFx(tester);

    for (final id in ['lift', 'press', 'glow']) {
      expect(find.text(id), findsWidgets, reason: '$id was not offered');
    }

    await tester.enterText(hoverFxInput(), 'pr');
    await tester.pumpAndSettle();
    expect(find.text('press'), findsWidgets);
    expect(find.text('glow'), findsNothing);
  });

  testWidgets('picking a suggestion patches the element', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(effectsMessage());
    transport.deliver(withInteraction(block('a'), {'interactive': true}));
    model.select('a');

    await openInteract(tester, model);
    await focusHoverFx(tester);

    await tester.tap(find.text('glow').last);
    await tester.pumpAndSettle();

    final patch = transport.lastOfType('patch');
    expect(patch?['changes']['interaction.hoverEffect'], 'glow');
  });

  testWidgets('with no effects on disk the field says so', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(withInteraction(block('a'), {'interactive': true}));
    model.select('a');

    await openInteract(tester, model);

    expect(find.textContaining('no effects in effects/ yet'), findsWidgets);
  });
}
