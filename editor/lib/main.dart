import 'package:flutter/material.dart' hide Element;

import 'actions.dart';
import 'canvas.dart';
import 'chrome.dart';
import 'documents.dart';
import 'layers.dart';
import 'images.dart';
import 'model.dart';
import 'properties.dart';
import 'protocol.dart';
import 'shaders.dart';
import 'theme.dart';
import 'timeline.dart';
import 'videos.dart';

void main() => runApp(const ShadrEditorApp());

class ShadrEditorApp extends StatelessWidget {
  const ShadrEditorApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'shadr editor',
        debugShowCheckedModeBanner: false,
        theme: buildEditorTheme(),
        home: const EditorShell(),
      );
}

class EditorShell extends StatefulWidget {
  const EditorShell({super.key});

  @override
  State<EditorShell> createState() => _EditorShellState();
}

class _EditorShellState extends State<EditorShell> {
  late final EditorModel _model = EditorModel(endpoint: Endpoint.from(Uri.base));
  late final EditorActionRegistry _actions =
      EditorActionRegistry(_model, () => _canvasSize);
  late final EditorShortcutManager _shortcuts =
      EditorShortcutManager(shortcuts: editorShortcuts);

  Size _canvasSize = Size.zero;

  double _layersWidth = 232;
  double _propertiesWidth = 268;
  double _timelineHeight = 176;

  @override
  void initState() {
    super.initState();
    _model.addListener(_onModelChanged);
    _model.connect();
  }

  @override
  void dispose() {
    _model.removeListener(_onModelChanged);
    _actions.dispose();
    _shortcuts.dispose();
    _model.dispose();
    super.dispose();
  }

