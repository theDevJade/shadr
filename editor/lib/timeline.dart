import 'package:flutter/material.dart' hide Element;

import 'chrome.dart';
import 'fields.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

class TimelinePanel extends StatelessWidget {
  const TimelinePanel({super.key});

  static const double headerWidth = 300;

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final snapshot = model.snapshot;
    if (snapshot == null) return const SizedBox.shrink();

    final animation = snapshot.animations.firstOrNull;
    final duration = animation?.duration ?? 20;
    final steps = animation?.steps ?? const <AnimationStep>[];
    final tokens = context.tokens;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: tokens.surface,
        border: Border(top: BorderSide(color: tokens.border)),
      ),
      child: Column(
        children: [
          _Toolbar(model: model, duration: duration),
          Expanded(
            child: steps.isEmpty
                ? EmptyState(
                    icon: Icons.animation,
                    title: 'No animated properties',
                    detail: model.soleSelection == null
                        ? 'Select one element, then add a track.'
                        : 'Add a track for ${model.soleSelection!.id} above.',
                  )
                : Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(
                        width: headerWidth,
                        child: ListView(
                          children: [
                            for (final step in steps)
                              _TrackHeader(
                                step: step,
                                selected: model.selection.contains(step.target),
                                onSelect: () => model.select(step.target),
                                onEdit: model.editStep,
                                onRemove: () => model.removeStep(step.target, step.axis),
                              ),
                          ],
                        ),
                      ),
                      VerticalDivider(width: 1, color: tokens.border),
                      Expanded(
                        child: ListView(
                          children: [
                            for (final step in steps)
                              _TrackLane(step: step, duration: duration, tokens: tokens),
                          ],
                        ),
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

class _Toolbar extends StatelessWidget {
  const _Toolbar({required this.model, required this.duration});

  final EditorModel model;
  final int duration;

  static const _axes = ['x', 'y', 'width', 'height', 'opacity', 'rotation'];

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final tick = model.snapshot?.previewTick;
    final selected = model.soleSelection;

    return Container(
      height: 32,
      padding: const EdgeInsets.symmetric(horizontal: Insets.md),
      decoration: BoxDecoration(
        color: tokens.surfaceRaised,
        border: Border(bottom: BorderSide(color: tokens.border)),
      ),
      child: Row(
        children: [
          Text('TIMELINE', style: context.texts.labelSmall),
          const SizedBox(width: Insets.md),
          SizedBox(
            width: 76,
            child: Text(
              tick == null ? 'authored' : 'tick $tick / $duration',
              style: context.texts.bodySmall?.copyWith(
                color: tick == null ? tokens.textTertiary : tokens.accent,
              ),
            ),
          ),
          Expanded(
            child: _Scrubber(
              duration: duration,
              tick: tick,
              tokens: tokens,
              onScrub: model.scrub,
            ),
          ),
          const SizedBox(width: Insets.md),
          if (selected != null)
            PopupMenuButton<String>(
              tooltip: 'Animate a property of ${selected.id}',
              position: PopupMenuPosition.under,
              color: tokens.surfaceRaised,
              onSelected: (axis) => model.addStep(selected.id, axis),
              itemBuilder: (context) => [
                for (final axis in _axes)
                  PopupMenuItem(
                    value: axis,
                    height: 30,
                    child: Text(axis, style: context.texts.bodyMedium),
                  ),
              ],
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.add, size: 13, color: tokens.textSecondary),
                  const SizedBox(width: Insets.xs),
                  Text('Track', style: context.texts.bodySmall),
                ],
              ),
            ),
          const SizedBox(width: Insets.sm),
          ToolButton(
            icon: Icons.restart_alt,
            tooltip: 'Reset the playhead',
            onPressed: tick == null ? null : () => model.scrub(null),
          ),
        ],
      ),
    );
  }
}

class _Scrubber extends StatelessWidget {
  const _Scrubber({
    required this.duration,
    required this.tick,
    required this.tokens,
    required this.onScrub,
  });

  final int duration;
  final int? tick;
  final EditorTokens tokens;
  final ValueChanged<int?> onScrub;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          final width = constraints.maxWidth;

          void report(double dx) =>
              onScrub((dx / width * duration).round().clamp(0, duration));

          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (d) => report(d.localPosition.dx),
            onHorizontalDragUpdate: (d) => report(d.localPosition.dx),
            child: MouseRegion(
              cursor: SystemMouseCursors.resizeLeftRight,
              child: CustomPaint(
                size: Size(width, 20),
                painter: _ScrubberPainter(duration: duration, tick: tick, tokens: tokens),
              ),
            ),
          );
        },
      );
}

class _ScrubberPainter extends CustomPainter {
  _ScrubberPainter({required this.duration, required this.tick, required this.tokens});

