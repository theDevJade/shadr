library;

import 'dart:js_interop';
import 'dart:ui_web' as ui_web;

import 'package:flutter/material.dart';
import 'package:web/web.dart' as web;

class ShaderDiagnostic {
  const ShaderDiagnostic({required this.line, required this.message, this.isError = true});

  final int line;
  final String message;
  final bool isError;

  @override
  String toString() => 'line $line: $message';
}

class CompileResult {
  const CompileResult({required this.ok, required this.diagnostics});

  final bool ok;
  final List<ShaderDiagnostic> diagnostics;

  static const clean = CompileResult(ok: true, diagnostics: []);
}

const _vertexSource = '''#version 300 es
precision highp float;
const vec2 verts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() { gl_Position = vec4(verts[gl_VertexID], 0.0, 1.0); }
''';

class ShaderPreviewController {
  ShaderPreviewController() {
    _canvas = web.HTMLCanvasElement()
      ..width = 512
      ..height = 512;
    _canvas.style
      ..width = '100%'
      ..height = '100%'
      ..display = 'block';

    _gl = _canvas.getContext('webgl2') as web.WebGL2RenderingContext?;
  }

  late final web.HTMLCanvasElement _canvas;
  web.WebGL2RenderingContext? _gl;

  web.WebGLProgram? _program;
  web.WebGLUniformLocation? _uResolution;
  web.WebGLUniformLocation? _uTime;
  web.WebGLUniformLocation? _uTint;

  web.HTMLCanvasElement get element => _canvas;
  bool get available => _gl != null;

  CompileResult compile(String fragmentSource, {int lineOffset = 0}) {
    final gl = _gl;
    if (gl == null) {
      return const CompileResult(
        ok: false,
        diagnostics: [ShaderDiagnostic(line: 0, message: 'WebGL2 is not available in this browser')],
      );
    }

    final vertex = _compileStage(gl, web.WebGL2RenderingContext.VERTEX_SHADER, _vertexSource);
    if (vertex == null) {
      return const CompileResult(
        ok: false,
        diagnostics: [ShaderDiagnostic(line: 0, message: 'internal: vertex stage failed')],
      );
    }

    final fragment = gl.createShader(web.WebGL2RenderingContext.FRAGMENT_SHADER)!;
    gl.shaderSource(fragment, fragmentSource);
    gl.compileShader(fragment);

    final compiled = gl.getShaderParameter(fragment, web.WebGL2RenderingContext.COMPILE_STATUS);
    if (!(compiled.dartify() as bool? ?? false)) {
      final log = gl.getShaderInfoLog(fragment) ?? 'compilation failed';
      gl.deleteShader(fragment);
      gl.deleteShader(vertex);
      return CompileResult(ok: false, diagnostics: _parse(log, lineOffset));
    }

    final program = gl.createProgram()!;
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.linkProgram(program);

    final linked = gl.getProgramParameter(program, web.WebGL2RenderingContext.LINK_STATUS);
    if (!(linked.dartify() as bool? ?? false)) {
      final log = gl.getProgramInfoLog(program) ?? 'link failed';
      gl.deleteProgram(program);
      gl.deleteShader(fragment);
      gl.deleteShader(vertex);
      return CompileResult(ok: false, diagnostics: _parse(log, lineOffset));
    }

    if (_program != null) gl.deleteProgram(_program!);
    _program = program;
    _uResolution = gl.getUniformLocation(program, 'uResolution');
    _uTime = gl.getUniformLocation(program, 'uTime');
    _uTint = gl.getUniformLocation(program, 'uTint');

    gl.deleteShader(fragment);
    gl.deleteShader(vertex);
    return CompileResult.clean;
  }

  web.WebGLShader? _compileStage(web.WebGL2RenderingContext gl, int type, String source) {
    final shader = gl.createShader(type)!;
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    final ok = gl.getShaderParameter(shader, web.WebGL2RenderingContext.COMPILE_STATUS);
    if (!(ok.dartify() as bool? ?? false)) {
      gl.deleteShader(shader);
      return null;
    }
    return shader;
  }

  void render({required double time, required Color tint, required double opacity}) {
    final gl = _gl;
    final program = _program;
    if (gl == null || program == null) return;

    final width = _canvas.width;
    final height = _canvas.height;
    gl.viewport(0, 0, width, height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(web.WebGL2RenderingContext.COLOR_BUFFER_BIT);

    gl.useProgram(program);
    if (_uResolution != null) gl.uniform2f(_uResolution, width.toDouble(), height.toDouble());
    if (_uTime != null) gl.uniform1f(_uTime, time);
    if (_uTint != null) {
      gl.uniform4f(
        _uTint,
        (tint.r * 255.0).round() / 255.0,
        (tint.g * 255.0).round() / 255.0,
        (tint.b * 255.0).round() / 255.0,
        opacity,
      );
    }

    gl.enable(web.WebGL2RenderingContext.BLEND);
    gl.blendFunc(
      web.WebGL2RenderingContext.ONE,
      web.WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA,
    );
    gl.drawArrays(web.WebGL2RenderingContext.TRIANGLES, 0, 3);
  }

  void resize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    if (_canvas.width == width && _canvas.height == height) return;
    _canvas.width = width;
    _canvas.height = height;
  }

  void dispose() {
    final gl = _gl;
    if (gl != null && _program != null) gl.deleteProgram(_program!);
    _program = null;
    _gl = null;
  }

  static List<ShaderDiagnostic> _parse(String log, int lineOffset) {
    final out = <ShaderDiagnostic>[];
    final pattern = RegExp(r'^(ERROR|WARNING):\s*\d+:(\d+):\s*(.*)$');

    for (final raw in log.split('\n')) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final match = pattern.firstMatch(line);
      if (match == null) {
        out.add(ShaderDiagnostic(line: 0, message: line));
        continue;
      }
      final reported = int.tryParse(match.group(2)!) ?? 0;
      out.add(ShaderDiagnostic(
        line: reported > lineOffset ? reported - lineOffset : 0,
        message: match.group(3)!.trim(),
        isError: match.group(1) == 'ERROR',
      ));
    }
    return out.isEmpty ? [ShaderDiagnostic(line: 0, message: log.trim())] : out;
  }
}

class ShaderPreviewSurface extends StatefulWidget {
  const ShaderPreviewSurface({super.key, required this.controller});

  final ShaderPreviewController controller;

  @override
  State<ShaderPreviewSurface> createState() => _ShaderPreviewSurfaceState();
}

class _ShaderPreviewSurfaceState extends State<ShaderPreviewSurface> {
  static int _seed = 0;
  late final String _viewType;

  @override
  void initState() {
    super.initState();
    _viewType = 'shadr-shader-preview-${_seed++}';
    ui_web.platformViewRegistry.registerViewFactory(
      _viewType,
      (int _) => widget.controller.element,
    );
  }

  @override
  Widget build(BuildContext context) => HtmlElementView(viewType: _viewType);
}
