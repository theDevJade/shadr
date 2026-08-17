import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart' hide Element;
import 'package:flutter/scheduler.dart' show Ticker;

import 'chrome.dart';
import 'glsl_syntax.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';
import 'webgl.dart';

class ShaderWorkspace extends StatefulWidget {
  const ShaderWorkspace({super.key});

  @override
  State<ShaderWorkspace> createState() => _ShaderWorkspaceState();
}

class _ShaderWorkspaceState extends State<ShaderWorkspace> {
  late final GlslHighlightingController _controller =
      GlslHighlightingController(palette: GlslPalette.of(EditorTokens.dark));
  final _preview = ShaderPreviewController();

  Timer? _compileDebounce;
  CompileResult _result = CompileResult.clean;

  String _syncedFrom = '';

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onTyped);
  }

  @override
  void dispose() {
    _compileDebounce?.cancel();
    _controller.dispose();
    _preview.dispose();
    super.dispose();
  }

  void _onTyped() {
    final model = EditorScope.read(context);
    _syncedFrom = _controller.text;
    model.editShader(_controller.text);
    _compileDebounce?.cancel();
    _compileDebounce = Timer(const Duration(milliseconds: 250), _compile);
  }

  void _compile() {
    if (!mounted) return;
    final model = EditorScope.read(context);
    if (model.openProgramPath != null) {
      setState(
        () => _result = const CompileResult(
          ok: true,
          diagnostics: [
            ShaderDiagnostic(
              line: 0,
              message:
                  'The game compiles world programs, so the editor cannot preview '
                  'them. Save and rebuild the pack to see the result.',
              isError: false,
            ),
          ],
        ),
      );
      return;
    }
    final (program, offset) = model.catalog.program(_controller.text);
    final result = _preview.compile(program, lineOffset: offset);
    if (mounted) setState(() => _result = result);
  }

  void _syncFromModel(EditorModel model) {
    if (model.shaderBuffer == _syncedFrom) return;
    _syncedFrom = model.shaderBuffer;
    _controller.value = TextEditingValue(
      text: model.shaderBuffer,
      selection: TextSelection.collapsed(offset: model.shaderBuffer.length),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) => _compile());
  }

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    _syncFromModel(model);

    return Row(
      children: [
        SizedBox(width: 220, child: _ShaderList(model: model)),
        const VerticalDivider(width: 1),
        Expanded(
          flex: 3,
          child: model.openShaderId == null && model.openProgramPath == null
              ? const _NoShader()
              : _Editor(controller: _controller, result: _result, model: model),
        ),
        const VerticalDivider(width: 1),
        SizedBox(
          width: 340,
          child: _PreviewPanel(
            preview: _preview,
            result: _result,
            model: model,
          ),
        ),
      ],
    );
  }
}

class _NoShader extends StatelessWidget {
  const _NoShader();

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Container(
      color: tokens.surface,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(Insets.lg),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.bolt, size: 28, color: tokens.textTertiary),
          const SizedBox(height: Insets.md),
          Text('No shader open', style: context.texts.titleSmall),
          const SizedBox(height: Insets.sm),
          SizedBox(
            width: 320,
            child: Text(
              'A shader draws a custom fragment program in place of an item.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: tokens.textTertiary),
            ),
          ),
        ],
      ),
    );
  }
}

class _ShaderList extends StatelessWidget {
  const _ShaderList({required this.model});

  final EditorModel model;

  @override
  Widget build(BuildContext context) {
    return Panel(
      title: 'Shaders',
      trailing: IconButton(
        tooltip: 'New shader',
        icon: const Icon(Icons.add, size: 16),
        padding: EdgeInsets.zero,
        constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
        onPressed: () => _promptForName(context, model),
      ),
      child: ListView(
        children: [
          if (model.catalog.shaders.isEmpty)
            Padding(
              padding: const EdgeInsets.all(Insets.md),
              child: Text(
                'No shaders yet. Add one to draw a custom fragment program in place of an item.',
                style: TextStyle(
                  fontSize: 11,
                  color: context.tokens.textTertiary,
                ),
              ),
            )
          else
            for (final shader in model.catalog.shaders)
              _ShaderRow(
                shader: shader,
                selected: shader.id == model.openShaderId,
                onOpen: () => model.openShaderDocument(shader.id),
                onDelete: () => model.removeShader(shader.id),
              ),
          if (model.catalog.environment.isNotEmpty) _WorldEffects(model: model),
        ],
      ),
    );
  }

