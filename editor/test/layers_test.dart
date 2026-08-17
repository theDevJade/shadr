import 'package:flutter_test/flutter_test.dart';
import 'package:shadr_editor/layers.dart';
import 'package:shadr_editor/protocol.dart';

import 'support.dart';

void main() {
  test('children nest under the element they were authored inside', () {
    final roots = buildLayerTree([
      block('backdrop', sourcePath: '0'),
      block('card', sourcePath: '1'),
      block('title', sourcePath: '1.children.0'),
      block('body', sourcePath: '1.children.1'),
    ]);

    expect(roots.map((n) => n.element.id), ['backdrop', 'card']);
    final card = roots.firstWhere((n) => n.element.id == 'card');
    expect(card.children.map((n) => n.element.id), ['title', 'body']);
  });

  test('a grid slot nests too, since its children are still authored nodes', () {
    final roots = buildLayerTree([
      block('row', sourcePath: '0'),
      block('chip_a', sourcePath: '0.grid/0'),
      block('chip_b', sourcePath: '0.grid/1'),
    ]);

    expect(roots.single.element.id, 'row');
    expect(roots.single.children.map((n) => n.element.id), ['chip_a', 'chip_b']);
  });

  test('nesting is recursive', () {
    final roots = buildLayerTree([
      block('a', sourcePath: '0'),
      block('b', sourcePath: '0.children.0'),
      block('c', sourcePath: '0.children.0.children.0'),
    ]);

    expect(roots.single.children.single.children.single.element.id, 'c');
  });

  test('an element whose parent is absent becomes a root', () {
    final roots = buildLayerTree([
      block('orphan', sourcePath: '3.children.0'),
    ]);
    expect(roots.map((n) => n.element.id), ['orphan']);
  });

  test('siblings are ordered topmost first, matching the canvas', () {
    final roots = buildLayerTree([
      block('back', sourcePath: '0', layer: 1),
      block('front', sourcePath: '1', layer: 9),
      block('middle', sourcePath: '2', layer: 5),
    ]);
    expect(roots.map((n) => n.element.id), ['front', 'middle', 'back']);
  });

  test('ordering applies at every depth', () {
    final roots = buildLayerTree([
      block('card', sourcePath: '0', layer: 1),
      block('low', sourcePath: '0.children.0', layer: 2),
      block('high', sourcePath: '0.children.1', layer: 8),
    ]);
    expect(roots.single.children.map((n) => n.element.id), ['high', 'low']);
  });

  test('elements with no source path are all roots', () {
    final roots = buildLayerTree([block('a'), block('b')]);
    expect(roots.length, 2);
  });

  test('a blur panel sorts behind everything, whatever layer it was authored at', () {
    final roots = buildLayerTree([
      block('bg', layer: 0),
      block('panel', type: 'BLUR', layer: 14),
      block('label', layer: 5),
    ]);

    expect(roots.map((n) => n.element.id), ['label', 'bg', 'panel']);
  });

  test('the blur panel reports the reserved layer', () {
    expect(block('p', type: 'BLUR', layer: 14).effectiveLayer, blurPanelLayer);
    expect(block('q', layer: 14).effectiveLayer, 14);
  });
}
