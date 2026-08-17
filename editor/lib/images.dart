import 'dart:convert';
import 'dart:js_interop';

import 'package:flutter/material.dart' hide Element;
import 'package:web/web.dart' as web;

import 'chrome.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

class ImagesWorkspace extends StatelessWidget {
  const ImagesWorkspace({super.key});

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;

    return Panel(
      title: 'Images',
      trailing: FilledButton.icon(
        onPressed: () => pickAndUpload(context, model),
        icon: const Icon(Icons.upload_file, size: 13),
        label: const Text('Upload', style: TextStyle(fontSize: 10)),
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
      ),
      child: model.images.isEmpty
          ? EmptyState(
              icon: Icons.image_outlined,
              title: 'No images yet',
              detail: 'Upload a PNG to use it as an element. '
                  'Every image becomes glyphs in the pack, so it costs no extra entities.',
            )
          : Scrollbar(
              child: GridView.builder(
                primary: true,
                padding: const EdgeInsets.all(Insets.md),
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 150,
                  mainAxisSpacing: Insets.sm,
                  crossAxisSpacing: Insets.sm,
                  childAspectRatio: 1.15,
                ),
                itemCount: model.images.length,
                itemBuilder: (context, index) {
                  final image = model.images[index];
                  return _ImageTile(
                    image: image,
                    onInsert: () => model.insertImage(image),
                    onDelete: () => model.removeImage(image.name),
                    canInsert: model.snapshot != null,
                    tokens: tokens,
                  );
                },
              ),
            ),
    );
  }
}

Future<void> pickAndUpload(BuildContext context, EditorModel model) async {
  final input = web.HTMLInputElement()
    ..type = 'file'
    ..accept = 'image/png';
  input.click();

  await input.onChange.first;
  final file = input.files?.item(0);
  if (file == null) return;

  final buffer = await file.arrayBuffer().toDart;
  final bytes = buffer.toDart.asUint8List();
  model.uploadImage(_nameOf(file.name), base64Encode(bytes));
}

String _nameOf(String fileName) {
  final stem = fileName.contains('.') ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
  final cleaned = stem.toLowerCase().replaceAll(RegExp('[^a-z0-9_]'), '_');
  return cleaned.isEmpty ? 'image' : cleaned.substring(0, cleaned.length.clamp(0, 48));
}

class _ImageTile extends StatelessWidget {
  const _ImageTile({
    required this.image,
    required this.onInsert,
    required this.onDelete,
    required this.canInsert,
    required this.tokens,
  });

  final ImageEntry image;
  final VoidCallback onInsert;
  final VoidCallback onDelete;
  final bool canInsert;
  final EditorTokens tokens;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: canInsert ? 'Add to the page' : 'Open a page first',
      child: InkWell(
        onTap: canInsert ? onInsert : null,
        borderRadius: Corners.small,
        child: Container(
          decoration: BoxDecoration(
            color: tokens.surfaceSunken,
            borderRadius: Corners.small,
            border: Border.all(color: tokens.border),
          ),
          padding: const EdgeInsets.all(Insets.sm),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Center(
                  child: Icon(Icons.image, size: 26, color: tokens.textTertiary),
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      image.name,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11),
                    ),
                  ),
                  InkWell(
                    onTap: onDelete,
                    child: Icon(Icons.close, size: 12, color: tokens.textTertiary),
                  ),
                ],
              ),
              Text(
                '${image.columns}x${image.rows} tiles',
                style: TextStyle(fontSize: 9, color: tokens.textTertiary),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
