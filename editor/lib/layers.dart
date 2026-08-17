import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';

import 'chrome.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

class LayerNode {
  LayerNode(this.element, this.children);

  final Element element;
  final List<LayerNode> children;
}

List<LayerNode> buildLayerTree(List<Element> elements) {
  final byPath = <String, Element>{
    for (final element in elements)
      if (element.sourcePath.isNotEmpty) element.sourcePath: element,
  };
  final nodes = <String, LayerNode>{};
  final roots = <LayerNode>[];

  final ordered = [...elements]
    ..sort((a, b) => a.sourcePath.length.compareTo(b.sourcePath.length));

  for (final element in ordered) {
    final node = LayerNode(element, []);
    nodes[element.sourcePath] = node;

    final parentPath = element.parentPath;
    final parent = parentPath == null ? null : nodes[parentPath];
    if (parent != null && byPath.containsKey(parentPath)) {
      parent.children.add(node);
    } else {
      roots.add(node);
    }
  }

  void sortByLayer(List<LayerNode> list) {
    list.sort((a, b) => b.element.effectiveLayer.compareTo(a.element.effectiveLayer));
    for (final node in list) {
      sortByLayer(node.children);
    }
  }

  sortByLayer(roots);
  return roots;
}

class LayersPanel extends StatefulWidget {
  const LayersPanel({super.key});

  @override
  State<LayersPanel> createState() => _LayersPanelState();
}

class _LayersPanelState extends State<LayersPanel> {
  final Set<String> _collapsed = <String>{};
  String _filter = '';

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final snapshot = model.snapshot;
    if (snapshot == null) return const SizedBox.shrink();

    final matching = _filter.isEmpty
        ? snapshot.elements
        : snapshot.elements
            .where((e) => e.id.toLowerCase().contains(_filter) ||
                e.type.toLowerCase().contains(_filter))
            .toList();
    final roots = buildLayerTree(matching);

    return Panel(
      title: 'Layers',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          ToolButton(
            icon: Icons.flip_to_front,
            tooltip: 'Bring forward  ⌘]',
            onPressed: model.canReorder ? model.bringForward : null,
          ),
          ToolButton(
            icon: Icons.flip_to_back,
            tooltip: 'Send backward  ⌘[',
            onPressed: model.canReorder ? model.sendBackward : null,
          ),
          const SizedBox(width: Insets.xs),
          Text('${snapshot.elements.length}', style: context.texts.labelSmall),
        ],
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(Insets.sm, Insets.sm, Insets.sm, Insets.xs),
            child: _FilterField(onChanged: (value) => setState(() => _filter = value.toLowerCase())),
          ),
          Expanded(
            child: roots.isEmpty
                ? EmptyState(
                    icon: Icons.layers_outlined,
                    title: _filter.isEmpty ? 'This page has no elements' : 'Nothing matches',
                    detail: _filter.isEmpty ? 'Add one from the properties panel.' : null,
                  )
                : ListView(
                    padding: const EdgeInsets.only(bottom: Insets.md),
                    children: [
                      for (final root in roots) ..._rows(context, model, root, 0),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  List<Widget> _rows(BuildContext context, EditorModel model, LayerNode node, int depth) {
    final collapsed = _collapsed.contains(node.element.id);
    return [
      _LayerRow(
        node: node,
        depth: depth,
        collapsed: collapsed,
        selected: model.selection.contains(node.element.id),
        lockReason: model.lockReason(node.element.id),
        onToggle: () => setState(() {
          collapsed ? _collapsed.remove(node.element.id) : _collapsed.add(node.element.id);
        }),
        onTap: () => model.select(
          node.element.id,
          additive: HardwareKeyboard.instance.isShiftPressed ||
              HardwareKeyboard.instance.isMetaPressed,
        ),
        onToggleVisible: () =>
            model.patchOne(node.element.id, 'enabled', '${!node.element.enabled}'),
      ),
      if (!collapsed)
        for (final child in node.children) ..._rows(context, model, child, depth + 1),
    ];
  }
}

class _FilterField extends StatelessWidget {
  const _FilterField({required this.onChanged});

  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return SizedBox(
      height: 24,
      child: TextField(
        onChanged: onChanged,
        style: context.texts.bodyMedium,
        cursorWidth: 1,
        cursorColor: tokens.accent,
        decoration: InputDecoration(
          hintText: 'Filter',
          hintStyle: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
          prefixIcon: Icon(Icons.search, size: 13, color: tokens.textTertiary),
          prefixIconConstraints: const BoxConstraints(minWidth: 26, minHeight: 0),
          isDense: true,
          filled: true,
          fillColor: tokens.surfaceSunken,
          contentPadding: const EdgeInsets.symmetric(horizontal: Insets.sm),
          border: OutlineInputBorder(
            borderRadius: Corners.small,
            borderSide: BorderSide(color: tokens.border),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: Corners.small,
            borderSide: BorderSide(color: tokens.border),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: Corners.small,
            borderSide: BorderSide(color: tokens.accent),
          ),
        ),
      ),
    );
  }
}

class _LayerRow extends StatelessWidget {
  const _LayerRow({
    required this.node,
    required this.depth,
    required this.collapsed,
    required this.selected,
    required this.lockReason,
    required this.onToggle,
    required this.onTap,
    required this.onToggleVisible,
  });

  final LayerNode node;
  final int depth;
  final bool collapsed;
  final bool selected;
  final String? lockReason;
  final VoidCallback onToggle;
  final VoidCallback onTap;
  final VoidCallback onToggleVisible;

  static const _icons = <String, IconData>{
    'TEXT': Icons.text_fields,
    'HITBOX': Icons.crop_free,
    'BLOCK_ROUNDED': Icons.rounded_corner,
    'BLOCK_SDF': Icons.rounded_corner,
    'CIRCLE': Icons.circle_outlined,
    'GRADIENT': Icons.gradient,
    'BLUR': Icons.blur_on,
    'PROGRESS': Icons.remove,
    'GRID': Icons.grid_view,
  };

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final element = node.element;
    final dimmed = !element.enabled;

    return InkWell(
      onTap: onTap,
      child: Container(
        height: 24,
        padding: EdgeInsets.only(left: Insets.sm + depth * 12, right: Insets.xs),
        color: selected ? tokens.surfaceSelected : null,
        child: Row(
          children: [
            SizedBox(
              width: 14,
              child: node.children.isEmpty
                  ? null
                  : InkWell(
                      onTap: onToggle,
                      child: Icon(
                        collapsed ? Icons.chevron_right : Icons.expand_more,
                        size: 13,
                        color: tokens.textTertiary,
                      ),
                    ),
            ),
            Icon(
              _icons[element.type] ?? Icons.crop_square,
              size: 12,
              color: selected ? tokens.accent : tokens.textTertiary,
            ),
            const SizedBox(width: Insets.sm),
            Expanded(
              child: Text(
                element.id,
                overflow: TextOverflow.ellipsis,
                style: context.texts.bodyMedium?.copyWith(
                  color: dimmed ? tokens.textTertiary : tokens.textPrimary,
                ),
              ),
            ),
            if (lockReason != null)
              Tooltip(
                message: lockReason!,
                child: Icon(Icons.link, size: 12, color: tokens.attention),
              ),
            InkWell(
              onTap: onToggleVisible,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: Insets.xs),
                child: Icon(
                  element.enabled ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                  size: 12,
                  color: element.enabled ? tokens.textTertiary : tokens.textSecondary,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