  Future<void> _promptForName(BuildContext context, EditorModel model) async {
    final name = await _promptForId(context, title: 'New shader', initial: '');
    if (name != null) model.createShader(name);
  }
}

class _WorldEffects extends StatelessWidget {
  const _WorldEffects({required this.model});

  final EditorModel model;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(height: Insets.lg),
        Padding(
          padding: const EdgeInsets.fromLTRB(Insets.md, 0, Insets.md, 2),
          child: Row(
            children: [
              Text('WORLD', style: context.texts.labelSmall),
              const SizedBox(width: Insets.sm),
              Text(
                '${model.catalog.environment.where((e) => e.enabled).length}'
                '/${model.catalog.environment.length} in the pack',
                style: TextStyle(
                  fontSize: 9,
                  color: context.tokens.textTertiary,
                ),
              ),
            ],
          ),
        ),
        for (final effect in model.catalog.environment)
          _WorldEffectTile(effect: effect, model: model),
        const SizedBox(height: Insets.md),
      ],
    );
  }
}

class _WorldEffectTile extends StatelessWidget {
  const _WorldEffectTile({required this.effect, required this.model});

  final EnvironmentEffect effect;
  final EditorModel model;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return ExpansionTile(
      dense: true,
      tilePadding: const EdgeInsets.symmetric(horizontal: Insets.md),
      visualDensity: VisualDensity.compact,
      childrenPadding: const EdgeInsets.only(
        left: Insets.md,
        bottom: Insets.sm,
      ),
      shape: const Border(),
      collapsedShape: const Border(),
      title: Text(
        effect.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 11),
      ),
      subtitle: Text(
        effect.enabled ? 'in the pack' : 'not shipped',
        style: TextStyle(
          fontSize: 9,
          color: effect.enabled ? tokens.accent : tokens.textTertiary,
        ),
      ),
      leading: Icon(
        effect.enabled ? Icons.public : Icons.public_off,
        size: 15,
        color: effect.enabled ? tokens.accent : tokens.textTertiary,
      ),
      trailing: Transform.scale(
        scale: 0.75,
        child: Switch(
          value: effect.enabled,
          onChanged: (on) => model.setEnvironment(effect.id, on),
        ),
      ),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(0, 0, Insets.md, Insets.sm),
          child: Text(
            effect.description,
            style: TextStyle(
              fontSize: 10,
              color: tokens.textTertiary,
              height: 1.4,
            ),
          ),
        ),
        for (final program in effect.programs)
          ListTile(
            dense: true,
            visualDensity: VisualDensity.compact,
            contentPadding: const EdgeInsets.only(right: Insets.md),
            selected: model.openProgramPath == program.path,
            leading: Icon(
              program.customised
                  ? Icons.edit_document
                  : Icons.description_outlined,
              size: 14,
              color: program.customised ? tokens.accent : tokens.textTertiary,
            ),
            title: Text(program.label, style: const TextStyle(fontSize: 11)),
            subtitle: program.customised
                ? Text(
                    'overridden',
                    style: TextStyle(fontSize: 9, color: tokens.accent),
                  )
                : null,
            onTap: () => model.openWorldProgram(program.path),
          ),
      ],
    );
  }
}

Future<String?> _promptForId(
  BuildContext context, {
  required String title,
  required String initial,
}) async {
  final controller = TextEditingController(text: initial);
  final formKey = GlobalKey<FormState>();

  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: Form(
        key: formKey,
        child: TextFormField(
          controller: controller,
          autofocus: true,
          autovalidateMode: AutovalidateMode.onUserInteraction,
          decoration: const InputDecoration(
            labelText: 'id',
            helperText: 'lowercase letters, digits and underscores',
          ),
          validator: (value) {
            final v = (value ?? '').trim();
            if (v.isEmpty) return 'required';
            if (!RegExp(r'^[a-z0-9_]{1,48}$').hasMatch(v)) {
              return 'lowercase letters, digits and underscores only';
            }
            return null;
          },
          onFieldSubmitted: (value) {
            if (formKey.currentState?.validate() ?? false) {
              Navigator.of(context).pop(value.trim());
            }
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            if (formKey.currentState?.validate() ?? false) {
              Navigator.of(context).pop(controller.text.trim());
            }
          },
          child: const Text('OK'),
        ),
      ],
    ),
  );
  return result?.isEmpty ?? true ? null : result;
}

