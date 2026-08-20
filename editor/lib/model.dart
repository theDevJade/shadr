import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart' show setEquals;
import 'package:flutter/widgets.dart' hide Element;
import 'package:web_socket_channel/web_socket_channel.dart';

import 'protocol.dart';
import 'protocol.dart' as wire;
import 'snapping.dart';
import 'viewport.dart';

abstract class EditorTransport {
  Stream<String> get inbound;
  void send(String message);
  Future<void> close();
}

class WebSocketTransport implements EditorTransport {
  WebSocketTransport(Uri url, {List<String> protocols = const []})
    : _channel = WebSocketChannel.connect(
        url,
        protocols: protocols.isEmpty ? null : protocols,
      );

  final WebSocketChannel _channel;

  @override
  Stream<String> get inbound => _channel.stream.map((event) => event as String);

  @override
  void send(String message) => _channel.sink.add(message);

  @override
  Future<void> close() => _channel.sink.close();
}

enum ConnectionStatus { connecting, connected, disconnected, failed }

enum ResizeHandle {
  topLeft,
  top,
  topRight,
  left,
  right,
  bottomLeft,
  bottom,
  bottomRight;

  bool get movesLeft => this == topLeft || this == left || this == bottomLeft;
  bool get movesRight =>
      this == topRight || this == right || this == bottomRight;
  bool get movesTop => this == topLeft || this == top || this == topRight;
  bool get movesBottom =>
      this == bottomLeft || this == bottom || this == bottomRight;

  MouseCursor get cursor => switch (this) {
    ResizeHandle.topLeft ||
    ResizeHandle.bottomRight => SystemMouseCursors.resizeUpLeftDownRight,
    ResizeHandle.topRight ||
    ResizeHandle.bottomLeft => SystemMouseCursors.resizeUpRightDownLeft,
    ResizeHandle.top || ResizeHandle.bottom => SystemMouseCursors.resizeUpDown,
    ResizeHandle.left ||
    ResizeHandle.right => SystemMouseCursors.resizeLeftRight,
  };

  Offset anchorOn(Rect rect) => switch (this) {
    ResizeHandle.topLeft => rect.topLeft,
    ResizeHandle.top => rect.topCenter,
    ResizeHandle.topRight => rect.topRight,
    ResizeHandle.left => rect.centerLeft,
    ResizeHandle.right => rect.centerRight,
    ResizeHandle.bottomLeft => rect.bottomLeft,
    ResizeHandle.bottom => rect.bottomCenter,
    ResizeHandle.bottomRight => rect.bottomRight,
  };
}

enum Workspace { ui, shaders, images, videos }

class EditorModel extends ChangeNotifier {
  EditorModel({required this.endpoint, EditorTransport Function()? connect})
    : _connect =
          connect ??
          (() => WebSocketTransport(
            Uri.parse(endpoint.url),
            protocols: endpoint.protocols,
          ));

  final Endpoint endpoint;
  final EditorTransport Function() _connect;

  EditorTransport? _transport;
  StreamSubscription<String>? _subscription;

  ConnectionStatus _status = ConnectionStatus.disconnected;
  ConnectionStatus get status => _status;

  String _message = 'not connected';
  String get message => _message;

  String? _notice;
  String? get notice => _notice;

  void acknowledgeNotice() {
    _notice = null;
  }

  Workspace _workspace = Workspace.ui;
  Workspace get workspace => _workspace;

  void setWorkspace(Workspace next) {
    if (_workspace == next) return;
    _workspace = next;
    notifyListeners();
  }

  List<ImageEntry> _images = const [];
  List<ImageEntry> get images => _images;

  List<VideoEntry> _videos = const [];
  List<VideoEntry> get videos => _videos;

  List<EffectEntry> _effects = const [];
  List<EffectEntry> get effects => _effects;

  void uploadImage(String name, String base64Data) =>
      _send(wire.uploadImage(name, base64Data));

  void removeImage(String name) => _send(wire.deleteImage(name));

  void insertImage(ImageEntry entry) {
    final screen = _snapshot?.screen;
    final centre = screen == null
        ? const Offset(860, 500)
        : _viewport.toDesign(viewportSize.center(Offset.zero));
    _send(
      wire.addElement(
        'image',
        centre.dx.roundToDouble() - 60,
        centre.dy.roundToDouble() - 20,
        width: entry.columns * 64,
        height: entry.rows * 64,
      ),
    );
    _pendingImage = entry;
  }

