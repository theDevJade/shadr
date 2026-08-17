library;

import 'dart:convert';
import 'dart:ui' show Rect;

const protocolVersion = 1;

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
  });

  final double width;
  final double height;
  final double offsetX;
  final double offsetY;

  static ScreenDef fromJson(Map<String, dynamic> json) => ScreenDef(
        width: (json['width'] as num?)?.toDouble() ?? 1920,
        height: (json['height'] as num?)?.toDouble() ?? 1080,
        offsetX: (json['offsetX'] as num?)?.toDouble() ?? 0,
        offsetY: (json['offsetY'] as num?)?.toDouble() ?? 0,
      );
}

class Rounding {
  const Rounding({required this.size, this.radius});

  final String size;
  final double? radius;

  static Rounding? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : Rounding(
          size: (json['size'] as String?) ?? 'REGULAR',
          radius: (json['radius'] as num?)?.toDouble(),
        );

  double resolvedRadius(double width, double height) {
    final shorter = width < height ? width : height;
    final explicit = radius;
    if (explicit != null) return explicit.clamp(0.0, shorter / 2);
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
  const Outline({required this.size, required this.color});

  final double size;
  final int color;

  static Outline? fromJson(Map<String, dynamic>? json) => json == null
      ? null
      : Outline(
          size: (json['size'] as num?)?.toDouble() ?? 0,
          color: _colorOf(json['color']),
        );
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
    this.sourcePath = '',
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

  final String sourcePath;

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

  double get effectiveLayer => isBlur ? blurPanelLayer : layer;

  bool get supportsRounding =>
      type == 'BLOCK' || type == 'BLOCK_ROUNDED' || type == 'BLOCK_SDF';

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
        sourcePath: (json['sourcePath'] as String?) ?? '',
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

class EnvironmentEffect {
  const EnvironmentEffect({
    required this.id,
    required this.title,
    required this.description,
    required this.enabled,
    this.programs = const [],
  });

  final String id;
  final String title;
  final String description;
  final bool enabled;

  final List<EnvironmentProgram> programs;

  static EnvironmentEffect fromJson(Map<String, dynamic> json) => EnvironmentEffect(
        id: json['id'] as String,
        title: (json['title'] as String?) ?? json['id'] as String,
        description: (json['description'] as String?) ?? '',
        enabled: (json['enabled'] as bool?) ?? false,
        programs: ((json['programs'] as List<dynamic>?) ?? const [])
            .map((e) => EnvironmentProgram.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

String setEnvironmentEffect(String id, bool enabled) =>
    jsonEncode({'t': 'setEnvironment', 'id': id, 'enabled': enabled});

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

String openProgram(String path) => jsonEncode({'t': 'openProgram', 'path': path});

String saveProgram(String path, String source) =>
    jsonEncode({'t': 'saveProgram', 'path': path, 'source': source});

String revertProgram(String path) => jsonEncode({'t': 'revertProgram', 'path': path});
