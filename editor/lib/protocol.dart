library;

import 'dart:convert';
import 'dart:ui' show Rect;

const protocolVersion = 3;

const blurPanelLayer = -5000.0;

const tokenProtocolPrefix = 'shadr.token.';

class Endpoint {
  const Endpoint({required this.url, this.token});

  final String url;
  final String? token;

  List<String> get protocols => token == null ? const [] : ['$tokenProtocolPrefix$token'];

  static Endpoint from(Uri base) {
    final scheme = base.scheme == 'https' ? 'wss' : 'ws';
    final port = base.hasPort ? base.port : (base.scheme == 'https' ? 443 : 80);
    return Endpoint(
      url: '$scheme://${base.host.isEmpty ? 'localhost' : base.host}:$port/',
      token: base.queryParameters['token'],
    );
  }
}

typedef LockedElements = Map<String, String>;

class ScreenDef {
  const ScreenDef({
    required this.width,
    required this.height,
    this.offsetX = 0,
    this.offsetY = 0,
    this.hud = false,
    this.cursorSize = 10,
    this.cursorSpeed = 1,
    this.cursorLayer = 9700,
    this.hitboxOffsetX = 0,
    this.hitboxOffsetY = 0,
    this.cursorUnicode = '',
    this.previewDefaultZoom = 0.8,
  });

  final double width;
  final double height;
  final double offsetX;
  final double offsetY;

  final bool hud;

  final double cursorSize;
  final double cursorSpeed;
  final double cursorLayer;

  final double hitboxOffsetX;
  final double hitboxOffsetY;
  final String cursorUnicode;
  final double previewDefaultZoom;

  static ScreenDef fromJson(Map<String, dynamic> json) => ScreenDef(
        width: (json['width'] as num?)?.toDouble() ?? 1920,
        height: (json['height'] as num?)?.toDouble() ?? 1080,
        offsetX: (json['offsetX'] as num?)?.toDouble() ?? 0,
        offsetY: (json['offsetY'] as num?)?.toDouble() ?? 0,
        hud: (json['hud'] as bool?) ?? false,
        cursorSize: (json['cursorSize'] as num?)?.toDouble() ?? 10,
        cursorSpeed: (json['cursorSpeed'] as num?)?.toDouble() ?? 1,
        cursorLayer: (json['cursorLayer'] as num?)?.toDouble() ?? 9700,
        hitboxOffsetX: (json['hitboxOffsetX'] as num?)?.toDouble() ?? 0,
        hitboxOffsetY: (json['hitboxOffsetY'] as num?)?.toDouble() ?? 0,
        cursorUnicode: (json['cursorUnicode'] as String?) ?? '',
        previewDefaultZoom: (json['previewDefaultZoom'] as num?)?.toDouble() ?? 0.8,
      );
}

class Rounding {
  const Rounding({required this.size, this.radius, this.unicode});

  final String size;
  final double? radius;
  final String? unicode;

  static Rounding? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : Rounding(
          size: (json['size'] as String?) ?? 'REGULAR',
          radius: (json['radius'] as num?)?.toDouble(),
          unicode: json['unicode'] as String?,
        );

  static const bucketCount = 32;
  static const maxBucketFraction = 0.5;

  static double quantise(double radius, double width, double height) {
    final shorter = width < height ? width : height;
    if (shorter <= 0) return 0;
    final fraction = (radius / shorter).clamp(0.0, maxBucketFraction);
    final bucket = (fraction / maxBucketFraction * (bucketCount - 1)).round();
    return bucket * maxBucketFraction / (bucketCount - 1) * shorter;
  }

  double resolvedRadius(double width, double height) {
    final shorter = width < height ? width : height;
    final explicit = radius;
    if (explicit != null) return quantise(explicit.clamp(0.0, shorter / 2), width, height);
    final preset = switch (size.toUpperCase()) {
      'NONE' => 0.0,
      'SMALL' => 4.0,
      'MEDIUM' => 8.0,
      'LARGE' => 24.0,
      _ => 14.0,
    };
    return preset.clamp(0.0, shorter / 2);
  }
}

class Outline {
  const Outline({required this.size, required this.color, this.layer});

  final double size;
  final int color;
  final double? layer;

  static Outline? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : Outline(
          size: (json['size'] as num?)?.toDouble() ?? 0,
          color: _colorOf(json['color']),
          layer: (json['layer'] as num?)?.toDouble(),
        );
}

class RenderBox {
  const RenderBox(this.x, this.y, this.width, this.height, [this.rotationDeg = 0]);

  final double x;
  final double y;
  final double width;
  final double height;
  final double rotationDeg;

  Rect get rect => Rect.fromLTWH(x, y, width, height);

  static RenderBox? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : RenderBox(
          (json['x'] as num?)?.toDouble() ?? 0,
          (json['y'] as num?)?.toDouble() ?? 0,
          (json['width'] as num?)?.toDouble() ?? 0,
          (json['height'] as num?)?.toDouble() ?? 0,
          (json['rotationDeg'] as num?)?.toDouble() ?? 0,
        );
}

