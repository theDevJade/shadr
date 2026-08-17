import 'package:flutter/material.dart';

import 'theme.dart';

class GlslHighlightingController extends TextEditingController {
  GlslHighlightingController({super.text, required this.palette});

  final GlslPalette palette;

  static const _keywords = {
    'attribute', 'break', 'case', 'centroid', 'const', 'continue', 'default', 'discard', 'do',
    'else', 'flat', 'for', 'highp', 'if', 'in', 'inout', 'invariant', 'layout', 'lowp',
    'mediump', 'noperspective', 'out', 'precision', 'return', 'smooth', 'struct', 'switch',
    'uniform', 'varying', 'void', 'while', 'true', 'false',
  };

  static const _types = {
    'bool', 'double', 'float', 'int', 'uint',
    'bvec2', 'bvec3', 'bvec4', 'dvec2', 'dvec3', 'dvec4',
    'ivec2', 'ivec3', 'ivec4', 'uvec2', 'uvec3', 'uvec4',
    'vec2', 'vec3', 'vec4', 'mat2', 'mat3', 'mat4',
    'mat2x2', 'mat2x3', 'mat2x4', 'mat3x2', 'mat3x3', 'mat3x4',
    'mat4x2', 'mat4x3', 'mat4x4',
    'sampler1D', 'sampler2D', 'sampler3D', 'samplerCube', 'isamplerBuffer',
  };

  static const _builtins = {
    'abs', 'acos', 'all', 'any', 'asin', 'atan', 'ceil', 'clamp', 'cos', 'cosh', 'cross',
    'degrees', 'determinant', 'dFdx', 'dFdy', 'distance', 'dot', 'equal', 'exp', 'exp2',
    'faceforward', 'floor', 'fract', 'fwidth', 'greaterThan', 'inverse', 'inversesqrt',
    'length', 'lessThan', 'log', 'log2', 'matrixCompMult', 'max', 'min', 'mix', 'mod',
    'normalize', 'not', 'notEqual', 'pow', 'radians', 'reflect', 'refract', 'round', 'sign',
    'sin', 'sinh', 'smoothstep', 'sqrt', 'step', 'tan', 'tanh', 'texelFetch', 'texture',
    'textureSize', 'transpose', 'trunc',
    'gl_FragCoord', 'gl_FragDepth', 'gl_Position', 'gl_VertexID', 'gl_InstanceID',
  };

  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    final base = style ?? const TextStyle();
    final spans = <TextSpan>[];

    void emit(String text, Color? colour, {bool italic = false}) {
      if (text.isEmpty) return;
      spans.add(TextSpan(
        text: text,
        style: colour == null
            ? base
            : base.copyWith(
                color: colour,
                fontStyle: italic ? FontStyle.italic : null,
              ),
      ));
    }

    final source = text;
    var index = 0;
    var pending = StringBuffer();

    void flush() {
      emit(pending.toString(), null);
      pending = StringBuffer();
    }

    while (index < source.length) {
      final rest = source.length - index;
      final two = rest >= 2 ? source.substring(index, index + 2) : '';

      if (two == '//') {
        flush();
        final end = source.indexOf('\n', index);
        final stop = end < 0 ? source.length : end;
        emit(source.substring(index, stop), palette.comment, italic: true);
        index = stop;
        continue;
      }
      if (two == '/*') {
        flush();
        final end = source.indexOf('*/', index + 2);
        final stop = end < 0 ? source.length : end + 2;
        emit(source.substring(index, stop), palette.comment, italic: true);
        index = stop;
        continue;
      }

      final char = source[index];

      if (char == '#' && _atLineStart(source, index)) {
        flush();
        final end = source.indexOf('\n', index);
        final stop = end < 0 ? source.length : end;
        emit(source.substring(index, stop), palette.preprocessor);
        index = stop;
        continue;
      }

      if (_isDigit(char) ||
          (char == '.' && index + 1 < source.length && _isDigit(source[index + 1]))) {
        flush();
        var end = index;
        while (end < source.length &&
            (_isDigit(source[end]) ||
                source[end] == '.' ||
                source[end] == 'e' ||
                source[end] == 'E' ||
                source[end] == 'f' ||
                source[end] == 'u' ||
                ((source[end] == '-' || source[end] == '+') &&
                    end > index &&
                    (source[end - 1] == 'e' || source[end - 1] == 'E')))) {
          end++;
        }
        emit(source.substring(index, end), palette.number);
        index = end;
        continue;
      }

      if (_isWordStart(char)) {
        var end = index;
        while (end < source.length && _isWordPart(source[end])) {
          end++;
        }
        final word = source.substring(index, end);
        final colour = _keywords.contains(word)
            ? palette.keyword
            : _types.contains(word)
                ? palette.type
                : _builtins.contains(word)
                    ? palette.builtin
                    : word.startsWith('shadr_') || word.startsWith('SHADR_')
                        ? palette.shadr
                        : null;
        if (colour == null) {
          pending.write(word);
        } else {
          flush();
          emit(word, colour);
        }
        index = end;
        continue;
      }

      pending.write(char);
      index++;
    }
    flush();

    return TextSpan(style: base, children: spans);
  }

  static bool _isDigit(String c) => c.codeUnitAt(0) >= 0x30 && c.codeUnitAt(0) <= 0x39;

  static bool _isWordStart(String c) {
    final code = c.codeUnitAt(0);
    return (code >= 0x41 && code <= 0x5A) || (code >= 0x61 && code <= 0x7A) || c == '_';
  }

  static bool _isWordPart(String c) => _isWordStart(c) || _isDigit(c);

  static bool _atLineStart(String source, int index) {
    for (var i = index - 1; i >= 0; i--) {
      final c = source[i];
      if (c == '\n') return true;
      if (c != ' ' && c != '\t') return false;
    }
    return true;
  }
}

class GlslPalette {
  const GlslPalette({
    required this.comment,
    required this.keyword,
    required this.type,
    required this.builtin,
    required this.number,
    required this.preprocessor,
    required this.shadr,
  });

  final Color comment;
  final Color keyword;
  final Color type;
  final Color builtin;
  final Color number;
  final Color preprocessor;
  final Color shadr;

  factory GlslPalette.of(EditorTokens tokens) => GlslPalette(
        comment: tokens.textTertiary,
        keyword: const Color(0xFFD08BE8),
        type: const Color(0xFF6FD3C8),
        builtin: const Color(0xFF7FB2F0),
        number: const Color(0xFFE6B673),
        preprocessor: tokens.attention,
        shadr: tokens.accent,
      );
}
