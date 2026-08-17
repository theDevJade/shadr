import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/protocol.dart';

void main() {
  test('decodes a snapshot as the server encodes it', () {
    final json = jsonDecode('''
    {
      "t": "snapshot",
      "name": "demo",
      "screen": {"width": 1920, "height": 1080, "offsetX": 0, "offsetY": 0},
      "elements": [
        {
          "id": "card", "type": "BLOCK_ROUNDED",
          "x": 700, "y": 370, "width": 520, "height": 340, "layer": 10,
          "color": {"packed": 1381404}, "opacity": 255,
          "text": "", "font": "shadr", "textAlignment": "CENTER",
          "enabled": true, "rotationDeg": 0,
          "rounding": {"size": "REGULAR", "radius": null},
          "outline": {"size": 1, "color": {"packed": 2763318}},
          "componentName": null
        }
      ],
      "issues": ["demo.yml: something to fix"],
      "locked": {"chip_a": "from component 'stat_chip'"}
    }
    ''') as Map<String, dynamic>;

    final snapshot = PageSnapshot.fromJson(json);
    expect(snapshot.name, 'demo');
    expect(snapshot.screen.width, 1920);
    expect(snapshot.issues.single, contains('something to fix'));
    expect(snapshot.locked['chip_a'], contains('stat_chip'));

    final card = snapshot.elements.single;
    expect(card.id, 'card');
    expect(card.isRounded, isTrue);
    expect(card.x, 700);
    expect(card.color, 1381404);
    expect(card.outline?.size, 1);
    expect(card.outline?.color, 2763318);
  });

  test('a missing optional leaves a usable default', () {
    final element = Element.fromJson({'id': 'bare'});
    expect(element.type, 'BLOCK');
    expect(element.width, 20);
    expect(element.opacity, 255);
    expect(element.rounding, isNull);
    expect(element.outline, isNull);
  });

  group('corner radius', () {
    test('an explicit radius wins over the preset', () {
      const rounding = Rounding(size: 'SMALL', radius: 30);
      expect(rounding.resolvedRadius(200, 100), 30);
    });

    test('a radius past half the shorter side clamps to a capsule', () {
      const rounding = Rounding(size: 'REGULAR', radius: 500);
      expect(rounding.resolvedRadius(200, 100), 50);
    });

    test('presets match the renderer', () {
      expect(const Rounding(size: 'NONE').resolvedRadius(200, 200), 0);
      expect(const Rounding(size: 'SMALL').resolvedRadius(200, 200), 4);
      expect(const Rounding(size: 'LARGE').resolvedRadius(200, 200), 24);
    });
  });

  group('endpoint', () {
    test('connects back to the origin it was served from, with its token', () {
      final endpoint = Endpoint.from(Uri.parse('http://10.0.0.5:8124/?token=abc123'));
      expect(endpoint.url, 'ws://10.0.0.5:8124/');
      expect(endpoint.token, 'abc123');
      expect(endpoint.protocols, ['${tokenProtocolPrefix}abc123']);
    });

    test('https implies wss, so a tunnelled editor is not downgraded', () {
      final endpoint = Endpoint.from(Uri.parse('https://shadr.example/?token=abc123'));
      expect(endpoint.url, 'wss://shadr.example:443/');
    });

    test('no token means no subprotocol, not an empty one', () {
      final endpoint = Endpoint.from(Uri.parse('http://10.0.0.5:8124/'));
      expect(endpoint.token, isNull);
      expect(endpoint.protocols, isEmpty);
    });
  });

  test('outbound messages carry the discriminator the server decodes on', () {
    final open = jsonDecode(
      openDocument(const DocumentRef(name: 'chip', kind: DocumentKind.component)),
    ) as Map<String, dynamic>;
    expect(open['t'], 'open');
    expect(open['kind'], 'COMPONENT');
    expect(open['name'], 'chip');

    final patch = jsonDecode(patchElement('card', {'position.x': '250'})) as Map<String, dynamic>;
    expect(patch['t'], 'patch');
    expect(patch['elementId'], 'card');
    expect((patch['changes'] as Map)['position.x'], '250');
  });
}
