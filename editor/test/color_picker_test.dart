import 'package:flutter/material.dart' hide Element;
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/fields.dart';
import 'package:shadr_editor/theme.dart';

void main() {
  final picked = <int?>[];

  Future<void> open(WidgetTester tester, int initial) async {
    picked.clear();
    await tester.pumpWidget(MaterialApp(
      theme: buildEditorTheme(),
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: ElevatedButton(
              onPressed: () async {
                picked.add(await showDialog<int>(
                  context: context,
                  builder: (_) => ColorPickerDialog(initial: initial),
                ));
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('cancelling reports no colour at all', (tester) async {
    await open(tester, 0x4CC9F0);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    expect(find.byType(ColorPickerDialog), findsNothing);
    expect(picked, [null]);
  });

  testWidgets('the hex field seeds from the colour and drives the picker', (tester) async {
    await open(tester, 0x4CC9F0);
    expect(find.text('4cc9f0'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'ff0000');
    await tester.pumpAndSettle();

    final state = tester.state<State<ColorPickerDialog>>(find.byType(ColorPickerDialog));
    expect((state as dynamic).hsvForTest.hue, closeTo(0, 0.01));
    expect((state as dynamic).hsvForTest.saturation, closeTo(1, 0.01));
  });

  testWidgets('applying returns the picked value as packed rgb', (tester) async {
    await open(tester, 0x000000);
    await tester.enterText(find.byType(TextField), '7209b7');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();

    expect(picked, [0x7209B7]);
  });

  testWidgets('dragging the hue strip changes the colour without touching saturation',
      (tester) async {
    await open(tester, 0xFF0000);
    final state = tester.state<State<ColorPickerDialog>>(find.byType(ColorPickerDialog));
    final before = (state as dynamic).hsvForTest as HSVColor;

    final strip = find.byKey(const ValueKey('hue-slider'));
    final box = tester.getRect(strip);
    await tester.tapAt(Offset(box.left + box.width * 0.5, box.center.dy));
    await tester.pumpAndSettle();

    final after = (state as dynamic).hsvForTest as HSVColor;
    expect(after.hue, greaterThan(before.hue));
    expect(after.saturation, closeTo(before.saturation, 0.001));
    expect(after.value, closeTo(before.value, 0.001));
  });

  testWidgets('a preset selects that colour', (tester) async {
    await open(tester, 0x000000);
    final state = tester.state<State<ColorPickerDialog>>(find.byType(ColorPickerDialog));

    await tester.tap(find.byKey(ValueKey('preset-${colorPresets.last}')));
    await tester.pumpAndSettle();

    expect((state as dynamic).rgbForTest, colorPresets.last);
  });
}
