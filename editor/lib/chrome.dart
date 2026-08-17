import 'package:flutter/material.dart' hide Element;

import 'theme.dart';

class Panel extends StatelessWidget {
  const Panel({
    super.key,
    required this.title,
    required this.child,
    this.trailing,
    this.borderSide = BorderSide.none,
  });

  final String title;
  final Widget child;
  final Widget? trailing;

  final BorderSide borderSide;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: tokens.surface,
        border: Border(left: borderSide, right: borderSide),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PanelHeader(title: title, trailing: trailing),
          Expanded(child: child),
        ],
      ),
    );
  }
}

class PanelHeader extends StatelessWidget {
  const PanelHeader({super.key, required this.title, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Container(
      height: 30,
      padding: const EdgeInsets.only(left: Insets.md, right: Insets.xs),
      decoration: BoxDecoration(
        color: tokens.surfaceRaised,
        border: Border(bottom: BorderSide(color: tokens.border)),
      ),
      child: Row(
        children: [
          Expanded(child: Text(title.toUpperCase(), style: context.texts.labelSmall)),
          ?trailing,
        ],
      ),
    );
  }
}

class Section extends StatelessWidget {
  const Section({super.key, required this.title, required this.children, this.trailing});

  final String title;
  final List<Widget> children;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: Insets.md, bottom: Insets.xs),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(Insets.md, 0, Insets.sm, Insets.sm),
              child: Row(
                children: [
                  Expanded(child: Text(title.toUpperCase(), style: context.texts.labelSmall)),
                  ?trailing,
                ],
              ),
            ),
            ...children,
          ],
        ),
      );
}

class PropertyRow extends StatelessWidget {
  const PropertyRow({super.key, required this.label, required this.child, this.labelWidth = 62});

  final String label;
  final Widget child;
  final double labelWidth;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(Insets.md, 0, Insets.md, Insets.xs),
        child: Table(
          columnWidths: {
            0: FixedColumnWidth(labelWidth),
            1: const FlexColumnWidth(),
          },
          defaultVerticalAlignment: TableCellVerticalAlignment.middle,
          children: [
            TableRow(
              children: [
                Text(
                  label,
                  style: context.texts.bodySmall,
                  overflow: TextOverflow.ellipsis,
                ),
                child,
              ],
            ),
          ],
        ),
      );
}

class ToolButton extends StatelessWidget {
  const ToolButton({
    super.key,
    required this.icon,
    required this.tooltip,
    this.onPressed,
    this.active = false,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;

  final bool active;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final enabled = onPressed != null;
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: onPressed,
        borderRadius: Corners.small,
        child: SizedBox(
          width: 26,
          height: 26,
          child: Icon(
            icon,
            size: 15,
            color: !enabled
                ? tokens.textTertiary.withValues(alpha: 0.4)
                : active
                    ? tokens.accent
                    : tokens.textSecondary,
          ),
        ),
      ),
    );
  }
}

class Splitter extends StatelessWidget {
  const Splitter({super.key, required this.onDrag, this.vertical = true});

  final ValueChanged<double> onDrag;

  final bool vertical;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return MouseRegion(
      cursor: vertical ? SystemMouseCursors.resizeColumn : SystemMouseCursors.resizeRow,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onHorizontalDragUpdate: vertical ? (d) => onDrag(d.delta.dx) : null,
        onVerticalDragUpdate: vertical ? null : (d) => onDrag(d.delta.dy),
        child: SizedBox(
          width: vertical ? 7 : null,
          height: vertical ? null : 7,
          child: Center(
            child: Container(
              width: vertical ? 1 : null,
              height: vertical ? null : 1,
              color: tokens.border,
            ),
          ),
        ),
      ),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.title, this.detail, this.action});

  final IconData icon;
  final String title;
  final String? detail;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(Insets.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 22, color: tokens.textTertiary),
            const SizedBox(height: Insets.md),
            Text(title, style: context.texts.bodyMedium, textAlign: TextAlign.center),
            if (detail != null) ...[
              const SizedBox(height: Insets.sm),
              Text(
                detail!,
                style: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
                textAlign: TextAlign.center,
              ),
            ],
            if (action != null) ...[const SizedBox(height: Insets.md), action!],
          ],
        ),
      ),
    );
  }
}
