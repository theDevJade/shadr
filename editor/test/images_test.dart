import 'package:flutter_test/flutter_test.dart';

import 'support.dart';

void main() {
  test('the image list arrives from the wire', () {
    final (:model, :transport) = connectedModel();
    transport.deliver({
      't': 'images',
      'images': [
        {'name': 'logo', 'unicode': 'AB', 'columns': 2, 'rows': 1},
      ],
    });

    expect(model.images.single.name, 'logo');
    expect(model.images.single.unicode, 'AB');
    expect(model.images.single.columns, 2);
  });

  test('uploading sends the name and the payload', () {
    final (:model, :transport) = connectedModel();
    model.uploadImage('logo', 'aGk=');

    final sent = transport.lastOfType('uploadImage')!;
    expect(sent['name'], 'logo');
    expect(sent['data'], 'aGk=');
  });

  test('inserting adds an image element sized to its tiles, then points it at the glyphs', () {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: []));
    transport.deliver({
      't': 'images',
      'images': [
        {'name': 'logo', 'unicode': 'AB', 'columns': 2, 'rows': 3},
      ],
    });

    model.insertImage(model.images.single);

    final add = transport.lastOfType('add')!;
    expect(add['type'], 'image');
    expect(add['width'], 128);
    expect(add['height'], 192);

    transport.deliver(snapshotJson(elements: [block('el_image_1', type: 'IMAGE')]));

    final patch = transport.lastOfType('patch')!;
    expect(patch['elementId'], 'el_image_1');
    expect(patch['changes']['font'], 'uiimages');
    expect(patch['changes']['unicode'], 'AB');
  });

  test('a snapshot with no pending insert does not patch anything', () {
    final (:model, :transport) = connectedModel();
    transport.deliver(snapshotJson(elements: [block('a', type: 'IMAGE')]));

    expect(transport.lastOfType('patch'), isNull);
  });
}