class ElementGeometry {
  const ElementGeometry({required this.render, required this.hit, this.takesInput = false});

  final RenderBox render;
  final RenderBox hit;
  final bool takesInput;

  static ElementGeometry? fromJson(Map<String, dynamic>? json) {
    if (json == null) return null;
    final render = RenderBox.fromJson(json['render'] as Map<String, dynamic>?);
    if (render == null) return null;
    return ElementGeometry(
      render: render,
      hit: RenderBox.fromJson(json['hit'] as Map<String, dynamic>?) ?? render,
      takesInput: (json['takesInput'] as bool?) ?? false,
    );
  }
}

class FontMetrics {
  const FontMetrics({
    required this.advance,
    required this.ascent,
    required this.descent,
    required this.lineHeight,
    this.advances = const {},
    this.coverage = const [],
  });

  final double advance;
  final double ascent;
  final double descent;
  final double lineHeight;
  final Map<int, double> advances;
  final List<List<int>> coverage;

  double advanceOf(int codepoint) => advances[codepoint] ?? advance;

  bool covers(int codepoint) =>
      advances.containsKey(codepoint) ||
      coverage.isEmpty ||
      coverage.any((r) => codepoint >= r[0] && codepoint <= r[1]);

  static FontMetrics fromJson(Map<String, dynamic> json) => FontMetrics(
        advance: (json['advance'] as num?)?.toDouble() ?? 6,
        ascent: (json['ascent'] as num?)?.toDouble() ?? 7,
        descent: (json['descent'] as num?)?.toDouble() ?? 2,
        lineHeight: (json['lineHeight'] as num?)?.toDouble() ?? 9,
        advances: ((json['advances'] as Map<String, dynamic>?) ?? const {}).map(
          (k, v) => MapEntry(int.parse(k), (v as num).toDouble()),
        ),
        coverage: ((json['coverage'] as List<dynamic>?) ?? const []).map((e) {
          final r = e as Map<String, dynamic>;
          return <int>[(r['from'] as num).toInt(), (r['to'] as num).toInt()];
        }).toList(),
      );
}

class MetricsTable {
  const MetricsTable({this.fonts = const {}, this.missingGlyphAdvance = 6});

  final Map<String, FontMetrics> fonts;
  final double missingGlyphAdvance;

  static const scaleUnit = 64.0;

  static const empty = MetricsTable();

  static const _fallback = FontMetrics(advance: 6, ascent: 7, descent: 2, lineHeight: 9);

  FontMetrics font(String name) => fonts[name] ?? fonts['shadr'] ?? _fallback;

  double designPerFontPixel(double scale) => scale / scaleUnit;

  double _advanceFor(FontMetrics metrics, int codepoint) =>
      metrics.covers(codepoint) ? metrics.advanceOf(codepoint) : missingGlyphAdvance;

  double advanceOf(String name, int codepoint) => _advanceFor(font(name), codepoint);

  bool covers(String name, int codepoint) => font(name).covers(codepoint);

  double measure(String name, String text) {
    final metrics = font(name);
    var total = 0.0;
    for (final codepoint in text.runes) {
      total += _advanceFor(metrics, codepoint);
    }
    return total;
  }

  List<String> wrap(String name, String text, int lineWidth) {
    final metrics = font(name);
    final limit = (lineWidth < 1 ? 1 : lineWidth).toDouble();
    final out = <String>[];
    for (final paragraph in text.split('\n')) {
      if (paragraph.isEmpty) {
        out.add('');
        continue;
      }
      var line = StringBuffer();
      var width = 0.0;
      var breakAt = -1;
      var widthAtBreak = 0.0;
      for (final codepoint in paragraph.runes) {
        final advance = _advanceFor(metrics, codepoint);
        if (width + advance > limit && line.length > 0) {
          final text = line.toString();
          if (breakAt >= 0) {
            out.add(text.substring(0, breakAt));
            line = StringBuffer(text.substring(breakAt + 1));
            width -= widthAtBreak;
          } else {
            out.add(text);
            line = StringBuffer();
            width = 0;
          }
          breakAt = -1;
          widthAtBreak = 0;
        }
        if (codepoint == 0x20) {
          // A break drops its space, so one starting a line is discarded, not carried.
          if (line.length == 0) continue;
          breakAt = line.length;
          widthAtBreak = width + advance;
        }
        line.writeCharCode(codepoint);
        width += advance;
      }
      out.add(line.toString());
    }
    return out;
  }

  static MetricsTable fromJson(Map<String, dynamic>? json) {
    if (json == null) return empty;
    return MetricsTable(
      fonts: ((json['fonts'] as Map<String, dynamic>?) ?? const {}).map(
        (k, v) => MapEntry(k, FontMetrics.fromJson(v as Map<String, dynamic>)),
      ),
      missingGlyphAdvance: (json['missingGlyphAdvance'] as num?)?.toDouble() ?? 6,
    );
  }
}

