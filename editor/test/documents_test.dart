import 'package:flutter/material.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/documents.dart';
import 'package:shadr_editor/properties.dart';

import 'support.dart';

void main() {
  testWidgets('the new document dialog creates a HUD page with a name and a size',
      (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver({'t': 'welcome', 'documents': <Map<String, dynamic>>[]});

    await tester.pumpWidget(harness(model, const DocumentBar()));
    await tester.tap(find.byTooltip('New page or component'));
    await tester.pumpAndSettle();

    expect(find.text('New document'), findsOneWidget);

    await tester.enterText(find.byType(TextFormField), 'bars');
    await tester.tap(find.text('HUD'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();

    final sent = transport.lastOfType('newDocument')!;
    expect(sent['name'], 'bars');
    expect(sent['kind'], 'PAGE');
    expect(sent['hud'], isTrue);
    expect(sent['width'], 1920.0);
  });

  testWidgets('a component has no HUD choice, because it has no screen', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver({'t': 'welcome', 'documents': <Map<String, dynamic>>[]});

    await tester.pumpWidget(harness(model, const DocumentBar()));
    await tester.tap(find.byTooltip('New page or component'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Component'));
    await tester.pumpAndSettle();
    expect(find.text('HUD'), findsNothing);

    await tester.enterText(find.byType(TextFormField), 'chip');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();

    final sent = transport.lastOfType('newDocument')!;
    expect(sent['kind'], 'COMPONENT');
    expect(sent['hud'], isFalse);
  });

  testWidgets('a name the server would refuse is caught before it is sent', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver({'t': 'welcome', 'documents': <Map<String, dynamic>>[]});

    await tester.pumpWidget(harness(model, const DocumentBar()));
    await tester.tap(find.byTooltip('New page or component'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();

    expect(find.text('required'), findsOneWidget);
    expect(transport.lastOfType('newDocument'), isNull);
  });

  testWidgets('the page section switches an open page between menu and HUD', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a')]));

    await tester.pumpWidget(harness(
      model,
      const SizedBox(width: 320, height: 700, child: PropertiesPanel()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('PAGE'), findsOneWidget);
    await tester.tap(find.text('HUD'));
    await tester.pump();

    expect(transport.lastOfType('patchScreen')?['changes'], {'hud': 'true'});
  });

  testWidgets('a component says its size belongs to whoever places it', (tester) async {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(name: 'chip', kind: 'COMPONENT', elements: [block('a')]));

    await tester.pumpWidget(harness(
      model,
      const SizedBox(width: 320, height: 700, child: PropertiesPanel()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('COMPONENT'), findsOneWidget);
    expect(find.text('HUD'), findsNothing);
  });
}