  ImageEntry? _pendingImage;

  String? _pendingVideo;

  void uploadVideo(String name, String extension, String base64Data) =>
      _send(wire.uploadVideo(name, extension, base64Data));

  void removeVideo(String name) => _send(wire.deleteVideo(name));

  void insertVideo(VideoEntry entry) {
    final screen = _snapshot?.screen;
    final centre = screen == null
        ? const Offset(860, 500)
        : _viewport.toDesign(viewportSize.center(Offset.zero));
    final width = entry.width == 0 ? 640.0 : entry.width.toDouble();
    final height = entry.height == 0 ? 360.0 : entry.height.toDouble();
    _send(
      wire.addElement(
        'video',
        centre.dx.roundToDouble() - width / 2,
        centre.dy.roundToDouble() - height / 2,
        width: width,
        height: height,
      ),
    );
    _pendingVideo = entry.name;
  }

  ShaderCatalog _catalog = ShaderCatalog.empty;
  ShaderCatalog get catalog => _catalog;

  bool get frostedGlassEnabled =>
      _catalog.environment.any((effect) => effect.id == 'blur' && effect.enabled);

  String? _openShaderId;
  String? get openShaderId => _openShaderId;

  String _shaderBuffer = '';
  String get shaderBuffer => _shaderBuffer;

  String _shaderSaved = '';
  bool get shaderDirty => _shaderBuffer != _shaderSaved;

  List<String> _shaderIssues = const [];
  List<String> get shaderIssues => _shaderIssues;

  void openShaderDocument(String id) {
    _openProgramPath = null;
    _openShaderId = id;
    _send(openShader(id));
    notifyListeners();
  }

  void editShader(String source) {
    if (_shaderBuffer == source) return;
    _shaderBuffer = source;
    notifyListeners();
  }

  void saveShaderDocument() {
    _shaderSaved = _shaderBuffer;
    final program = _openProgramPath;
    if (program != null) {
      _send(saveProgram(program, _shaderBuffer));
    } else {
      final id = _openShaderId;
      if (id == null) return;
      _send(saveShader(id, _shaderBuffer));
    }
    notifyListeners();
  }

  void createShader(String id) => _send(newShader(id));

  void renameShader(String id, String to) => _send(renameShader_(id, to));

  void duplicateShader(String id, String to) {
    if (id == _openShaderId) {
      _send(newShader(to, source: _shaderSaved));
    } else {
      _send(duplicateFrom(id, to));
    }
  }

  void setEnvironment(String id, bool enabled) =>
      _send(setEnvironmentEffect(id, enabled));

  String? _openProgramPath;
  String? get openProgramPath => _openProgramPath;

  bool _programCustomised = false;

  bool get programCustomised => _programCustomised;

  void openWorldProgram(String path) {
    _openProgramPath = path;
    _openShaderId = null;
    _send(openProgram(path));
    notifyListeners();
  }

  void revertWorldProgram() {
    final path = _openProgramPath;
    if (path != null) _send(revertProgram(path));
  }

  void removeShader(String id) {
    if (_openShaderId == id) {
      _openShaderId = null;
      _shaderBuffer = '';
      _shaderSaved = '';
    }
    _send(deleteShader(id));
  }

  List<DocumentRef> _documents = const [];
  List<DocumentRef> get documents => _documents;

  DocumentRef? _openRef;
  DocumentRef? get openRef => _openRef;

  PageSnapshot? _snapshot;
  PageSnapshot? get snapshot => _snapshot;

  SaveResult? _lastSave;
  SaveResult? get lastSave => _lastSave;

  final Set<String> _selection = <String>{};
  Set<String> get selection => UnmodifiableSetView(_selection);

  CanvasViewport _viewport = const CanvasViewport(
    scale: 1,
    offset: Offset.zero,
  );
  CanvasViewport get viewport => _viewport;

  bool _viewportInitialised = false;

  bool _snapEnabled = true;
  bool get snapEnabled => _snapEnabled;

  bool _timelineVisible = true;
  bool get timelineVisible => _timelineVisible;

  void setTimelineVisible(bool visible) {
    _timelineVisible = visible;
    notifyListeners();
  }