class TextInputDef {
  const TextInputDef({
    this.placeholder = '',
    this.value = '',
    this.maxLength = 60,
    this.lines = 1,
    this.secret = false,
    this.fontSize = 32,
    this.padding = 10,
  });

  final String placeholder;
  final String value;
  final int maxLength;
  final int lines;
  final bool secret;
  final double fontSize;
  final double padding;

  String display() {
    if (value.isEmpty) return placeholder;
    return secret ? '\u2022' * value.length : value;
  }

  static TextInputDef? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : TextInputDef(
          placeholder: (json['placeholder'] as String?) ?? '',
          value: (json['value'] as String?) ?? '',
          maxLength: (json['maxLength'] as num?)?.toInt() ?? 60,
          lines: (json['lines'] as num?)?.toInt() ?? 1,
          secret: (json['secret'] as bool?) ?? false,
          fontSize: (json['fontSize'] as num?)?.toDouble() ?? 32,
          padding: (json['padding'] as num?)?.toDouble() ?? 10,
        );
}

class ToggleDef {
  const ToggleDef({
    this.value = false,
    this.onColor = 0x4C8DFF,
    this.offColor = 0x3A3A47,
    this.knobColor = 0xF2F2F7,
  });

  final bool value;
  final int onColor;
  final int offColor;
  final int knobColor;

  int trackColor() => value ? onColor : offColor;

  static ToggleDef? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : ToggleDef(
          value: (json['value'] as bool?) ?? false,
          onColor: _colorOf(json['onColor']),
          offColor: _colorOf(json['offColor']),
          knobColor: _colorOf(json['knobColor']),
        );
}

class SliderDef {
  const SliderDef({
    this.value = 0,
    this.min = 0,
    this.max = 100,
    this.step = 0,
    this.trackColor = 0x3A3A47,
    this.fillColor = 0x4C8DFF,
    this.knobColor = 0xF2F2F7,
  });

  final double value;
  final double min;
  final double max;
  final double step;
  final int trackColor;
  final int fillColor;
  final int knobColor;

  double get fraction {
    final span = (max - min).abs() < 1e-9 ? 1.0 : max - min;
    return ((value - min) / span).clamp(0.0, 1.0);
  }

  static SliderDef? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : SliderDef(
          value: (json['value'] as num?)?.toDouble() ?? 0,
          min: (json['min'] as num?)?.toDouble() ?? 0,
          max: (json['max'] as num?)?.toDouble() ?? 100,
          step: (json['step'] as num?)?.toDouble() ?? 0,
          trackColor: _colorOf(json['trackColor']),
          fillColor: _colorOf(json['fillColor']),
          knobColor: _colorOf(json['knobColor']),
        );
}

class Interaction {
  const Interaction({
    this.interactive = true,
    this.disableHitbox = false,
    this.hoverText = '',
    this.hoverEffect = '',
    this.clickEffect = '',
    this.permission = '',
    this.onClick = const [],
    this.onLeftClick = const [],
    this.onRightClick = const [],
    this.hitboxOffsetX = 0,
    this.hitboxOffsetY = 0,
  });

  final double hitboxOffsetX;
  final double hitboxOffsetY;

  final bool interactive;
  final bool disableHitbox;
  final String hoverText;
  final String hoverEffect;
  final String clickEffect;
  final String permission;
  final List<String> onClick;
  final List<String> onLeftClick;
  final List<String> onRightClick;

  bool get actionable =>
      onClick.isNotEmpty ||
      onLeftClick.isNotEmpty ||
      onRightClick.isNotEmpty ||
      hoverText.isNotEmpty ||
      hoverEffect.isNotEmpty ||
      clickEffect.isNotEmpty;

  static List<String> _actions(dynamic raw) => ((raw as List<dynamic>?) ?? const [])
      .map((e) {
        final map = e as Map<String, dynamic>;
        final verb = (map['verb'] as String?) ?? '';
        final argument = (map['argument'] as String?) ?? '';
        return argument.isEmpty ? verb : '$verb $argument';
      })
      .where((line) => line.trim().isNotEmpty)
      .toList();

  static Interaction fromJson(Map<String, dynamic>? json) {
    if (json == null) return const Interaction();
    return Interaction(
      interactive: (json['interactive'] as bool?) ?? true,
      disableHitbox: (json['disableHitbox'] as bool?) ?? false,
      hoverText: (json['hoverText'] as String?) ?? '',
      hoverEffect: (json['hoverEffect'] as String?) ?? '',
      clickEffect: (json['clickEffect'] as String?) ?? '',
      permission: (json['permission'] as String?) ?? '',
      onClick: _actions(json['onClick']),
      onLeftClick: _actions(json['onLeftClick']),
      onRightClick: _actions(json['onRightClick']),
      hitboxOffsetX: (json['hitboxOffsetX'] as num?)?.toDouble() ?? 0,
      hitboxOffsetY: (json['hitboxOffsetY'] as num?)?.toDouble() ?? 0,
    );
  }
}

