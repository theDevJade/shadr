import 'package:flutter/material.dart' hide Element;

import 'chrome.dart';
import 'fields.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

class PropertiesPanel extends StatefulWidget {
  const PropertiesPanel({super.key});

  @override
  State<PropertiesPanel> createState() => _PropertiesPanelState();
}

class _PropertiesPanelState extends State<PropertiesPanel> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final snapshot = model.snapshot;
    final selected = model.soleSelection;
    final count = model.selection.length;

    return Panel(
      title: selected?.id ?? (count > 1 ? '$count selected' : 'Properties'),
      trailing: selected == null
          ? null
          : Text(selected.type.toLowerCase(), style: context.texts.labelSmall),
      child: snapshot == null
          ? const SizedBox.shrink()
          : Column(
              children: [
                if (selected != null)
                  _Tabs(
                    labels: const ['Design', 'Interact'],
                    selected: _tab,
                    onSelected: (i) => setState(() => _tab = i),
                  ),
                Expanded(
                  child: Scrollbar(
                    child: ListView(
                      primary: true,
                      padding: const EdgeInsets.only(bottom: Insets.lg),
                      children: [
                        if (snapshot.issues.isNotEmpty) _Issues(issues: snapshot.issues),
                        if (selected == null)
                          _NoSelection(count: count)
                        else if (_tab == 0)
                          ..._fields(context, model, selected)
                        else
                          ..._interaction(context, model, selected),
                        if (selected == null || _tab == 0)
                          _AddSection(onAdd: model.addElement),
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }

  List<SuggestOption> _effectOptions(EditorModel model) => [
        for (final effect in model.effects)
          (
            value: effect.id,
            label: effect.name == effect.id ? effect.id : '${effect.id} · ${effect.name}',
            detail: effect.summary,
          ),
      ];

  List<Widget> _interaction(BuildContext context, EditorModel model, Element element) {
    final lock = model.lockReason(element.id);
    final editable = lock == null && !model.isPreviewing;
    final it = element.interaction;

    void set(String path, String value) => model.patchOne(element.id, path, value);

    return [
      if (lock != null) _LockBanner(reason: lock),
      if (!it.actionable && element.type != 'HITBOX')
        const _Hint(
          'Nothing is bound yet, so this element lets hovers and clicks pass through to '
          'whatever is behind it.',
        ),
      Section(
        title: 'On click',
        children: [
          _ActionField(
            value: it.onClick,
            enabled: editable,
            onCommit: (v) => set('interaction.onClick', v),
          ),
          const _Hint('One action per line, as `verb argument`. For example `sound shadr.click`.'),
        ],
      ),
      Section(
        title: 'On left click',
        children: [
          _ActionField(
            value: it.onLeftClick,
            enabled: editable,
            onCommit: (v) => set('interaction.onLeftClick', v),
          ),
        ],
      ),
      Section(
        title: 'On right click',
        children: [
          _ActionField(
            value: it.onRightClick,
            enabled: editable,
            onCommit: (v) => set('interaction.onRightClick', v),
          ),
        ],
      ),
      Section(
        title: 'Feedback',
        children: [
          PropertyRow(
            label: 'Hover text',
            child: ValueField(
              value: it.hoverText,
              enabled: editable,
              hint: 'shown on hover',
              onCommit: (v) => set('interaction.hoverText', v),
            ),
          ),
          PropertyRow(
            label: 'Hover fx',
            child: SuggestField(
              value: it.hoverEffect,
              enabled: editable,
              hint: 'none',
              options: _effectOptions(model),
              emptyHint: 'no effects in effects/ yet',
              onCommit: (v) => set('interaction.hoverEffect', v),
            ),
          ),
          PropertyRow(
            label: 'Click fx',
            child: SuggestField(
              value: it.clickEffect,
              enabled: editable,
              hint: 'none',
              options: _effectOptions(model),
              emptyHint: 'no effects in effects/ yet',
              onCommit: (v) => set('interaction.clickEffect', v),
            ),
          ),
        ],
      ),
      Section(
        title: 'Hitbox',
        children: [
          PropertyRow(
            label: 'Enabled',
            child: ChoiceField<bool>(
              value: it.interactive && !it.disableHitbox,
              enabled: editable,
              options: const [
                (value: true, icon: Icons.touch_app, label: 'On'),
                (value: false, icon: Icons.do_not_touch, label: 'Off'),
              ],
              onChanged: (v) => set('interaction.disableHitbox', '${!v}'),
            ),
          ),
          PropertyRow(
            label: 'Offset',
            child: Row(
              children: [
                Expanded(
                  child: ScrubField(
                    label: 'x',
                    value: 0,
                    enabled: editable,
                    onChanged: (v) => set('interaction.hitboxOffsetX', '$v'),
                  ),
                ),
                const SizedBox(width: Insets.sm),
                Expanded(
                  child: ScrubField(
                    label: 'y',
                    value: 0,
                    enabled: editable,
                    onChanged: (v) => set('interaction.hitboxOffsetY', '$v'),
                  ),
                ),
              ],
            ),
          ),
          PropertyRow(
            label: 'Permission',
            child: ValueField(
              value: it.permission,
              enabled: editable,
              hint: 'none',
              onCommit: (v) => set('interaction.permission', v),
            ),
          ),
        ],
      ),
    ];
  }

  List<Widget> _fields(BuildContext context, EditorModel model, Element element) {
    final lock = model.lockReason(element.id);
    final editable = lock == null && !model.isPreviewing;

    void set(String path, String value, {bool continuous = false}) => model.patchSelection(
          path,
          value,
          gesture: continuous ? 'field:$path:${element.id}' : null,
        );

    return [
      if (lock != null) _LockBanner(reason: lock),
      if (model.isPreviewing) const _PreviewBanner(),
      if (element.isBlur && !model.frostedGlassEnabled) const _FrostedGlassBanner(),
      Section(
        title: 'Position',
        children: [
          PropertyRow(
            label: 'X / Y',
            child: Row(
              children: [
                Expanded(
                  child: ScrubField(
                    label: 'x',
                    value: element.x,
                    enabled: editable,
                    onChanged: (v) => set('position.x', '$v', continuous: true),
                  ),
                ),
                const SizedBox(width: Insets.sm),
                Expanded(
                  child: ScrubField(
                    label: 'y',
                    value: element.y,
                    enabled: editable,
                    onChanged: (v) => set('position.y', '$v', continuous: true),
                  ),
                ),
              ],
            ),
          ),
          PropertyRow(
            label: 'Layer',
            child: ScrubField(
              label: 'z',
              value: element.effectiveLayer,
              enabled: editable && !element.isBlur,
              step: 0.1,
              onChanged: (v) => set('layer', '$v', continuous: true),
            ),
          ),
          if (element.isBlur)
            const _Hint(
              'Blur panels are pinned to a reserved layer behind every other element, so this '
              'is fixed. Only what is behind the HUD gets blurred.',
            ),
          PropertyRow(
            label: 'Rotation',
            child: ScrubField(
              label: '°',
              value: element.rotationDeg,
              enabled: editable,
              onChanged: (v) => set('rotationDeg', '$v', continuous: true),
            ),
          ),
        ],
      ),
      Section(
        title: element.isText ? 'Font scale' : 'Size',
        children: [
          PropertyRow(
            label: element.isText ? 'Scale' : 'W / H',
            child: Row(
              children: [
                Expanded(
                  child: ScrubField(
                    label: 'w',
                    value: element.width,
                    enabled: editable,
                    min: 1,
                    onChanged: (v) => set('size.width', '$v', continuous: true),
                  ),
                ),
                const SizedBox(width: Insets.sm),
                Expanded(
                  child: ScrubField(
                    label: 'h',
                    value: element.height,
                    enabled: editable,
                    min: 1,
                    onChanged: (v) => set('size.height', '$v', continuous: true),
                  ),
                ),
              ],
            ),
          ),
          if (element.isText) const _Hint('64 is 1×. This scales the font; it does not size a box.'),
        ],
      ),
      Section(
        title: 'Appearance',
        children: [
          PropertyRow(
            label: 'Colour',
            child: ColorField(
              value: element.color,
              enabled: editable,
              onCommit: (v) => set('color', v),
            ),
          ),
          PropertyRow(
            label: 'Opacity',
            child: ScrubField(
              label: 'α',
              value: element.opacity.toDouble(),
              enabled: editable,
              min: 0,
              max: 255,
              onChanged: (v) => set('opacity', '${v.toInt()}', continuous: true),
            ),
          ),
        ],
      ),
      if (element.isText)
        Section(
          title: 'Text',
          children: [
            PropertyRow(
              label: 'Content',
              child: ValueField(
                value: element.text,
                enabled: editable,
                onCommit: (v) => set('text', v),
              ),
            ),
            PropertyRow(
              label: 'Align',
              child: ChoiceField<String>(
                value: element.textAlign,
                enabled: editable,
                options: const [
                  (value: 'LEFT', icon: Icons.format_align_left, label: 'Left'),
                  (value: 'CENTER', icon: Icons.format_align_center, label: 'Centre'),
                  (value: 'RIGHT', icon: Icons.format_align_right, label: 'Right'),
                ],
                onChanged: (v) => set('textAlign', v.toLowerCase()),
              ),
            ),
            PropertyRow(
              label: 'Font',
              child: ValueField(
                value: element.font,
                enabled: editable,
                onCommit: (v) => set('font', v),
              ),
            ),
          ],
        ),
      if (element.supportsRounding)
        Section(
          title: 'Rounding',
          children: [
            PropertyRow(
              label: 'Preset',
              child: ChoiceField<String>(
                value: element.rounding?.size.toUpperCase() ?? 'REGULAR',
                enabled: editable,
                options: const [
                  (value: 'NONE', icon: null, label: 'None'),
                  (value: 'SMALL', icon: null, label: 'S'),
                  (value: 'MEDIUM', icon: null, label: 'M'),
                  (value: 'REGULAR', icon: null, label: 'R'),
                  (value: 'LARGE', icon: null, label: 'L'),
                ],
                onChanged: (v) => set('rounding.size', v.toLowerCase()),
              ),
            ),
            PropertyRow(
              label: 'Radius',
              child: ValueField(
                value: element.rounding?.radius?.toString() ?? '',
                enabled: editable,
                hint: 'from preset',
                onCommit: (v) => set('rounding.radius', v),
              ),
            ),
          ],
        ),
      Section(
        title: 'Outline',
        children: [
          PropertyRow(
            label: 'Width',
            child: ScrubField(
              label: 'o',
              value: element.outline?.size ?? 0,
              enabled: editable,
              min: 0,
              onChanged: (v) => set('outline.size', '$v', continuous: true),
            ),
          ),
          if ((element.outline?.size ?? 0) > 0)
            PropertyRow(
              label: 'Colour',
              child: ColorField(
                value: element.outline?.color ?? 0xFFFFFF,
                enabled: editable,
                onCommit: (v) => set('outline.color', v),
              ),
            ),
        ],
      ),
    ];
  }
}

class _Tabs extends StatelessWidget {
  const _Tabs({required this.labels, required this.selected, required this.onSelected});

  final List<String> labels;
  final int selected;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Container(
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: tokens.border)),
      ),
      child: Row(
        children: [
          for (final (index, label) in labels.indexed)
            Expanded(
              child: InkWell(
                onTap: () => onSelected(index),
                child: Container(
                  height: 28,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                        color: index == selected ? tokens.accent : Colors.transparent,
                        width: 2,
                      ),
                    ),
                  ),
                  child: Text(
                    label,
                    style: context.texts.bodySmall?.copyWith(
                      color: index == selected ? tokens.accent : tokens.textSecondary,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _ActionField extends StatelessWidget {
  const _ActionField({required this.value, required this.enabled, required this.onCommit});

  final List<String> value;
  final bool enabled;
  final ValueChanged<String> onCommit;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: Insets.md),
        child: MultilineField(
          value: value.join('\n'),
          enabled: enabled,
          hint: 'nothing bound',
          onCommit: onCommit,
        ),
      );
}

class _NoSelection extends StatelessWidget {
  const _NoSelection({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: Insets.lg),
        child: EmptyState(
          icon: count > 1 ? Icons.select_all : Icons.highlight_alt_outlined,
          title: count > 1 ? '$count elements selected' : 'Nothing selected',
          detail: count > 1
              ? 'Edits apply to all of them. Select one to see its properties.'
              : 'Click an element on the canvas, or drag a box around several.',
        ),
      );
}

class _AddSection extends StatelessWidget {
  const _AddSection({required this.onAdd});

  final ValueChanged<String> onAdd;

  static const _types = <({String id, IconData icon, String label})>[
    (id: 'block', icon: Icons.crop_square, label: 'Block'),
    (id: 'circle', icon: Icons.circle_outlined, label: 'Circle'),
    (id: 'progress', icon: Icons.remove, label: 'Bar'),
    (id: 'gradient', icon: Icons.gradient, label: 'Gradient'),
    (id: 'blur', icon: Icons.blur_on, label: 'Blur'),
    (id: 'text', icon: Icons.text_fields, label: 'Text'),
    (id: 'hitbox', icon: Icons.crop_free, label: 'Hitbox'),
  ];

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Section(
      title: 'Add',
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: Insets.md),
          child: Wrap(
            spacing: Insets.sm,
            runSpacing: Insets.sm,
            children: [
              for (final type in _types)
                OutlinedButton.icon(
                  onPressed: () => onAdd(type.id),
                  icon: Icon(type.icon, size: 13),
                  label: Text(type.label, style: context.texts.bodySmall),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: tokens.textSecondary,
                    side: BorderSide(color: tokens.border),
                    padding: const EdgeInsets.symmetric(horizontal: Insets.sm, vertical: Insets.xs),
                    shape: const RoundedRectangleBorder(borderRadius: Corners.small),
                    minimumSize: Size.zero,
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(Insets.md, 0, Insets.md, Insets.sm),
        child: Text(
          text,
          style: context.texts.bodySmall?.copyWith(color: context.tokens.textTertiary),
        ),
      );
}

class _LockBanner extends StatelessWidget {
  const _LockBanner({required this.reason});

  final String reason;

  @override
  Widget build(BuildContext context) => _Banner(
        icon: Icons.link,
        colour: context.tokens.attention,
        text: reason,
      );
}

class _FrostedGlassBanner extends StatelessWidget {
  const _FrostedGlassBanner();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;
    return Container(
      margin: const EdgeInsets.fromLTRB(Insets.md, Insets.md, Insets.md, 0),
      padding: const EdgeInsets.all(Insets.sm),
      decoration: BoxDecoration(
        color: tokens.attention.withValues(alpha: 0.1),
        borderRadius: Corners.small,
        border: Border.all(color: tokens.attention.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.blur_off, size: 12, color: tokens.attention),
              const SizedBox(width: Insets.sm),
              Expanded(
                child: Text(
                  'The frosted glass post chain is not in the pack, so this panel will not '
                  'blur anything in game.',
                  style: context.texts.bodySmall?.copyWith(color: tokens.attention),
                ),
              ),
            ],
          ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () {
                model
                  ..setEnvironment('blur', true)
                  ..setWorkspace(Workspace.shaders);
              },
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: Insets.sm),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text('Ship it', style: context.texts.bodySmall),
            ),
          ),
        ],
      ),
    );
  }
}

class _PreviewBanner extends StatelessWidget {
  const _PreviewBanner();

  @override
  Widget build(BuildContext context) => _Banner(
        icon: Icons.movie_filter_outlined,
        colour: context.tokens.attention,
        text: 'Previewing an animation frame. Reset the playhead to edit.',
      );
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.colour, required this.text});

  final IconData icon;
  final Color colour;
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.fromLTRB(Insets.md, Insets.md, Insets.md, 0),
        padding: const EdgeInsets.all(Insets.sm),
        decoration: BoxDecoration(
          color: colour.withValues(alpha: 0.1),
          borderRadius: Corners.small,
          border: Border.all(color: colour.withValues(alpha: 0.3)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 12, color: colour),
            const SizedBox(width: Insets.sm),
            Expanded(
              child: Text(text, style: context.texts.bodySmall?.copyWith(color: colour)),
            ),
          ],
        ),
      );
}

class _Issues extends StatelessWidget {
  const _Issues({required this.issues});

  final List<String> issues;

  @override
  Widget build(BuildContext context) => Section(
        title: 'Issues',
        children: [
          for (final issue in issues)
            Padding(
              padding: const EdgeInsets.fromLTRB(Insets.md, 0, Insets.md, Insets.xs),
              child: Text(
                issue,
                style: context.texts.bodySmall?.copyWith(color: context.tokens.attention),
              ),
            ),
        ],
      );
}