class _ShaderRow extends StatelessWidget {
  const _ShaderRow({
    required this.shader,
    required this.selected,
    required this.onOpen,
    required this.onDelete,
  });

  final ShaderSummary shader;
  final bool selected;
  final VoidCallback onOpen;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final model = EditorScope.of(context);

    return MenuAnchor(
      menuChildren: [
        MenuItemButton(
          leadingIcon: const Icon(Icons.drive_file_rename_outline, size: 15),
          onPressed: () => _rename(context, model),
          child: const Text('Rename…'),
        ),
        MenuItemButton(
          leadingIcon: const Icon(Icons.copy_all_outlined, size: 15),
          onPressed: () => _duplicate(context, model),
          child: const Text('Duplicate'),
        ),
        const Divider(height: 1),
        MenuItemButton(
          leadingIcon: Icon(
            Icons.delete_outline,
            size: 15,
            color: tokens.danger,
          ),
          onPressed: onDelete,
          child: Text('Delete', style: TextStyle(color: tokens.danger)),
        ),
      ],
      builder: (context, controller, child) => GestureDetector(
        onSecondaryTapDown: (details) =>
            _openAt(controller, details.localPosition),
        onLongPressStart: (details) =>
            _openAt(controller, details.localPosition),
        child: InkWell(
          onTap: onOpen,
          child: Container(
            color: selected ? tokens.surfaceSelected : null,
            padding: const EdgeInsets.symmetric(
              horizontal: Insets.md,
              vertical: 6,
            ),
            child: Row(
              children: [
                Icon(
                  shader.issues.isEmpty ? Icons.bolt : Icons.error_outline,
                  size: 12,
                  color: shader.issues.isEmpty
                      ? tokens.accent
                      : tokens.attention,
                ),
                const SizedBox(width: Insets.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(shader.id, style: const TextStyle(fontSize: 11)),
                      if (shader.description.isNotEmpty)
                        Text(
                          shader.description,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 10,
                            color: tokens.textTertiary,
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _openAt(MenuController controller, Offset at) {
    if (controller.isOpen) {
      controller.close();
    } else {
      controller.open(position: at);
    }
  }

  Future<void> _rename(BuildContext context, EditorModel model) async {
    final name = await _promptForId(
      context,
      title: 'Rename shader',
      initial: shader.id,
    );
    if (name != null && name != shader.id) model.renameShader(shader.id, name);
  }

  Future<void> _duplicate(BuildContext context, EditorModel model) async {
    final name = await _promptForId(
      context,
      title: 'Duplicate shader',
      initial: '${shader.id}_copy',
    );
    if (name != null) model.duplicateShader(shader.id, name);
  }
}

class _Editor extends StatelessWidget {
  const _Editor({
    required this.controller,
    required this.result,
    required this.model,
  });

  final TextEditingController controller;
  final CompileResult result;
  final EditorModel model;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final program = model.openProgramPath;
    return Panel(
      title: program?.split('/').last ?? model.openShaderId ?? 'Shader',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (program != null && model.programCustomised)
            TextButton(
              onPressed: model.revertWorldProgram,
              child: const Text('Revert', style: TextStyle(fontSize: 10)),
            ),
          if (model.shaderDirty)
            Padding(
              padding: const EdgeInsets.only(right: Insets.sm),
              child: Text(
                'unsaved',
                style: TextStyle(fontSize: 10, color: tokens.attention),
              ),
            ),
          FilledButton(
            onPressed: model.saveShaderDocument,
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text('Save & rebuild', style: TextStyle(fontSize: 10)),
          ),
        ],
      ),
      child: Column(
        children: [
          Expanded(
            child: Container(
              color: tokens.canvasVoid,
              padding: const EdgeInsets.all(Insets.md),
              child: TextField(
                controller: controller,
                maxLines: null,
                expands: true,
                textAlignVertical: TextAlignVertical.top,
                keyboardType: TextInputType.multiline,
                autocorrect: false,
                enableSuggestions: false,
                smartQuotesType: SmartQuotesType.disabled,
                smartDashesType: SmartDashesType.disabled,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                  height: 1.45,
                ),
                decoration: const InputDecoration(
                  border: InputBorder.none,
                  isDense: true,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ),
          ),
          _Diagnostics(result: result, serverIssues: model.shaderIssues),
        ],
      ),
    );
  }
}

class _Diagnostics extends StatelessWidget {
  const _Diagnostics({required this.result, required this.serverIssues});

  final CompileResult result;
  final List<String> serverIssues;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final clean = result.ok && serverIssues.isEmpty;

    return Container(
      constraints: const BoxConstraints(maxHeight: 132),
      width: double.infinity,
      decoration: BoxDecoration(
        color: tokens.surfaceSunken,
        border: Border(top: BorderSide(color: tokens.border)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: Insets.md, vertical: 6),
      child: clean
          ? Row(
              children: [
                Icon(
                  Icons.check_circle_outline,
                  size: 12,
                  color: tokens.accent,
                ),
                const SizedBox(width: 6),
                Text(
                  'compiles',
                  style: TextStyle(fontSize: 11, color: tokens.textTertiary),
                ),
              ],
            )
          : ListView(
              shrinkWrap: true,
              children: [
                for (final issue in serverIssues)
                  _DiagnosticRow(text: issue, color: tokens.attention),
                for (final diagnostic in result.diagnostics)
                  _DiagnosticRow(
                    text: diagnostic.line > 0
                        ? 'line ${diagnostic.line}: ${diagnostic.message}'
                        : diagnostic.message,
                    color: diagnostic.isError
                        ? tokens.danger
                        : tokens.attention,
                  ),
              ],
            ),
    );
  }
}

class _DiagnosticRow extends StatelessWidget {
  const _DiagnosticRow({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 2),
    child: SelectableText(
      text,
      style: TextStyle(fontFamily: 'monospace', fontSize: 11, color: color),
    ),
  );
}

class _PreviewPanel extends StatefulWidget {
  const _PreviewPanel({
    required this.preview,
    required this.result,
    required this.model,
  });

  final ShaderPreviewController preview;
  final CompileResult result;
  final EditorModel model;

  @override
  State<_PreviewPanel> createState() => _PreviewPanelState();
}

class _PreviewPanelState extends State<_PreviewPanel>
    with SingleTickerProviderStateMixin {
  static const _dragRadiansPerPixel = 0.011;
  static const _pitchLimit = 1.4;

  late final Ticker _ticker = createTicker(_onTick);
  Duration _elapsed = Duration.zero;
  bool _playing = true;
  Color _tint = const Color(0xFF4CC9F0);
  Offset _orbit = Offset.zero;

  @override
  void initState() {
    super.initState();
    _ticker.start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  void _onTick(Duration elapsed) {
    if (!_playing) return;
    _elapsed = elapsed;
    _draw();
  }

  void _draw() {
    widget.preview.render(
      time: _elapsed.inMicroseconds / 1e6,
      tint: _tint,
      opacity: 1,
      orbit: _orbit,
    );
  }

  void _orbitBy(Offset delta) {
    setState(() {
      _orbit = Offset(
        _orbit.dx - delta.dx * _dragRadiansPerPixel,
        (_orbit.dy + delta.dy * _dragRadiansPerPixel).clamp(
          -_pitchLimit,
          _pitchLimit,
        ),
      );
    });
    _draw();
  }

  void _resetOrbit() {
    setState(() => _orbit = Offset.zero);
    _draw();
  }

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final orbited = _orbit != Offset.zero;

    return Panel(
      title: 'Preview',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip: 'Face on',
            icon: const Icon(Icons.threed_rotation, size: 16),
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
            color: orbited ? tokens.accent : null,
            onPressed: orbited ? _resetOrbit : null,
          ),
          IconButton(
            tooltip: _playing ? 'Pause' : 'Play',
            icon: Icon(_playing ? Icons.pause : Icons.play_arrow, size: 16),
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
            onPressed: () => setState(() => _playing = !_playing),
          ),
        ],
      ),
      child: ListView(
        padding: const EdgeInsets.all(Insets.md),
        children: [
          AspectRatio(
            aspectRatio: 1,
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: tokens.border),
                color: tokens.canvasVoid,
              ),
              child: widget.preview.available
                  ? _OrbitGesture(
                      onDrag: _orbitBy,
                      onReset: _resetOrbit,
                      child: ShaderPreviewSurface(controller: widget.preview),
                    )
                  : Center(
                      child: Text(
                        'WebGL2 unavailable',
                        style: TextStyle(
                          fontSize: 11,
                          color: tokens.textTertiary,
                        ),
                      ),
                    ),
            ),
          ),
          const SizedBox(height: Insets.md),
          Row(
            children: [
              Text(
                't = ${(_elapsed.inMilliseconds / 1000).toStringAsFixed(1)}s',
                style: TextStyle(fontSize: 10, color: tokens.textTertiary),
              ),
              const Spacer(),
              Text(
                orbited
                    ? '${_degrees(_orbit.dx)}° / ${_degrees(_orbit.dy)}°'
                    : 'drag to orbit',
                style: TextStyle(
                  fontSize: 10,
                  color: orbited ? tokens.textSecondary : tokens.textTertiary,
                ),
              ),
            ],
          ),
          const SizedBox(height: Insets.md),
          Text('TINT', style: context.texts.labelSmall),
          const SizedBox(height: 6),
          Wrap(
            spacing: 6,
            children: [
              for (final colour in const [
                Color(0xFF4CC9F0),
                Color(0xFFF0A04C),
                Color(0xFF7DE07D),
                Color(0xFFFF5577),
                Color(0xFFFFFFFF),
              ])
                GestureDetector(
                  onTap: () => setState(() => _tint = colour),
                  child: Container(
                    width: 20,
                    height: 20,
                    decoration: BoxDecoration(
                      color: colour,
                      borderRadius: BorderRadius.circular(3),
                      border: Border.all(
                        color: _tint == colour
                            ? Colors.white
                            : Colors.transparent,
                      ),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: Insets.lg),
          Text('HELPERS', style: context.texts.labelSmall),
          const SizedBox(height: 6),
          Text(
            'Every shader can call these, and the pack compiles the same ones.',
            style: TextStyle(fontSize: 10, color: tokens.textTertiary),
          ),
          const SizedBox(height: 6),
          for (final signature in _signatures(widget.model.catalog.helpers))
            Padding(
              padding: const EdgeInsets.only(bottom: 2),
              child: SelectableText(
                signature,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 10),
              ),
            ),
        ],
      ),
    );
  }

  static String _degrees(double radians) =>
      (radians * 180 / math.pi).round().toString();

  static List<String> _signatures(String helpers) =>
      RegExp(
            r'^\s*(float|vec2|vec3|vec4)\s+(shadr_\w+)\s*\(([^)]*)\)',
            multiLine: true,
          )
          .allMatches(helpers)
          .map((m) => '${m.group(1)} ${m.group(2)}(${m.group(3)})')
          .toSet()
          .toList()
        ..sort();
}

class _OrbitGesture extends StatefulWidget {
  const _OrbitGesture({
    required this.onDrag,
    required this.onReset,
    required this.child,
  });

  final ValueChanged<Offset> onDrag;
  final VoidCallback onReset;
  final Widget child;

  @override
  State<_OrbitGesture> createState() => _OrbitGestureState();
}

class _OrbitGestureState extends State<_OrbitGesture> {
  bool _dragging = false;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        widget.child,
        Positioned.fill(
          child: MouseRegion(
            cursor: _dragging
                ? SystemMouseCursors.grabbing
                : SystemMouseCursors.grab,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onDoubleTap: widget.onReset,
              onPanStart: (_) => setState(() => _dragging = true),
              onPanUpdate: (details) => widget.onDrag(details.delta),
              onPanEnd: (_) => setState(() => _dragging = false),
              onPanCancel: () => setState(() => _dragging = false),
            ),
          ),
        ),
      ],
    );
  }
}