  List<Guide> _guides = const [];
  List<Guide> get guides => _guides;

  final Snapper _snapper = const Snapper();

  bool get isPreviewing => _snapshot?.previewTick != null;

  Element? get soleSelection {
    if (_selection.length != 1) return null;
    return elementById(_selection.first);
  }

  Element? elementById(String id) {
    for (final element in _snapshot?.elements ?? const <Element>[]) {
      if (element.id == id) return element;
    }
    return null;
  }

  String? lockReason(String id) => _snapshot?.locked[id];

  void connect() {
    _subscription?.cancel();
    _transport?.close();
    _setStatus(ConnectionStatus.connecting, 'connecting to ${endpoint.url}');
    try {
      final transport = _connect();
      _transport = transport;
      _subscription = transport.inbound.listen(
        _receive,
        onError: (Object error) =>
            _setStatus(ConnectionStatus.failed, _describe(error)),
        onDone: () => _setStatus(ConnectionStatus.disconnected, 'disconnected'),
      );
    } catch (error) {
      _setStatus(ConnectionStatus.failed, _describe(error));
    }
  }

  String _describe(Object error) => endpoint.token == null
      ? 'not authenticated: open the URL the server logged, with its ?token='
      : 'connection failed: $error';

  @override
  void dispose() {
    _flushTimer?.cancel();
    _subscription?.cancel();
    _transport?.close();
    super.dispose();
  }

  void _setStatus(ConnectionStatus status, String message) {
    _status = status;
    _message = message;
    notifyListeners();
  }