class Element {
  const Element({
    required this.id,
    required this.type,
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    required this.layer,
    required this.color,
    required this.opacity,
    required this.text,
    required this.font,
    required this.textAlign,
    required this.enabled,
    required this.rotationDeg,
    this.rounding,
    this.outline,
    this.componentName,
    this.item,
    this.stream = false,
    this.sourcePath = '',
    this.interaction = const Interaction(),
    this.unicode = '',
    this.align = 'CENTER',
    this.lineWidth = 200,
    this.mirrorX = false,
    this.mirrorY = false,
    this.pivotOffsetX = 0,
    this.pivotOffsetY = 0,
    this.itemCustomModelData,
    this.playerHeadText = false,
    this.input,
    this.toggle,
    this.slider,
  });

  final String id;
  final String type;
  final double x;
  final double y;
  final double width;
  final double height;
  final double layer;
  final int color;
  final int opacity;
  final String text;
  final String font;
  final String textAlign;
  final bool enabled;
  final double rotationDeg;
  final Rounding? rounding;
  final Outline? outline;
  final String? componentName;

  final String? item;

  final bool stream;

  final String sourcePath;

  final Interaction interaction;

  final String unicode;

  /// Anchors the element to a screen edge in game.
  final String align;

  final int lineWidth;

  final bool mirrorX;
  final bool mirrorY;
  final double pivotOffsetX;
  final double pivotOffsetY;
  final int? itemCustomModelData;
  final bool playerHeadText;

  final TextInputDef? input;
  final ToggleDef? toggle;
  final SliderDef? slider;

  String? get parentPath {
    for (final separator in const ['.children.', '.grid/']) {
      final cut = sourcePath.lastIndexOf(separator);
      if (cut > 0) return sourcePath.substring(0, cut);
    }
    return null;
  }

  bool get isText => type == 'TEXT';
  bool get isHitbox => type == 'HITBOX';

  bool get isBlur => type == 'BLUR';

  bool get isTextInput => type == 'TEXT_INPUT';

  bool get isControl => isTextInput || type == 'TOGGLE' || type == 'SLIDER';

  double get effectiveLayer => isBlur ? blurPanelLayer : layer;

  bool get supportsRounding =>
      type == 'BLOCK' || type == 'BLOCK_ROUNDED' || type == 'BLOCK_SDF' || isControl;

  bool get isRounded =>
      supportsRounding && (rounding?.resolvedRadius(width, height) ?? 0) > 0;

  Rect get bounds => Rect.fromLTWH(x, y, width, height);

  static Element fromJson(Map<String, dynamic> json) => Element(
        id: json['id'] as String,
        type: (json['type'] as String?) ?? 'BLOCK',
        x: (json['x'] as num?)?.toDouble() ?? 0,
        y: (json['y'] as num?)?.toDouble() ?? 0,
        width: (json['width'] as num?)?.toDouble() ?? 20,
        height: (json['height'] as num?)?.toDouble() ?? 20,
        layer: (json['layer'] as num?)?.toDouble() ?? 0,
        color: _colorOf(json['color']),
        opacity: (json['opacity'] as num?)?.toInt() ?? 255,
        text: (json['text'] as String?) ?? '',
        font: (json['font'] as String?) ?? 'shadr',
        textAlign: (json['textAlignment'] as String?) ?? 'CENTER',
        enabled: (json['enabled'] as bool?) ?? true,
        rotationDeg: (json['rotationDeg'] as num?)?.toDouble() ?? 0,
        rounding: Rounding.fromJson(json['rounding'] as Map<String, dynamic>?),
        outline: Outline.fromJson(json['outline'] as Map<String, dynamic>?),
        componentName: json['componentName'] as String?,
        item: json['item'] as String?,
        stream: (json['stream'] as bool?) ?? false,
        sourcePath: (json['sourcePath'] as String?) ?? '',
        interaction: Interaction.fromJson(json['interaction'] as Map<String, dynamic>?),
        unicode: (json['unicode'] as String?) ?? '',
        align: (json['hudAlignment'] as String?) ?? 'CENTER',
        lineWidth: (json['lineWidth'] as num?)?.toInt() ?? 200,
        mirrorX: (json['mirrorX'] as bool?) ?? false,
        mirrorY: (json['mirrorY'] as bool?) ?? false,
        pivotOffsetX: (json['pivotOffsetX'] as num?)?.toDouble() ?? 0,
        pivotOffsetY: (json['pivotOffsetY'] as num?)?.toDouble() ?? 0,
        itemCustomModelData: (json['itemCustomModelData'] as num?)?.toInt(),
        playerHeadText: (json['playerHeadText'] as bool?) ?? false,
        input: TextInputDef.fromJson(json['input'] as Map<String, dynamic>?),
        toggle: ToggleDef.fromJson(json['toggle'] as Map<String, dynamic>?),
        slider: SliderDef.fromJson(json['slider'] as Map<String, dynamic>?),
      );
}

