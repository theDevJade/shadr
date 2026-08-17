import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';

import 'theme.dart';

class ValueField extends StatefulWidget {
  const ValueField({
    super.key,
    required this.value,
    required this.onCommit,
    this.enabled = true,
    this.leading,
    this.hint,
  });

  final String value;
  final ValueChanged<String> onCommit;
  final bool enabled;

  final Widget? leading;
  final String? hint;

  @override
  State<ValueField> createState() => _ValueFieldState();
}

class _ValueFieldState extends State<ValueField> {
  late final TextEditingController _controller = TextEditingController(text: widget.value);
  late final FocusNode _focus = FocusNode()..addListener(_onFocusChange);

  @override
  void didUpdateWidget(ValueField old) {
    super.didUpdateWidget(old);
    if (!_focus.hasFocus && widget.value != _controller.text) {
      _controller.text = widget.value;
    }
  }

  void _onFocusChange() {
    if (!_focus.hasFocus) _commit();
  }

  void _commit() {
    if (_controller.text != widget.value) widget.onCommit(_controller.text);
  }

  @override
  void dispose() {
    _focus.removeListener(_onFocusChange);
    _focus.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return SizedBox(
      height: Metrics.fieldHeight,
      child: TextField(
        controller: _controller,
        focusNode: _focus,
        enabled: widget.enabled,
        onSubmitted: (_) => _commit(),
        style: context.texts.bodyMedium,
        cursorWidth: 1,
        cursorColor: tokens.accent,
        decoration: InputDecoration(
          hintText: widget.hint,
          hintStyle: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
          isDense: true,
          filled: true,
          fillColor: widget.enabled ? tokens.surfaceSunken : tokens.surface,
          contentPadding: EdgeInsets.only(
            left: widget.leading == null ? Insets.sm : 0,
            right: Insets.sm,
          ),
          prefixIcon: widget.leading == null
              ? null
              : SizedBox(
                  width: Metrics.fieldGutter,
                  height: Metrics.fieldHeight,
                  child: Center(child: widget.leading),
                ),
          prefixIconConstraints: const BoxConstraints(minWidth: 0, minHeight: 0),
          border: _border(tokens.border),
          enabledBorder: _border(tokens.border),
          focusedBorder: _border(tokens.accent),
          disabledBorder: _border(tokens.border),
        ),
      ),
    );
  }

  OutlineInputBorder _border(Color color) => OutlineInputBorder(
        borderRadius: Corners.small,
        borderSide: BorderSide(color: color),
      );
}

class ScrubField extends StatefulWidget {
  const ScrubField({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
    this.onGestureEnd,
    this.enabled = true,
    this.step = 1,
    this.min,
    this.max,
  });

  final String label;
  final double value;

  final ValueChanged<double> onChanged;

  final VoidCallback? onGestureEnd;

  final bool enabled;

  final double step;
  final double? min;
  final double? max;

  @override
  State<ScrubField> createState() => _ScrubFieldState();
}

class _ScrubFieldState extends State<ScrubField> {
  double _accumulated = 0;
  bool _scrubbing = false;

  void _begin() {
    _accumulated = widget.value;
    setState(() => _scrubbing = true);
  }

  void _update(double dx) {
    final scale = HardwareKeyboard.instance.isShiftPressed ? 0.1 : 1.0;
    _accumulated += dx * widget.step * scale;
    var next = _accumulated.roundToDouble();
    if (widget.min != null) next = next.clamp(widget.min!, double.infinity);
    if (widget.max != null) next = next.clamp(double.negativeInfinity, widget.max!);
    if (next != widget.value) widget.onChanged(next);
  }