  void _receive(String raw) {
    final json = jsonDecode(raw) as Map<String, dynamic>;
    switch (json['t']) {
      case 'welcome':
        _status = ConnectionStatus.connected;
        _message = 'connected';
        _documents = ((json['documents'] as List<dynamic>?) ?? const [])
            .map((e) => DocumentRef.fromJson(e as Map<String, dynamic>))
            .toList();
        final restore =
            _openRef ?? (_documents.isEmpty ? null : _documents.first);
        notifyListeners();
        if (restore != null) open(restore);
      case 'snapshot':
        final next = PageSnapshot.fromJson(json);
        final pending = _pendingImage;
        if (pending != null) {
          _pendingImage = null;
          final added = next.elements.where((e) => e.type == 'IMAGE').lastOrNull;
          if (added != null) {
            _send(wire.patchElement(added.id, {
              'font': 'uiimages',
              'unicode': pending.unicode,
            }));
          }
        }
        final pendingVideo = _pendingVideo;
        if (pendingVideo != null) {
          _pendingVideo = null;
          final added = next.elements.where((e) => e.type == 'VIDEO').lastOrNull;
          if (added != null) {
            _send(wire.patchElement(added.id, {'video': pendingVideo}));
          }
        }
        final changedDocument = _snapshot?.name != next.name;
        _snapshot = next;
        if (changedDocument) {
          _selection.clear();
          _viewportInitialised = false;
        }
        if (_settling && _origins.isEmpty) {
          _settling = false;
          _live = const {};
        }
        final opened = _pendingOpen;
        if (opened != null) {
          _pendingOpen = null;
          _openRef = opened;
        }
        _selection.removeWhere((id) => next.elements.every((e) => e.id != id));
        notifyListeners();
      case 'saved':
        _lastSave = SaveResult.fromJson(json);
        _message = _lastSave!.summary;
        notifyListeners();
      case 'documents':
        _documents = ((json['documents'] as List<dynamic>?) ?? const [])
            .map((e) => DocumentRef.fromJson(e as Map<String, dynamic>))
            .toList();
        notifyListeners();
      case 'images':
        _images = ((json['images'] as List<dynamic>?) ?? const [])
            .map((e) => ImageEntry.fromJson(e as Map<String, dynamic>))
            .toList();
        notifyListeners();
      case 'videos':
        _videos = ((json['videos'] as List<dynamic>?) ?? const [])
            .map((e) => VideoEntry.fromJson(e as Map<String, dynamic>))
            .toList();
        notifyListeners();
      case 'effects':
        _effects = ((json['effects'] as List<dynamic>?) ?? const [])
            .map((e) => EffectEntry.fromJson(e as Map<String, dynamic>))
            .toList();
        notifyListeners();
      case 'shaders':
        _catalog = ShaderCatalog.fromJson(json);
        if (_openShaderId == null &&
            _openProgramPath == null &&
            _catalog.shaders.isNotEmpty) {
          openShaderDocument(_catalog.shaders.first.id);
        }
        notifyListeners();
      case 'programSource':
        _openProgramPath = json['path'] as String;
        _openShaderId = null;
        _programCustomised = (json['customised'] as bool?) ?? false;
        _shaderBuffer = (json['source'] as String?) ?? '';
        _shaderSaved = _shaderBuffer;
        _shaderIssues = const [];
        notifyListeners();
      case 'shaderSource':
        _openShaderId = json['id'] as String;
        _shaderBuffer = (json['source'] as String?) ?? '';
        _shaderSaved = _shaderBuffer;
        _shaderIssues = ((json['issues'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList();
        notifyListeners();
      case 'shaderSaved':
        final rebuilt = (json['packRebuilt'] as bool?) ?? false;
        final detail = rebuilt ? 'the pack was rebuilt' : 'rebuild the pack';
        _notice = 'Saved: $detail';
        _shaderIssues = ((json['issues'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList();
        _message = 'saved: $detail';
        notifyListeners();
      case 'error':
        _pendingOpen = null;
        _message = 'server: ${json['message']}';
        _notice = json['message'] as String?;
        notifyListeners();
    }
  }

  void _send(String message) => _transport?.send(message);

  void acknowledgeSave() {
    _lastSave = null;
    notifyListeners();
  }

  DocumentRef? _pendingOpen;

  void open(DocumentRef ref) {
    _openRef = ref;
    _pendingOpen = null;
    _selection.clear();
    notifyListeners();
    _send(wire.openDocument(ref));
  }

  void createDocument(
    DocumentRef ref, {
    bool hud = false,
    double width = 1920,
    double height = 1080,
  }) {
    _pendingOpen = ref;
    _send(wire.newDocument(ref, hud: hud, width: width, height: height));
  }

  void deleteDocument(DocumentRef ref) {
    if (ref == _openRef) _openRef = null;
    _send(wire.deleteDocument(ref));
  }

  void renameDocument(DocumentRef ref, String to) {
    if (ref == _openRef) _pendingOpen = DocumentRef(name: to, kind: ref.kind);
    _send(wire.renameDocument(ref, to));
  }

  void duplicateDocument(DocumentRef ref, String to) {
    _pendingOpen = DocumentRef(name: to, kind: ref.kind);
    _send(wire.duplicateDocument(ref, to));
  }

  void patchScreen(String path, String value, {String? gesture}) {
    if (_refuseWhilePreviewing()) return;
    _send(wire.patchScreen({path: value}, gesture: gesture));
  }

  void reload() => _send(wire.reloadPage());

  void save() => _send(wire.savePage());

  void select(String? id, {bool additive = false}) {
    if (id == null) {
      if (additive || _selection.isEmpty) return;
      _selection.clear();
    } else if (additive) {
      _selection.contains(id) ? _selection.remove(id) : _selection.add(id);
    } else {
      if (_selection.length == 1 && _selection.contains(id)) return;
      _selection
        ..clear()
        ..add(id);
    }
    notifyListeners();
  }

  void setSelection(Iterable<String> ids) {
    final next = ids.toSet();
    if (setEquals(next, _selection)) return;
    _selection
      ..clear()
      ..addAll(next);
    notifyListeners();
  }

  void selectAll() {
    final elements = _snapshot?.elements ?? const <Element>[];
    _selection
      ..clear()
      ..addAll(elements.map((e) => e.id));
    notifyListeners();
  }

  void clearSelection() => select(null);

  Element? hitTest(Offset design) {
    Element? best;
    for (final element in _snapshot?.elements ?? const <Element>[]) {
      if (!element.enabled) continue;
      if (!_covers(element, design)) continue;
      if (best == null || element.effectiveLayer >= best.effectiveLayer) best = element;
    }
    return best;
  }

  bool _covers(Element element, Offset design) {
    final rect = boundsOf(element);
    if (element.rotationDeg == 0) return rect.contains(design);
    final radians = -element.rotationDeg * math.pi / 180;
    final local = design - rect.center;
    final rotated = Offset(
      local.dx * math.cos(radians) - local.dy * math.sin(radians),
      local.dx * math.sin(radians) + local.dy * math.cos(radians),
    );
    return rect.contains(rotated + rect.center);
  }

  List<Element> lineageOf(Element element) {
    final elements = _snapshot?.elements ?? const <Element>[];
    final byPath = <String, Element>{
      for (final candidate in elements)
        if (candidate.sourcePath.isNotEmpty) candidate.sourcePath: candidate,
    };
    final lineage = <Element>[element];
    var current = element;
    for (var guard = 0; guard < elements.length; guard++) {
      final parentPath = current.parentPath;
      final parent = parentPath == null ? null : byPath[parentPath];
      if (parent == null) break;
      lineage.add(parent);
      current = parent;
    }
    return lineage;
  }

  void ensureFitted(Size size) {
    if (_viewportInitialised) return;
    final page = _snapshot?.screen;
    if (page == null) return;
    _viewportInitialised = true;
    _viewport = CanvasViewport.fit(size, Size(page.width, page.height));
    notifyListeners();
  }

  void fit(Size size) {
    final page = _snapshot?.screen;
    if (page == null) return;
    _viewportInitialised = true;
    _viewport = CanvasViewport.fit(size, Size(page.width, page.height));
    notifyListeners();
  }

  void zoomAt(Offset focal, double factor) {
    final next = _viewport.zoomedAt(focal, factor);
    if (next == _viewport) return;
    _viewport = next;
    notifyListeners();
  }

  void zoomTo(double scale, Size size) {
    _viewportInitialised = true;
    _viewport = _viewport.zoomedTo(scale, size);
    notifyListeners();
  }

  void pan(Offset delta) {
    _viewport = _viewport.panned(delta);
    notifyListeners();
  }

  void setSnapping(bool enabled) {
    _snapEnabled = enabled;
    notifyListeners();
  }

  Timer? _flushTimer;

  ResizeHandle? _handle;
  String? _resizeId;

  Map<String, Rect> _origins = const {};

  Offset _gestureDelta = Offset.zero;

  Map<String, Rect> _live = const {};

  bool _settling = false;

  static const _flushInterval = Duration(milliseconds: 40);

  ResizeHandle? get activeHandle => _handle;

  bool get isGesturing => _origins.isNotEmpty;

  Map<String, Rect> get liveRects => UnmodifiableMapView(_live);

  Rect boundsOf(Element element) => _live[element.id] ?? element.bounds;

  void beginResize(ResizeHandle handle) {
    final element = soleSelection;
    if (element == null || isPreviewing) return;
    _handle = handle;
    _resizeId = element.id;
    _origins = {element.id: element.bounds};
    _gestureDelta = Offset.zero;
    _live = const {};
    notifyListeners();
  }

  void dragBy(Offset delta, {required bool bypassSnapping}) {
    if (isPreviewing) return;
    if (_handle == null && _selection.isEmpty) return;
    _bypassSnapping = bypassSnapping;
    if (_origins.isEmpty) _captureOrigins();
    if (_origins.isEmpty) return;
    _gestureDelta += delta;
    _recompute();
    _flushTimer ??= Timer(_flushInterval, _flush);
  }

  bool _bypassSnapping = false;

  void endGesture() {
    final sent = _flush();
    _handle = null;
    _resizeId = null;
    _origins = const {};
    _gestureDelta = Offset.zero;
    _settling = sent;
    if (!sent) _live = const {};
    _guides = const [];
    notifyListeners();
  }

  void _captureOrigins() {
    final snapshot = _snapshot;
    if (snapshot == null) return;
    final dragging = movingSelection;
    _origins = {
      for (final element in snapshot.elements)
        if (dragging.contains(element.id)) element.id: boundsOf(element),
    };
    _gestureDelta = Offset.zero;
  }

  bool _flush() {
    _flushTimer?.cancel();
    _flushTimer = null;
    if (_live.isEmpty || _origins.isEmpty) return false;

    final id = _resizeId;
    final handle = _handle;
    if (handle != null && id != null) {
      final rect = _live[id];
      if (rect == null) return false;
      _send(
        wire.patchElement(id, {
          'position.x': '${rect.left}',
          'position.y': '${rect.top}',
          'size.width': '${rect.width}',
          'size.height': '${rect.height}',
        }, gesture: 'resize:$id:${handle.name}'),
      );
      return true;
    }

    _send(
      wire.patchElements({
        for (final entry in _live.entries)
          entry.key: {
            'position.x': '${entry.value.left}',
            'position.y': '${entry.value.top}',
          },
      }, gesture: 'drag:${_selection.join(",")}'),
    );
    return true;
  }

  bool get _snapping => _snapEnabled && !_bypassSnapping;

  void _recompute() {
    final snapshot = _snapshot;
    if (snapshot == null) return;
    _live = _handle == null ? _moveTo(snapshot) : _resizeTo(snapshot);
    notifyListeners();
  }

  Map<String, Rect> _moveTo(PageSnapshot snapshot) {
    final others = [
      for (final element in snapshot.elements)
        if (!_origins.containsKey(element.id)) element.bounds,
    ];

    final snapped = _snapper.snapRects(
      moving: _origins.values.toList(),
      others: others,
      dx: _gestureDelta.dx,
      dy: _gestureDelta.dy,
      screen: snapshot.screen,
      enabled: _snapping,
    );
    _guides = snapped.guides;

    final shift = Offset(snapped.dx, snapped.dy);
    return {
      for (final entry in _origins.entries) entry.key: entry.value.shift(shift),
    };
  }

  Map<String, Rect> _resizeTo(PageSnapshot snapshot) {
    final handle = _handle;
    final id = _resizeId;
    final origin = id == null ? null : _origins[id];
    if (handle == null || id == null || origin == null) return const {};

    final others = [
      for (final element in snapshot.elements)
        if (element.id != id) element.bounds,
    ];

    var left = origin.left;
    var right = origin.right;
    var top = origin.top;
    var bottom = origin.bottom;
    if (handle.movesLeft) left += _gestureDelta.dx;
    if (handle.movesRight) right += _gestureDelta.dx;
    if (handle.movesTop) top += _gestureDelta.dy;
    if (handle.movesBottom) bottom += _gestureDelta.dy;

    final guides = <Guide>[];
    double snap(double value, bool vertical) {
      final target = _snapper.nearestEdge(
        value: value,
        vertical: vertical,
        others: others,
        screen: snapshot.screen,
        enabled: _snapping,
      );
      if (target == null) return value.roundToDouble();
      guides.add((vertical: vertical, at: target));
      return target;
    }

    if (handle.movesLeft) left = snap(left, true);
    if (handle.movesRight) right = snap(right, true);
    if (handle.movesTop) top = snap(top, false);
    if (handle.movesBottom) bottom = snap(bottom, false);

    const minimum = 1.0;
    if (right - left < minimum) {
      handle.movesLeft ? left = right - minimum : right = left + minimum;
    }
    if (bottom - top < minimum) {
      handle.movesTop ? top = bottom - minimum : bottom = top + minimum;
    }

    _guides = guides;
    return {id: Rect.fromLTRB(left, top, right, bottom)};
  }

  /// The selection plus everything nested inside it, so dragging a card takes its contents.
  Set<String> get movingSelection {
    final elements = _snapshot?.elements ?? const <Element>[];
    final roots = elements.where((e) => _selection.contains(e.id)).toList();
    final out = <String>{..._selection};
    for (final root in roots) {
      if (root.sourcePath.isEmpty) continue;
      for (final other in elements) {
        if (other.id == root.id) continue;
        for (final separator in const ['.children.', '.grid/']) {
          if (other.sourcePath.startsWith('${root.sourcePath}$separator')) out.add(other.id);
        }
      }
    }
    return out;
  }

  bool get canReorder => _selection.isNotEmpty && !isPreviewing;

  List<Element> _reorderPeers(Element of) {
    final elements = _snapshot?.elements ?? const <Element>[];
    return elements
        .where((e) => !e.isBlur && e.parentPath == of.parentPath)
        .toList()
      ..sort((a, b) => a.layer.compareTo(b.layer));
  }

  void _shift(int direction) {
    if (!canReorder) return;
    final element = soleSelection;
    if (element == null || element.isBlur) return;
    final peers = _reorderPeers(element);
    final at = peers.indexWhere((e) => e.id == element.id);
    final swapWith = at + direction;
    if (at < 0 || swapWith < 0 || swapWith >= peers.length) return;
    final other = peers[swapWith];
    _send(wire.patchElements({
      element.id: {'layer': '${other.layer}'},
      other.id: {'layer': '${element.layer}'},
    }));
  }

  void bringForward() => _shift(1);

  void sendBackward() => _shift(-1);

  void _toEnd(bool front) {
    if (!canReorder) return;
    final element = soleSelection;
    if (element == null || element.isBlur) return;
    final peers = _reorderPeers(element);
    if (peers.isEmpty) return;
    final target = front ? peers.last.layer + 1 : peers.first.layer - 1;
    _send(wire.patchElement(element.id, {'layer': '$target'}));
  }

  void bringToFront() => _toEnd(true);

  void sendToBack() => _toEnd(false);

  void nudge(Offset delta) {
    if (isPreviewing || _selection.isEmpty) return;
    final snapshot = _snapshot;
    if (snapshot == null) return;
    final dragging = movingSelection;
    final moving = snapshot.elements.where((e) => dragging.contains(e.id));
    _send(
      wire.patchElements({
        for (final element in moving)
          element.id: {
            'position.x': '${element.x + delta.dx}',
            'position.y': '${element.y + delta.dy}',
          },
      }),
    );
  }

  bool _refuseWhilePreviewing() {
    if (!isPreviewing) return false;
    _message = 'previewing tick ${_snapshot?.previewTick}';
    notifyListeners();
    return true;
  }

  void patchOne(
    String elementId,
    String path,
    String value, {
    String? gesture,
  }) {
    if (_refuseWhilePreviewing()) return;
    _send(wire.patchElement(elementId, {path: value}, gesture: gesture));
  }

  void patchSelection(String path, String value, {String? gesture}) {
    if (_refuseWhilePreviewing()) return;
    if (_selection.isEmpty) return;
    _send(
      wire.patchElements({
        for (final id in _selection) id: {path: value},
      }, gesture: gesture),
    );
  }

  void undo() => _send(wire.undoEdit());

  void redo() => _send(wire.redoEdit());

  void addElement(String type) {
    final screen = _snapshot?.screen;
    final centre = screen == null
        ? const Offset(860, 500)
        : _viewport.toDesign(viewportSize.center(Offset.zero));
    _send(
      wire.addElement(
        type,
        centre.dx.roundToDouble() - 60,
        centre.dy.roundToDouble() - 20,
      ),
    );
  }

  Size viewportSize = const Size(1920, 1080);

  void deleteSelection() {
    if (_selection.isEmpty || isPreviewing) return;
    _send(wire.deleteElements(_selection.toList()));
    _selection.clear();
    notifyListeners();
  }

  String get animationName => _snapshot?.animations.firstOrNull?.name ?? 'open';

  void scrub(int? tick) => _send(wire.scrub(tick));

  void addStep(String target, String axis) {
    final element = elementById(target);
    final value = element == null ? null : _axisValue(element, axis);
    if (value == null) return;
    _send(
      wire.setAnimationStep(
        animation: animationName,
        target: target,
        axis: axis,
        from: value,
        to: value,
        duration: _snapshot?.animations.firstOrNull?.duration ?? 20,
      ),
    );
  }

  void editStep(
    AnimationStep step, {
    double? from,
    double? to,
    int? duration,
    String? easing,
  }) {
    _send(
      wire.setAnimationStep(
        animation: animationName,
        target: step.target,
        axis: step.axis,
        from: from ?? step.from,
        to: to ?? step.to,
        duration: duration ?? step.duration,
        easing: easing ?? step.easing,
      ),
    );
  }

  void removeStep(String target, String axis) =>
      _send(wire.removeAnimationStep(animationName, target, axis));

  static double? _axisValue(Element element, String axis) => switch (axis) {
    'x' => element.x,
    'y' => element.y,
    'width' => element.width,
    'height' => element.height,
    'opacity' => element.opacity.toDouble(),
    'rotation' => element.rotationDeg,
    _ => null,
  };
}

class EditorScope extends InheritedNotifier<EditorModel> {
  const EditorScope({
    super.key,
    required EditorModel model,
    required super.child,
  }) : super(notifier: model);

  static EditorModel of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<EditorScope>();
    assert(scope != null, 'No EditorScope above this widget');
    return scope!.notifier!;
  }

  static EditorModel read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<EditorScope>();
    assert(scope != null, 'No EditorScope above this widget');
    return scope!.notifier!;
  }
}