int _colorOf(dynamic value) {
  if (value is Map<String, dynamic>) return (value['packed'] as num?)?.toInt() ?? 0xFFFFFF;
  if (value is num) return value.toInt();
  return 0xFFFFFF;
}

enum DocumentKind { page, component }

class DocumentRef {
  const DocumentRef({required this.name, required this.kind});

  final String name;
  final DocumentKind kind;

  static DocumentRef fromJson(Map<String, dynamic> json) => DocumentRef(
        name: json['name'] as String,
        kind: (json['kind'] as String?) == 'COMPONENT' ? DocumentKind.component : DocumentKind.page,
      );

  String get wireKind => kind == DocumentKind.component ? 'COMPONENT' : 'PAGE';

  @override
  bool operator ==(Object other) =>
      other is DocumentRef && other.name == name && other.kind == kind;

  @override
  int get hashCode => Object.hash(name, kind);
}

class AnimationStep {
  const AnimationStep({
    required this.target,
    required this.axis,
    required this.easing,
    required this.from,
    required this.to,
    required this.duration,
  });

  final String target;
  final String axis;

  final String easing;
  final double from;
  final double to;
  final int duration;

  static const easings = [
    'linear',
    'ease-in',
    'ease-out',
    'ease-in-out',
    'smooth-in',
    'smooth-out',
    'smooth',
  ];

  static AnimationStep fromJson(Map<String, dynamic> json) => AnimationStep(
        target: json['target'] as String,
        axis: (json['axis'] as String?) ?? 'y',
        easing: _easingOf(json['easing']),
        from: (json['from'] as num?)?.toDouble() ?? 0,
        to: (json['to'] as num?)?.toDouble() ?? 0,
        duration: (json['durationTicks'] as num?)?.toInt() ?? 0,
      );
}

String _easingOf(dynamic value) {
  final raw = (value as String?)?.toLowerCase().replaceAll('_', '-') ?? 'linear';
  return AnimationStep.easings.contains(raw) ? raw : 'linear';
}

class AnimationDef {
  const AnimationDef({required this.name, required this.duration, required this.steps});

  final String name;
  final int duration;
  final List<AnimationStep> steps;