  void _end() {
    setState(() => _scrubbing = false);
    widget.onGestureEnd?.call();
  }

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return ValueField(
      value: _format(widget.value),
      enabled: widget.enabled,
      leading: MouseRegion(
        cursor: widget.enabled ? SystemMouseCursors.resizeLeftRight : SystemMouseCursors.basic,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragStart: widget.enabled ? (_) => _begin() : null,
          onHorizontalDragUpdate: widget.enabled ? (d) => _update(d.delta.dx) : null,
          onHorizontalDragEnd: widget.enabled ? (_) => _end() : null,
          child: SizedBox(
            width: Metrics.fieldGutter,
            height: Metrics.fieldHeight,
            child: Center(
              child: Text(
                widget.label,
                style: context.texts.bodySmall?.copyWith(
                  color: !widget.enabled
                      ? tokens.textTertiary.withValues(alpha: 0.4)
                      : _scrubbing
                          ? tokens.accent
                          : tokens.textTertiary,
                ),
              ),
            ),
          ),
        ),
      ),
      onCommit: (raw) {
        final parsed = double.tryParse(raw.trim());
        if (parsed != null) widget.onChanged(parsed);
        widget.onGestureEnd?.call();
      },
    );
  }

  static String _format(double value) =>
      value == value.roundToDouble() ? '${value.toInt()}' : '$value';
}

class ColorField extends StatelessWidget {
  const ColorField({
    super.key,
    required this.value,
    required this.onCommit,
    this.enabled = true,
  });

  final int value;
  final ValueChanged<String> onCommit;
  final bool enabled;

  @override
  Widget build(BuildContext context) => ValueField(
        value: value.toRadixString(16).padLeft(6, '0'),
        enabled: enabled,
        leading: _Swatch(color: Color(0xFF000000 | value), enabled: enabled, onPick: onCommit),
        onCommit: onCommit,
      );
}

class _Swatch extends StatelessWidget {
  const _Swatch({required this.color, required this.enabled, required this.onPick});

  final Color color;
  final bool enabled;
  final ValueChanged<String> onPick;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Tooltip(
      message: enabled ? 'Pick a colour' : 'Locked',
      child: InkWell(
        onTap: enabled ? () => _open(context) : null,
        borderRadius: Corners.small,
        child: Container(
          width: 14,
          height: 14,
          decoration: BoxDecoration(
            color: color,
            borderRadius: Corners.small,
            border: Border.all(color: tokens.borderStrong),
          ),
        ),
      ),
    );
  }

  Future<void> _open(BuildContext context) async {
    final picked = await showDialog<int>(
      context: context,
      builder: (context) => ColorPickerDialog(initial: color.toARGB32() & 0xFFFFFF),
    );
    if (picked != null) onPick(picked.toRadixString(16).padLeft(6, '0'));
  }
}

const colorPresets = <int>[
  0x080810, 0x15151C, 0x22222C, 0x3A3A46, 0x6B6B76, 0x9A9AA5, 0xE6E6EA, 0xFFFFFF,
  0x4CC9F0, 0x4361EE, 0x7209B7, 0xB5179E, 0xF72585, 0xF2545B, 0xE9A15C, 0x4CAF50,
];

class ColorPickerDialog extends StatefulWidget {
  const ColorPickerDialog({super.key, required this.initial});

  final int initial;

  @override
  State<ColorPickerDialog> createState() => _ColorPickerDialogState();
}

class _ColorPickerDialogState extends State<ColorPickerDialog> {
  late HSVColor _hsv = HSVColor.fromColor(Color(0xFF000000 | widget.initial));
  late final TextEditingController _hex =
      TextEditingController(text: _rgb.toRadixString(16).padLeft(6, '0'));

  int get _rgb => _hsv.toColor().toARGB32() & 0xFFFFFF;

  @visibleForTesting
  HSVColor get hsvForTest => _hsv;

  @visibleForTesting
  int get rgbForTest => _rgb;

  @override
  void dispose() {
    _hex.dispose();
    super.dispose();
  }

  void _set(HSVColor next, {bool syncHex = true}) {
    setState(() => _hsv = next);
    if (syncHex) _hex.text = _rgb.toRadixString(16).padLeft(6, '0');
  }