  void _onModelChanged() {
    final result = _model.lastSave;
    if (result == null) return;
    if (result.skipped.isEmpty && result.expressionsReplaced.isEmpty) {
      _model.acknowledgeSave();
      return;
    }
    _model.acknowledgeSave();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _showSaveReport(result);
    });
  }

  @override
  Widget build(BuildContext context) {
    return EditorScope(
      model: _model,
      child: Shortcuts.manager(
        manager: _shortcuts,
        child: Actions(
          actions: _actions.map,
          child: Focus(
            autofocus: true,
            child: Scaffold(
              body: Column(
                children: [
                  const _Toolbar(),
                  Expanded(child: _workspace()),
                  const _StatusBar(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _drainNotice(BuildContext context) {
    final notice = _model.notice;
    if (notice == null) return;
    _model.acknowledgeNotice();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(notice), duration: const Duration(seconds: 3)));
    });
  }

  Widget _workspace() {
    return ListenableBuilder(
      listenable: _model,
      builder: (context, _) {
        _drainNotice(context);
        return Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _ModeRail(),
            Expanded(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 160),
                switchInCurve: Curves.easeOut,
                switchOutCurve: Curves.easeIn,
                child: KeyedSubtree(
                  key: ValueKey(_model.workspace),
                  child: switch (_model.workspace) {
                    Workspace.shaders => const ShaderWorkspace(),
                    Workspace.images => const ImagesWorkspace(),
                    Workspace.videos => const VideosWorkspace(),
                    Workspace.ui => _uiWorkspace(),
                  },
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _uiWorkspace() {
        if (_model.snapshot == null) return const _Disconnected();
        return Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(width: _layersWidth, child: const LayersPanel()),
            Splitter(
              onDrag: (dx) => setState(
                () => _layersWidth = (_layersWidth + dx).clamp(160.0, 420.0),
              ),
            ),
            Expanded(
              child: Column(
                children: [
                  Expanded(
                    child: PageCanvas(onSizeChanged: (size) => _canvasSize = size),
                  ),
                  if (_model.timelineVisible) ...[
                    Splitter(
                      vertical: false,
                      onDrag: (dy) => setState(
                        () => _timelineHeight = (_timelineHeight - dy).clamp(96.0, 420.0),
                      ),
                    ),
                    SizedBox(height: _timelineHeight, child: const TimelinePanel()),
                  ],
                ],
              ),
            ),
            Splitter(
              onDrag: (dx) => setState(
                () => _propertiesWidth = (_propertiesWidth - dx).clamp(200.0, 480.0),
              ),
            ),
            SizedBox(width: _propertiesWidth, child: const PropertiesPanel()),
          ],
        );
  }

  void _showSaveReport(SaveResult result) {
    final tokens = context.tokens;
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: tokens.surfaceRaised,
        shape: const RoundedRectangleBorder(borderRadius: Corners.medium),
        title: Text(
          'Saved ${result.saved} ${result.saved == 1 ? 'element' : 'elements'}',
          style: context.texts.titleSmall,
        ),
        content: SizedBox(
          width: 420,
          child: ListView(
            shrinkWrap: true,
            children: [
              if (result.skipped.isNotEmpty) ...[
                Text('Not written', style: context.texts.labelSmall),
                const SizedBox(height: Insets.sm),
                for (final entry in result.skipped.entries)
                  Padding(
                    padding: const EdgeInsets.only(bottom: Insets.xs),
                    child: Text('${entry.key}: ${entry.value}', style: context.texts.bodySmall),
                  ),
              ],
              if (result.expressionsReplaced.isNotEmpty) ...[
                const SizedBox(height: Insets.md),
                Text('Expressions replaced', style: context.texts.labelSmall),
                const SizedBox(height: Insets.sm),
                Text(
                  'These fields held an expression that an edit turned into a literal.',
                  style: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
                ),
                const SizedBox(height: Insets.sm),
                for (final path in result.expressionsReplaced)
                  Text(path, style: context.texts.bodySmall),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('OK')),
        ],
      ),
    );
  }
}

class _ModeRail extends StatelessWidget {
  const _ModeRail();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    return NavigationRail(
      selectedIndex: model.workspace.index,
      onDestinationSelected: (index) => model.setWorkspace(Workspace.values[index]),
      labelType: NavigationRailLabelType.all,
      minWidth: 64,
      groupAlignment: -1.0,
      destinations: const [
        NavigationRailDestination(
          icon: Icon(Icons.dashboard_customize_outlined, size: 18),
          selectedIcon: Icon(Icons.dashboard_customize, size: 18),
          label: Text('UI'),
        ),
        NavigationRailDestination(
          icon: Icon(Icons.bolt_outlined, size: 18),
          selectedIcon: Icon(Icons.bolt, size: 18),
          label: Text('Shaders'),
        ),
        NavigationRailDestination(
          icon: Icon(Icons.image_outlined, size: 18),
          selectedIcon: Icon(Icons.image, size: 18),
          label: Text('Images'),
        ),
        NavigationRailDestination(
          icon: Icon(Icons.movie_outlined, size: 18),
          selectedIcon: Icon(Icons.movie, size: 18),
          label: Text('Videos'),
        ),
      ],
    );
  }
}

class _Toolbar extends StatelessWidget {
  const _Toolbar();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;
    final snapshot = model.snapshot;

    return Container(
      height: 38,
      padding: const EdgeInsets.symmetric(horizontal: Insets.sm),
      decoration: BoxDecoration(
        color: tokens.surfaceRaised,
        border: Border(bottom: BorderSide(color: tokens.border)),
      ),
      child: Row(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: Insets.sm),
            child: Text('shadr', style: context.texts.titleSmall),
          ),
          const DocumentBar(),
          const Spacer(),
          if (snapshot?.dirty ?? false)
            Padding(
              padding: const EdgeInsets.only(right: Insets.sm),
              child: Text(
                'unsaved',
                style: context.texts.bodySmall?.copyWith(color: tokens.attention),
              ),
            ),
          _ActionButton(icon: Icons.undo, tooltip: 'Undo  ⌘Z', intent: const UndoIntent()),
          _ActionButton(icon: Icons.redo, tooltip: 'Redo  ⇧⌘Z', intent: const RedoIntent()),
          _Divider(colour: tokens.border),
          ToolButton(
            icon: model.snapEnabled ? Icons.grid_on : Icons.grid_off,
            tooltip: model.snapEnabled ? 'Snapping on, hold Alt to bypass  ⇧G' : 'Snapping off  ⇧G',
            active: model.snapEnabled,
            onPressed: () => model.setSnapping(!model.snapEnabled),
          ),
          _ActionButton(
            icon: Icons.fit_screen_outlined,
            tooltip: 'Zoom to fit  F',
            intent: const ZoomToFitIntent(),
          ),
          _ActionButton(
            icon: Icons.crop_original,
            tooltip: 'Actual size  ⌘0',
            intent: const ZoomToActualSizeIntent(),
          ),
          ToolButton(
            icon: Icons.movie_filter_outlined,
            tooltip: model.timelineVisible ? 'Hide the timeline' : 'Show the timeline',
            active: model.timelineVisible,
            onPressed: () => model.setTimelineVisible(!model.timelineVisible),
          ),
          _Divider(colour: tokens.border),
          _ActionButton(icon: Icons.save_outlined, tooltip: 'Save  ⌘S', intent: const SaveIntent()),
          _ActionButton(
            icon: Icons.refresh,
            tooltip: 'Reload from disk and discard edits  ⌘R',
            intent: const ReloadIntent(),
          ),
        ],
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({required this.icon, required this.tooltip, required this.intent});

  final IconData icon;
  final String tooltip;
  final Intent intent;

  @override
  Widget build(BuildContext context) {
    final action = Actions.maybeFind<Intent>(context, intent: intent);
    final enabled = action != null && action.isActionEnabled;
    return ToolButton(
      icon: icon,
      tooltip: tooltip,
      onPressed: enabled ? () => Actions.maybeInvoke(context, intent) : null,
    );
  }
}

class _Divider extends StatelessWidget {
  const _Divider({required this.colour});

  final Color colour;

  @override
  Widget build(BuildContext context) => Container(
        width: 1,
        height: 18,
        margin: const EdgeInsets.symmetric(horizontal: Insets.sm),
        color: colour,
      );
}

class _StatusBar extends StatelessWidget {
  const _StatusBar();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;
    final snapshot = model.snapshot;
    final selection = model.selection;

    final dot = switch (model.status) {
      ConnectionStatus.connected => tokens.accent,
      ConnectionStatus.connecting => tokens.attention,
      _ => tokens.danger,
    };

    return Container(
      height: 24,
      padding: const EdgeInsets.symmetric(horizontal: Insets.md),
      decoration: BoxDecoration(
        color: tokens.surfaceRaised,
        border: Border(top: BorderSide(color: tokens.border)),
      ),
      child: Row(
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
          ),
          const SizedBox(width: Insets.sm),
          Text(model.message, style: context.texts.bodySmall),
          const Spacer(),
          if (snapshot?.kind == DocumentKind.component)
            Padding(
              padding: const EdgeInsets.only(right: Insets.md),
              child: Text(
                'component: edits apply everywhere it is used',
                style: context.texts.bodySmall?.copyWith(color: tokens.attention),
              ),
            ),
          if (selection.length == 1)
            _Readout(text: _describe(model.soleSelection))
          else if (selection.length > 1)
            _Readout(text: '${selection.length} selected'),
          const _ZoomMenu(),
        ],
      ),
    );
  }

  static String _describe(Element? element) {
    if (element == null) return '';
    String n(double v) => v == v.roundToDouble() ? '${v.toInt()}' : v.toStringAsFixed(1);
    return '${n(element.x)}, ${n(element.y)}   ${n(element.width)} × ${n(element.height)}';
  }
}

class _ZoomMenu extends StatelessWidget {
  const _ZoomMenu();

  static const _steps = <double>[0.25, 0.5, 1, 2, 4];

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final size = model.viewportSize;
    final tokens = context.tokens;

    return PopupMenuButton<double>(
      tooltip: 'Zoom',
      position: PopupMenuPosition.over,
      color: tokens.surfaceRaised,
      onSelected: (value) => value == 0 ? model.fit(size) : model.zoomTo(value, size),
      itemBuilder: (context) => [
        const PopupMenuItem(value: 0, height: 32, child: Text('Zoom to fit    F')),
        const PopupMenuItem(value: 1, height: 32, child: Text('Actual size    ⌘0')),
        const PopupMenuDivider(),
        for (final step in _steps)
          PopupMenuItem(
            value: step,
            height: 32,
            child: Text('${(step * 100).round()}%'),
          ),
      ],
      child: Padding(
        padding: const EdgeInsets.only(left: Insets.lg),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '${(model.viewport.scale * 100).round()}%',
              style: context.texts.bodySmall?.copyWith(
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
            Icon(Icons.arrow_drop_down, size: 14, color: tokens.textTertiary),
          ],
        ),
      ),
    );
  }
}

