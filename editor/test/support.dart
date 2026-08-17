import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart' hide Element;
import 'package:shadr_editor/model.dart';
import 'package:shadr_editor/protocol.dart';
import 'package:shadr_editor/theme.dart';

class FakeTransport implements EditorTransport {
  final _controller = StreamController<String>.broadcast(sync: true);
  final List<String> sent = [];
  bool closed = false;

  @override
  Stream<String> get inbound => _controller.stream;

  @override
  void send(String message) => sent.add(message);

  @override
  Future<void> close() async {
    closed = true;
    await _controller.close();
  }

  void deliver(Map<String, dynamic> json) => _controller.add(jsonEncode(json));

  List<Map<String, dynamic>> get decoded =>
      sent.map((s) => jsonDecode(s) as Map<String, dynamic>).toList();

  Map<String, dynamic>? get last => decoded.isEmpty ? null : decoded.last;

  Map<String, dynamic>? lastOfType(String type) {
    for (final message in decoded.reversed) {
      if (message['t'] == type) return message;
    }
    return null;
  }
}

Element block(
  String id, {
  double x = 0,
  double y = 0,
  double w = 100,
  double h = 50,
  double layer = 0,
  String type = 'BLOCK',
  String sourcePath = '',
  bool enabled = true,
}) =>
    Element(
      id: id,
      type: type,
      x: x,
      y: y,
      width: w,
      height: h,
      layer: layer,
      color: 0xFFFFFF,
      opacity: 255,
      text: '',
      font: 'shadr',
      textAlign: 'CENTER',
      enabled: enabled,
      rotationDeg: 0,
      sourcePath: sourcePath,
    );

Map<String, dynamic> snapshotJson({
  String name = 'demo',
  List<Element> elements = const [],
  Map<String, String> locked = const {},
  int? previewTick,
  bool canUndo = false,
  bool canRedo = false,
  bool dirty = false,
  List<Map<String, dynamic>> animations = const [],
  String kind = 'PAGE',
}) =>
    {
      't': 'snapshot',
      'name': name,
      'screen': {'width': 1920.0, 'height': 1080.0},
      'elements': [
        for (final e in elements)
          {
            'id': e.id,
            'type': e.type,
            'x': e.x,
            'y': e.y,
            'width': e.width,
            'height': e.height,
            'layer': e.layer,
            'color': {'packed': e.color},
            'opacity': e.opacity,
            'text': e.text,
            'font': e.font,
            'textAlignment': e.textAlign,
            'enabled': e.enabled,
            'rotationDeg': e.rotationDeg,
            'sourcePath': e.sourcePath,
          },
      ],
      'issues': <String>[],
      'locked': locked,
      'canUndo': canUndo,
      'canRedo': canRedo,
      'dirty': dirty,
      'animations': animations,
      'previewTick': previewTick,
      'kind': kind,
    };

({EditorModel model, FakeTransport transport}) connectedModel() {
  final transport = FakeTransport();
  final model = EditorModel(
    endpoint: const Endpoint(url: 'ws://test', token: 't'),
    connect: () => transport,
  )..connect();
  return (model: model, transport: transport);
}

Widget harness(EditorModel model, Widget child) => MaterialApp(
      theme: buildEditorTheme(),
      home: EditorScope(
        model: model,
        child: Scaffold(body: child),
      ),
    );