  final int duration;
  final int? tick;
  final EditorTokens tokens;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, size.height / 2 - 1, size.width, 2),
        const Radius.circular(1),
      ),
      Paint()..color = tokens.border,
    );

    final mark = Paint()..color = tokens.borderStrong;
    for (var t = 0; t <= duration; t += 5) {
      final x = size.width * t / duration;
      canvas.drawRect(Rect.fromLTWH(x, size.height / 2 - 4, 1, 8), mark);
    }

    final position = tick;
    if (position == null) return;
    final x = size.width * position / duration;
    canvas.drawRect(Rect.fromLTWH(x - 1, 0, 2, size.height), Paint()..color = tokens.accent);
  }

  @override
  bool shouldRepaint(_ScrubberPainter old) => old.duration != duration || old.tick != tick;
}

class _TrackHeader extends StatelessWidget {
  const _TrackHeader({
    required this.step,
    required this.selected,
    required this.onSelect,
    required this.onEdit,
    required this.onRemove,
  });

  final AnimationStep step;
  final bool selected;
  final VoidCallback onSelect;
  final void Function(AnimationStep, {double? from, double? to, int? duration, String? easing}) onEdit;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return InkWell(
      onTap: onSelect,
      child: Container(
        height: 30,
        color: selected ? tokens.surfaceSelected : null,
        padding: const EdgeInsets.symmetric(horizontal: Insets.sm),
        child: Row(
          children: [
            SizedBox(
              width: 96,
              child: Text(
                step.target,
                overflow: TextOverflow.ellipsis,
                style: context.texts.bodyMedium,
              ),
            ),
            SizedBox(
              width: 46,
              child: Text(step.axis, style: context.texts.bodySmall),
            ),
            SizedBox(
              width: 54,
              child: ScrubField(
                label: '',
                value: step.from,
                onChanged: (v) => onEdit(step, from: v),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: Insets.xs),
              child: Icon(Icons.arrow_right_alt, size: 13, color: tokens.textTertiary),
            ),
            SizedBox(
              width: 54,
              child: ScrubField(
                label: '',
                value: step.to,
                onChanged: (v) => onEdit(step, to: v),
              ),
            ),
            const Spacer(),
            InkWell(
              onTap: onRemove,
              child: Icon(Icons.close, size: 12, color: tokens.textTertiary),
            ),
          ],
        ),
      ),
    );
  }
}

class _TrackLane extends StatelessWidget {
  const _TrackLane({required this.step, required this.duration, required this.tokens});

  final AnimationStep step;
  final int duration;
  final EditorTokens tokens;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 30,
        child: Row(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: Insets.xs, horizontal: Insets.sm),
                child: CustomPaint(
                  painter: _CurvePainter(
                    easing: step.easing,
                    fraction: duration == 0 ? 1 : (step.duration / duration).clamp(0.0, 1.0),
                    tokens: tokens,
                  ),
                ),
              ),
            ),
            SizedBox(
              width: 104,
              child: _EasingPicker(step: step),
            ),
            SizedBox(
              width: 54,
              child: Padding(
                padding: const EdgeInsets.only(right: Insets.sm),
                child: Text(
                  '${step.duration}t',
                  textAlign: TextAlign.right,
                  style: context.texts.bodySmall,
                ),
              ),
            ),
          ],
        ),
      );
}

class _EasingPicker extends StatelessWidget {
  const _EasingPicker({required this.step});

  final AnimationStep step;

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;
    return DropdownButtonHideUnderline(
      child: DropdownButton<String>(
        value: step.easing,
        isDense: true,
        isExpanded: true,
        dropdownColor: tokens.surfaceRaised,
        style: context.texts.bodySmall,
        icon: Icon(Icons.expand_more, size: 13, color: tokens.textTertiary),
        items: [
          for (final curve in AnimationStep.easings)
            DropdownMenuItem(
              value: curve,
              child: Text(curve, style: context.texts.bodySmall),
            ),
        ],
        onChanged: (curve) {
          if (curve != null) model.editStep(step, easing: curve);
        },
      ),
    );
  }
}

class _CurvePainter extends CustomPainter {
  _CurvePainter({required this.easing, required this.fraction, required this.tokens});

  final String easing;
  final double fraction;
  final EditorTokens tokens;

  static double _apply(String easing, double t) {
    final x = t.clamp(0.0, 1.0);
    return switch (easing) {
      'ease-in' || 'smooth-in' => x * x,
      'ease-out' || 'smooth-out' => 1 - (1 - x) * (1 - x),
      'ease-in-out' || 'smooth' => x < 0.5 ? 2 * x * x : 1 - ((-2 * x + 2) * (-2 * x + 2)) / 2,
      _ => x,
    };
  }

  @override
  void paint(Canvas canvas, Size size) {
    final width = size.width * fraction;
    if (width <= 0) return;

    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, width, size.height),
        const Radius.circular(3),
      ),
      Paint()..color = tokens.accentMuted,
    );

    final path = Path();
    const samples = 28;
    for (var i = 0; i <= samples; i++) {
      final t = i / samples;
      final x = width * t;
      final y = size.height - 2 - _apply(easing, t) * (size.height - 4);
      i == 0 ? path.moveTo(x, y) : path.lineTo(x, y);
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = tokens.accent
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );
  }

  @override
  bool shouldRepaint(_CurvePainter old) => old.fraction != fraction || old.easing != easing;
}