  static AnimationDef fromJson(Map<String, dynamic> json) => AnimationDef(
        name: json['name'] as String,
        duration: (json['durationTicks'] as num?)?.toInt() ?? 20,
        steps: ((json['steps'] as List<dynamic>?) ?? const [])
            .map((e) => AnimationStep.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

Map<String, ElementGeometry> _geometryOf(dynamic raw) {
  final out = <String, ElementGeometry>{};
  for (final entry in ((raw as Map<String, dynamic>?) ?? const {}).entries) {
    final geometry = ElementGeometry.fromJson(entry.value as Map<String, dynamic>?);
    if (geometry != null) out[entry.key] = geometry;
  }
  return out;
}

class PageSnapshot {
  const PageSnapshot({
    required this.name,
    required this.screen,
    required this.elements,
    required this.issues,
    required this.locked,
    required this.canUndo,
    required this.canRedo,
    required this.dirty,
    required this.animations,
    required this.previewTick,
    required this.kind,
    this.geometry = const {},
    this.metrics = MetricsTable.empty,
    this.actionVerbs = const [],
  });

  final String name;
  final ScreenDef screen;
  final List<Element> elements;
  final List<String> issues;
  final LockedElements locked;
  final bool canUndo;
  final bool canRedo;
  final bool dirty;
  final List<AnimationDef> animations;
  final int? previewTick;
  final DocumentKind kind;

  final Map<String, ElementGeometry> geometry;

  final MetricsTable metrics;

  final List<String> actionVerbs;

  ElementGeometry? geometryOf(String id) => geometry[id];

  Rect renderRectOf(Element element) =>
      geometry[element.id]?.render.rect ?? element.bounds;

  Rect hitRectOf(Element element) => geometry[element.id]?.hit.rect ?? element.bounds;

  double rotationOf(Element element) =>
      geometry[element.id]?.render.rotationDeg ?? element.rotationDeg;

  static PageSnapshot fromJson(Map<String, dynamic> json) => PageSnapshot(
        name: json['name'] as String,
        screen: ScreenDef.fromJson(json['screen'] as Map<String, dynamic>),
        elements: (json['elements'] as List<dynamic>)
            .map((e) => Element.fromJson(e as Map<String, dynamic>))
            .toList(),
        issues: ((json['issues'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        locked: ((json['locked'] as Map<String, dynamic>?) ?? const {})
            .map((k, v) => MapEntry(k, v.toString())),
        geometry: _geometryOf(json['geometry']),
        metrics: MetricsTable.fromJson(json['metrics'] as Map<String, dynamic>?),
        actionVerbs: ((json['actionVerbs'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        canUndo: (json['canUndo'] as bool?) ?? false,
        canRedo: (json['canRedo'] as bool?) ?? false,
        dirty: (json['dirty'] as bool?) ?? false,
        animations: ((json['animations'] as List<dynamic>?) ?? const [])
            .map((e) => AnimationDef.fromJson(e as Map<String, dynamic>))
            .toList(),
        previewTick: (json['previewTick'] as num?)?.toInt(),
        kind: (json['kind'] as String?) == 'COMPONENT' ? DocumentKind.component : DocumentKind.page,
      );
}

String openDocument(DocumentRef ref) =>
    jsonEncode({'t': 'open', 'name': ref.name, 'kind': ref.wireKind});

String newDocument(
  DocumentRef ref, {
  bool hud = false,
  double width = 1920,
  double height = 1080,
}) =>
    jsonEncode({
      't': 'newDocument',
      'name': ref.name,
      'kind': ref.wireKind,
      'hud': hud,
      'width': width,
      'height': height,
    });

String deleteDocument(DocumentRef ref) =>
    jsonEncode({'t': 'deleteDocument', 'name': ref.name, 'kind': ref.wireKind});

String renameDocument(DocumentRef ref, String to) =>
    jsonEncode({'t': 'renameDocument', 'name': ref.name, 'kind': ref.wireKind, 'to': to});

String duplicateDocument(DocumentRef ref, String to) =>
    jsonEncode({'t': 'duplicateDocument', 'name': ref.name, 'kind': ref.wireKind, 'to': to});

String patchScreen(Map<String, String> changes, {String? gesture}) =>
    jsonEncode({'t': 'patchScreen', 'changes': changes, 'gesture': gesture});

String patchElement(String elementId, Map<String, String> changes, {String? gesture}) =>
    jsonEncode({'t': 'patch', 'elementId': elementId, 'changes': changes, 'gesture': gesture});

String patchElements(Map<String, Map<String, String>> edits, {String? gesture}) =>
    jsonEncode({'t': 'patchAll', 'edits': edits, 'gesture': gesture});

String undoEdit() => jsonEncode({'t': 'undo'});

String redoEdit() => jsonEncode({'t': 'redo'});

String addElement(String type, double x, double y, {double width = 120, double height = 40}) =>
    jsonEncode({'t': 'add', 'type': type, 'x': x, 'y': y, 'width': width, 'height': height});

String deleteElements(List<String> elementIds) =>
    jsonEncode({'t': 'delete', 'elementIds': elementIds});

String reloadPage() => jsonEncode({'t': 'reload'});

String savePage() => jsonEncode({'t': 'save'});

String scrub(int? tick) => jsonEncode({'t': 'scrub', 'tick': tick});

String setAnimationStep({
  required String animation,
  required String target,
  required String axis,
  required double from,
  required double to,
  required int duration,
  String easing = 'linear',
}) =>
    jsonEncode({
      't': 'setStep',
      'animation': animation,
      'target': target,
      'axis': axis,
      'from': from,
      'to': to,
      'duration': duration,
      'easing': easing,
    });

String removeAnimationStep(String animation, String target, String axis) =>
    jsonEncode({'t': 'removeStep', 'animation': animation, 'target': target, 'axis': axis});

class SaveResult {
  const SaveResult({required this.saved, required this.skipped, required this.expressionsReplaced});

  final int saved;
  final Map<String, String> skipped;
  final List<String> expressionsReplaced;

  static SaveResult fromJson(Map<String, dynamic> json) => SaveResult(
        saved: (json['saved'] as num?)?.toInt() ?? 0,
        skipped: ((json['skipped'] as Map<String, dynamic>?) ?? const {})
            .map((k, v) => MapEntry(k, v.toString())),
        expressionsReplaced: ((json['expressionsReplaced'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );

  String get summary {
    final parts = <String>['saved $saved'];
    if (skipped.isNotEmpty) parts.add('${skipped.length} skipped');
    if (expressionsReplaced.isNotEmpty) {
      final n = expressionsReplaced.length;
      parts.add('$n ${n == 1 ? 'expression' : 'expressions'} replaced');
    }
    return parts.join(' · ');
  }
}

class ShaderSummary {
  const ShaderSummary({
    required this.id,
    required this.description,
    required this.index,
    required this.issues,
  });

  final String id;
  final String description;

  final int index;
  final List<String> issues;

  static ShaderSummary fromJson(Map<String, dynamic> json) => ShaderSummary(
        id: json['id'] as String,
        description: (json['description'] as String?) ?? '',
        index: (json['index'] as num?)?.toInt() ?? 0,
        issues: ((json['issues'] as List<dynamic>?) ?? const []).map((e) => e.toString()).toList(),
      );
}

class ShaderCatalog {
  const ShaderCatalog({
    required this.shaders,
    required this.helpers,
    required this.template,
    required this.preamble,
    required this.epilogue,
    required this.environment,
  });

  final List<ShaderSummary> shaders;
  final String helpers;
  final String template;
  final String preamble;
  final String epilogue;

  final List<EnvironmentEffect> environment;

  static const empty = ShaderCatalog(
    shaders: [],
    helpers: '',
    template: '',
    preamble: '',
    epilogue: '',
    environment: [],
  );

  (String, int) program(String source) => (
        '$preamble\n$source\n$epilogue',
        preamble.split('\n').length + 1,
      );

  static ShaderCatalog fromJson(Map<String, dynamic> json) => ShaderCatalog(
        shaders: ((json['shaders'] as List<dynamic>?) ?? const [])
            .map((e) => ShaderSummary.fromJson(e as Map<String, dynamic>))
            .toList(),
        helpers: (json['helpers'] as String?) ?? '',
        template: (json['template'] as String?) ?? '',
        preamble: (json['preamble'] as String?) ?? '',
        epilogue: (json['epilogue'] as String?) ?? '',
        environment: ((json['environment'] as List<dynamic>?) ?? const [])
            .map((e) => EnvironmentEffect.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

String openShader(String id) => jsonEncode({'t': 'openShader', 'id': id});

String saveShader(String id, String source) =>
    jsonEncode({'t': 'saveShader', 'id': id, 'source': source});

String newShader(String id, {String? source}) =>
    jsonEncode({'t': 'newShader', 'id': id, 'source': ?source});

String renameShader_(String id, String to) =>
    jsonEncode({'t': 'renameShader', 'id': id, 'to': to});

String duplicateFrom(String id, String to) =>
    jsonEncode({'t': 'duplicateShader', 'id': id, 'to': to});

String deleteShader(String id) => jsonEncode({'t': 'deleteShader', 'id': id});

class EnvironmentParam {
  const EnvironmentParam({
    required this.key,
    required this.label,
    required this.type,
    required this.value,
    required this.min,
    required this.max,
    required this.step,
    this.defaultValue = 0,
    this.options = const [],
    this.group = '',
  });

  final String key;
  final String label;
  final String type;
  final double value;
  final double defaultValue;
  final double min;
  final double max;
  final double step;
  final List<String> options;
  final String group;

  bool get isColor => type == 'COLOR';
  bool get isBool => type == 'BOOL';
  bool get isEnum => type == 'ENUM';

  static EnvironmentParam fromJson(Map<String, dynamic> json) => EnvironmentParam(
        key: json['key'] as String,
        label: (json['label'] as String?) ?? json['key'] as String,
        type: (json['type'] as String?) ?? 'FLOAT',
        value: (json['value'] as num?)?.toDouble() ?? 0,
        defaultValue: (json['default'] as num?)?.toDouble() ?? 0,
        min: (json['min'] as num?)?.toDouble() ?? 0,
        max: (json['max'] as num?)?.toDouble() ?? 1,
        step: (json['step'] as num?)?.toDouble() ?? 0.01,
        options: ((json['options'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        group: (json['group'] as String?) ?? '',
      );
}

class EnvironmentEffect {
  const EnvironmentEffect({
    required this.id,
    required this.title,
    required this.description,
    required this.enabled,
    this.programs = const [],
    this.worldEffect = false,
    this.params = const [],
    this.presets = const [],
  });

  final String id;
  final String title;
  final String description;
  final bool enabled;

  final List<EnvironmentProgram> programs;

  final bool worldEffect;

  final List<EnvironmentParam> params;
  final List<String> presets;

  List<String> get groups {
    final seen = <String>[];
    for (final param in params) {
      if (!seen.contains(param.group)) seen.add(param.group);
    }
    return seen;
  }

  static EnvironmentEffect fromJson(Map<String, dynamic> json) => EnvironmentEffect(
        id: json['id'] as String,
        title: (json['title'] as String?) ?? json['id'] as String,
        description: (json['description'] as String?) ?? '',
        enabled: (json['enabled'] as bool?) ?? false,
        programs: ((json['programs'] as List<dynamic>?) ?? const [])
            .map((e) => EnvironmentProgram.fromJson(e as Map<String, dynamic>))
            .toList(),
        worldEffect: (json['worldEffect'] as bool?) ?? false,
        params: ((json['params'] as List<dynamic>?) ?? const [])
            .map((e) => EnvironmentParam.fromJson(e as Map<String, dynamic>))
            .toList(),
        presets: ((json['presets'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}

String setEnvironmentEffect(String id, bool enabled) =>
    jsonEncode({'t': 'setEnvironment', 'id': id, 'enabled': enabled});

String setEnvironmentParam(String id, String key, double value) =>
    jsonEncode({'t': 'setEnvironmentParam', 'id': id, 'key': key, 'value': value});

String applyEnvironmentPreset(String id, String preset) =>
    jsonEncode({'t': 'applyEnvironmentPreset', 'id': id, 'preset': preset});

class EnvironmentProgram {
  const EnvironmentProgram({required this.path, required this.customised});

  final String path;
  final bool customised;

  String get label => path.split('/').last;

  static EnvironmentProgram fromJson(Map<String, dynamic> json) => EnvironmentProgram(
        path: json['path'] as String,
        customised: (json['customised'] as bool?) ?? false,
      );
}

class ImageEntry {
  const ImageEntry({
    required this.name,
    required this.unicode,
    this.columns = 1,
    this.rows = 1,
  });

  final String name;
  final String unicode;
  final int columns;
  final int rows;

  static ImageEntry fromJson(Map<String, dynamic> json) => ImageEntry(
        name: json['name'] as String,
        unicode: (json['unicode'] as String?) ?? '',
        columns: (json['columns'] as num?)?.toInt() ?? 1,
        rows: (json['rows'] as num?)?.toInt() ?? 1,
      );
}

class VideoEntry {
  const VideoEntry({
    required this.name,
    this.width = 0,
    this.height = 0,
    this.seconds = 0,
    this.thumbnail = '',
    this.issue,
  });

  final String name;
  final int width;
  final int height;
  final double seconds;
  final String thumbnail;
  final String? issue;

  bool get hasThumbnail => thumbnail.isNotEmpty;

  String get summary {
    if (issue != null) return issue!;
    if (width == 0 || height == 0) return 'unreadable';
    return '${width}x$height, ${seconds.toStringAsFixed(1)}s';
  }

  static VideoEntry fromJson(Map<String, dynamic> json) => VideoEntry(
        name: json['name'] as String,
        width: (json['width'] as num?)?.toInt() ?? 0,
        height: (json['height'] as num?)?.toInt() ?? 0,
        seconds: (json['seconds'] as num?)?.toDouble() ?? 0,
        thumbnail: (json['thumbnail'] as String?) ?? '',
        issue: json['issue'] as String?,
      );
}

class EffectEntry {
  const EffectEntry({
    required this.id,
    required this.name,
    this.moveX = 0,
    this.moveY = 0,
    this.scaleXPercent = 0,
    this.scaleYPercent = 0,
    this.opacityDelta = 0,
    this.rotationDeg = 0,
    this.durationMs = 250,
    this.interpolation = '',
  });

  final String id;
  final String name;
  final double moveX;
  final double moveY;
  final double scaleXPercent;
  final double scaleYPercent;
  final int opacityDelta;
  final double rotationDeg;
  final int durationMs;
  final String interpolation;

  static EffectEntry fromJson(Map<String, dynamic> json) => EffectEntry(
        id: json['id'] as String,
        name: (json['name'] as String?) ?? (json['id'] as String),
        moveX: (json['moveX'] as num?)?.toDouble() ?? 0,
        moveY: (json['moveY'] as num?)?.toDouble() ?? 0,
        scaleXPercent: (json['scaleXPercent'] as num?)?.toDouble() ?? 0,
        scaleYPercent: (json['scaleYPercent'] as num?)?.toDouble() ?? 0,
        opacityDelta: (json['opacityDelta'] as num?)?.toInt() ?? 0,
        rotationDeg: (json['rotationDeg'] as num?)?.toDouble() ?? 0,
        durationMs: (json['durationMs'] as num?)?.toInt() ?? 250,
        interpolation: (json['interpolation'] as String?) ?? '',
      );

  String get summary {
    final parts = <String>[];
    if (moveX != 0 || moveY != 0) parts.add('moves ${_n(moveX)}, ${_n(moveY)}');
    if (scaleXPercent != 0 || scaleYPercent != 0) {
      parts.add(
        scaleXPercent == scaleYPercent
            ? 'scales ${_n(scaleXPercent)}%'
            : 'scales ${_n(scaleXPercent)}%, ${_n(scaleYPercent)}%',
      );
    }
    if (opacityDelta != 0) parts.add('fades ${opacityDelta > 0 ? '+' : ''}$opacityDelta');
    if (rotationDeg != 0) parts.add('turns ${_n(rotationDeg)}°');
    if (parts.isEmpty) parts.add('no visible change');
    return '${parts.join(', ')} over ${durationMs}ms';
  }

  static String _n(double v) =>
      v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(1);
}

String uploadImage(String name, String base64Data) =>
    jsonEncode({'t': 'uploadImage', 'name': name, 'data': base64Data});

String deleteImage(String name) => jsonEncode({'t': 'deleteImage', 'name': name});

String uploadVideo(String name, String extension, String base64Data) => jsonEncode(
      {'t': 'uploadVideo', 'name': name, 'extension': extension, 'data': base64Data},
    );

String deleteVideo(String name) => jsonEncode({'t': 'deleteVideo', 'name': name});

String openProgram(String path) => jsonEncode({'t': 'openProgram', 'path': path});

String saveProgram(String path, String source) =>
    jsonEncode({'t': 'saveProgram', 'path': path, 'source': source});

String revertProgram(String path) => jsonEncode({'t': 'revertProgram', 'path': path});