  void _typedHex(String raw) {
    final cleaned = raw.replaceAll('#', '').trim();
    final parsed = int.tryParse(cleaned, radix: 16);
    if (parsed == null || cleaned.length != 6) return;
    _set(HSVColor.fromColor(Color(0xFF000000 | parsed)), syncHex: false);
  }

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Dialog(
      backgroundColor: tokens.surfaceRaised,
      shape: const RoundedRectangleBorder(borderRadius: Corners.medium),
      child: SizedBox(
        width: 268,
        child: Padding(
          padding: const EdgeInsets.all(Insets.md),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _SaturationValueArea(
                hsv: _hsv,
                onChanged: (s, v) => _set(_hsv.withSaturation(s).withValue(v)),
              ),
              const SizedBox(height: Insets.md),
              _HueSlider(
                key: const ValueKey('hue-slider'),
                hue: _hsv.hue,
                onChanged: (h) => _set(_hsv.withHue(h)),
              ),
              const SizedBox(height: Insets.md),
              Row(
                children: [
                  Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      color: _hsv.toColor(),
                      borderRadius: Corners.small,
                      border: Border.all(color: tokens.borderStrong),
                    ),
                  ),
                  const SizedBox(width: Insets.sm),
                  Expanded(
                    child: SizedBox(
                      height: 28,
                      child: TextField(
                        controller: _hex,
                        onChanged: _typedHex,
                        onSubmitted: (_) => Navigator.of(context).pop(_rgb),
                        style: context.texts.bodyMedium,
                        cursorWidth: 1,
                        cursorColor: tokens.accent,
                        inputFormatters: [
                          FilteringTextInputFormatter.allow(RegExp('[0-9a-fA-F]')),
                          LengthLimitingTextInputFormatter(6),
                        ],
                        decoration: InputDecoration(
                          isDense: true,
                          filled: true,
                          fillColor: tokens.surfaceSunken,
                          prefixIcon: Padding(
                            padding: const EdgeInsets.only(left: Insets.sm, right: Insets.xs),
                            child: Text('#', style: context.texts.bodySmall),
                          ),
                          prefixIconConstraints: const BoxConstraints(minWidth: 0, minHeight: 0),
                          contentPadding: const EdgeInsets.symmetric(horizontal: Insets.sm),
                          border: _outline(tokens.border),
                          enabledBorder: _outline(tokens.border),
                          focusedBorder: _outline(tokens.accent),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: Insets.md),
              Wrap(
                spacing: Insets.xs,
                runSpacing: Insets.xs,
                children: [
                  for (final preset in colorPresets)
                    _Preset(
                      key: ValueKey('preset-$preset'),
                      value: preset,
                      selected: preset == _rgb,
                      onTap: () => _set(HSVColor.fromColor(Color(0xFF000000 | preset))),
                    ),
                ],
              ),
              const SizedBox(height: Insets.sm),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Cancel'),
                  ),
                  const SizedBox(width: Insets.xs),
                  FilledButton(
                    onPressed: () => Navigator.of(context).pop(_rgb),
                    child: const Text('Apply'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  OutlineInputBorder _outline(Color color) => OutlineInputBorder(
        borderRadius: Corners.small,
        borderSide: BorderSide(color: color),
      );
}

class _Preset extends StatelessWidget {
  const _Preset({
    super.key,
    required this.value,
    required this.selected,
    required this.onTap,
  });

  final int value;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return InkWell(
      onTap: onTap,
      borderRadius: Corners.small,
      child: Container(
        width: 24,
        height: 24,
        decoration: BoxDecoration(
          color: Color(0xFF000000 | value),
          borderRadius: Corners.small,
          border: Border.all(
            color: selected ? tokens.accent : tokens.borderStrong,
            width: selected ? 2 : 1,
          ),
        ),
      ),
    );
  }
}

class _SaturationValueArea extends StatelessWidget {
  const _SaturationValueArea({required this.hsv, required this.onChanged});

  final HSVColor hsv;
  final void Function(double saturation, double value) onChanged;

  static const _height = 148.0;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;

        void report(Offset local) => onChanged(
              (local.dx / width).clamp(0.0, 1.0),
              1 - (local.dy / _height).clamp(0.0, 1.0),
            );

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onPanDown: (d) => report(d.localPosition),
          onPanUpdate: (d) => report(d.localPosition),
          child: ClipRRect(
            borderRadius: Corners.small,
            child: SizedBox(
              width: width,
              height: _height,
              child: Stack(
                children: [
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            Colors.white,
                            HSVColor.fromAHSV(1, hsv.hue, 1, 1).toColor(),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [Colors.transparent, Colors.black],
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    left: hsv.saturation * width - _Handle.radius,
                    top: (1 - hsv.value) * _height - _Handle.radius,
                    child: const _Handle(),
                  ),
                  Positioned.fill(
                    child: IgnorePointer(
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          borderRadius: Corners.small,
                          border: Border.all(color: tokens.border),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _HueSlider extends StatelessWidget {
  const _HueSlider({super.key, required this.hue, required this.onChanged});

  final double hue;
  final ValueChanged<double> onChanged;

  static const _height = 14.0;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        void report(Offset local) => onChanged(((local.dx / width).clamp(0.0, 1.0)) * 360);

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onPanDown: (d) => report(d.localPosition),
          onPanUpdate: (d) => report(d.localPosition),
          child: SizedBox(
            width: width,
            height: _height,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fill(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      borderRadius: Corners.small,
                      border: Border.all(color: tokens.border),
                      gradient: LinearGradient(
                        colors: [
                          for (var stop = 0; stop <= 6; stop++)
                            HSVColor.fromAHSV(1, stop * 60.0, 1, 1).toColor(),
                        ],
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: (hue / 360) * width - _Handle.radius,
                  top: _height / 2 - _Handle.radius,
                  child: const _Handle(),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _Handle extends StatelessWidget {
  const _Handle();

  static const radius = 7.0;

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: Container(
          width: radius * 2,
          height: radius * 2,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(color: Colors.white, width: 2),
            boxShadow: const [BoxShadow(color: Colors.black45, blurRadius: 2)],
          ),
        ),
      );
}

class ChoiceField<T> extends StatelessWidget {
  const ChoiceField({
    super.key,
    required this.value,
    required this.options,
    required this.onChanged,
    this.enabled = true,
  });

  final T value;
  final List<({T value, IconData? icon, String label})> options;
  final ValueChanged<T> onChanged;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Container(
      height: 24,
      decoration: BoxDecoration(
        color: tokens.surfaceSunken,
        borderRadius: Corners.small,
        border: Border.all(color: tokens.border),
      ),
      child: Row(
        children: [
          for (final option in options)
            Expanded(
              child: _Segment(
                selected: option.value == value,
                enabled: enabled,
                icon: option.icon,
                label: option.label,
                onTap: () => onChanged(option.value),
              ),
            ),
        ],
      ),
    );
  }
}

class _Segment extends StatelessWidget {
  const _Segment({
    required this.selected,
    required this.enabled,
    required this.label,
    required this.onTap,
    this.icon,
  });

  final bool selected;
  final bool enabled;
  final String label;
  final IconData? icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final foreground = !enabled
        ? tokens.textTertiary.withValues(alpha: 0.4)
        : selected
            ? tokens.accent
            : tokens.textSecondary;
    return InkWell(
      onTap: enabled ? onTap : null,
      borderRadius: Corners.small,
      child: Tooltip(
        message: label,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: selected ? tokens.accentMuted : null,
            borderRadius: Corners.small,
          ),
          child: Center(
            child: icon != null
                ? Icon(icon, size: 13, color: foreground)
                : Text(label, style: context.texts.bodySmall?.copyWith(color: foreground)),
          ),
        ),
      ),
    );
  }
}
