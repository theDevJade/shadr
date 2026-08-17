import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/glsl_syntax.dart';
import 'package:shadr_editor/theme.dart';

void main() {
  final palette = GlslPalette.of(EditorTokens.dark);

  String flatten(InlineSpan span) {
    final buffer = StringBuffer();
    span.visitChildren((s) {
      if (s is TextSpan && s.text != null) buffer.write(s.text);
      return true;
    });
    return buffer.toString();
  }

  late BuildContext ctx;

  Future<InlineSpan> build(WidgetTester tester, String source) async {
    await tester.pumpWidget(
      MaterialApp(home: Builder(builder: (c) {
        ctx = c;
        return const SizedBox.shrink();
      })),
    );
    return GlslHighlightingController(text: source, palette: palette)
        .buildTextSpan(context: ctx, style: const TextStyle(), withComposing: false);
  }

  testWidgets('every character survives highlighting, in order', (tester) async {
    const source = '''
#version 330
// a comment with a keyword: uniform
/* block
   comment */
uniform sampler2D Sampler0;
vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float x = 1.0e-3 + .5 - 2u;
    return mix(vec4(uv, 0.0, 1.0), tint, clamp(x, 0.0, 1.0));
}
''';
    expect(flatten(await build(tester, source)), source);
  });

  testWidgets('an unterminated block comment drops nothing', (tester) async {
    const source = 'vec4 a;\n/* never closed\nfloat b;';
    expect(flatten(await build(tester, source)), source);
  });

  testWidgets('empty and whitespace-only input are handled', (tester) async {
    expect(flatten(await build(tester, '')), '');
    expect(flatten(await build(tester, '\n\n   \t')), '\n\n   \t');
  });

  testWidgets('a keyword inside an identifier is not highlighted', (tester) async {
    final span = await build(tester, 'float informal = 1.0;');
    final coloured = <String>[];
    span.visitChildren((s) {
      if (s is TextSpan && s.text != null && s.style?.color != null) coloured.add(s.text!);
      return true;
    });
    expect(coloured, contains('float'));
    expect(coloured, isNot(contains('for')));
    expect(coloured, isNot(contains('informal')));
  });

  testWidgets('a hash mid-line is not treated as a directive', (tester) async {
    const source = 'int a = 1; // not # a directive\n#define X 1\n';
    expect(flatten(await build(tester, source)), source);
  });

  testWidgets('shadr helpers are highlighted distinctly from the language', (tester) async {
    final span = await build(tester, 'float d = shadr_sd_circle(p, 0.5);');
    Color? colourOf(String text) {
      Color? found;
      span.visitChildren((s) {
        if (s is TextSpan && s.text == text) found = s.style?.color;
        return true;
      });
      return found;
    }

    expect(colourOf('shadr_sd_circle'), palette.shadr);
    expect(colourOf('float'), palette.type);
  });
}