class _Readout extends StatelessWidget {
  const _Readout({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(left: Insets.lg),
        child: Text(
          text,
          style: context.texts.bodySmall?.copyWith(
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      );
}

class _Disconnected extends StatelessWidget {
  const _Disconnected();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final connected = model.status == ConnectionStatus.connected;
    final empty = connected && model.documents.isEmpty;

    return EmptyState(
      icon: switch (model.status) {
        ConnectionStatus.connecting => Icons.cable,
        ConnectionStatus.connected => Icons.note_add_outlined,
        _ => Icons.power_off_outlined,
      },
      title: switch (model.status) {
        ConnectionStatus.connecting => 'Connecting…',
        ConnectionStatus.connected => empty ? 'Nothing to edit yet' : 'No document open',
        _ => 'Not connected',
      },
      detail: empty
          ? 'Make a page for a menu or a HUD, or a component other pages can place.'
          : model.message,
      action: switch (model.status) {
        ConnectionStatus.connecting => null,
        ConnectionStatus.connected => FilledButton.icon(
            onPressed: () => showNewDocumentDialog(context, model),
            icon: const Icon(Icons.add, size: 15),
            label: const Text('New document'),
          ),
        _ => OutlinedButton(onPressed: model.connect, child: const Text('Reconnect')),
      },
    );
  }
}
