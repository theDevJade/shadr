import 'package:flutter_test/flutter_test.dart';

import 'support.dart';

void main() {
  test('saving a shader clears the unsaved marker', () {
    final (model: model, transport: transport) = connectedModel();

    transport.deliver({
      't': 'shaderSource',
      'id': 'portal',
      'source': 'vec4 shadr_main(vec2 uv, float t, vec4 c) { return c; }',
      'issues': <String>[],
    });
    expect(model.shaderDirty, isFalse, reason: 'freshly opened');

    model.editShader('vec4 shadr_main(vec2 uv, float t, vec4 c) { return c * 2.0; }');
    expect(model.shaderDirty, isTrue, reason: 'edited');

    model.saveShaderDocument();
    expect(transport.lastOfType('saveShader'), isNotNull, reason: 'save was sent');
    expect(model.shaderDirty, isFalse, reason: 'right after save');

    transport.deliver({
      't': 'shaders',
      'shaders': <Map<String, dynamic>>[
        {'id': 'portal', 'description': '', 'issues': <String>[]},
      ],
      'helpers': '',
      'preamble': '',
      'epilogue': '',
      'environment': <Map<String, dynamic>>[],
    });
    expect(model.shaderDirty, isFalse, reason: 'after the catalog broadcast');

    transport.deliver({
      't': 'shaderSaved',
      'id': 'portal',
      'issues': <String>[],
      'packRebuilt': true,
    });
    expect(model.shaderDirty, isFalse, reason: 'after shaderSaved');
  });
}
