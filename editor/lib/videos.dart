import 'dart:convert';
import 'dart:js_interop';

import 'package:flutter/material.dart' hide Element;
import 'package:web/web.dart' as web;

import 'chrome.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

class VideosWorkspace extends StatelessWidget {
  const VideosWorkspace({super.key});

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;

    return Panel(
      title: 'Videos',
      trailing: FilledButton.icon(
        onPressed: () => pickAndUploadVideo(model),
        icon: const Icon(Icons.upload_file, size: 13),
        label: const Text('Upload', style: TextStyle(fontSize: 10)),
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
      ),
      child: model.videos.isEmpty
          ? const EmptyState(
              icon: Icons.movie_outlined,
              title: 'No videos yet',
              detail: 'Upload a clip to drop it on a page. Encoding runs when the pack '
                  'rebuilds, and needs ffmpeg on the server PATH.',
            )
          : Scrollbar(
              child: GridView.builder(
                primary: true,
                padding: const EdgeInsets.all(Insets.md),
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 190,
                  mainAxisSpacing: Insets.sm,
                  crossAxisSpacing: Insets.sm,
                  childAspectRatio: 1.05,
                ),
                itemCount: model.videos.length,
                itemBuilder: (context, index) {
                  final video = model.videos[index];
                  return _VideoTile(
                    video: video,
                    onInsert: () => model.insertVideo(video),
                    onDelete: () => model.removeVideo(video.name),
                    canInsert: model.snapshot != null,
                    tokens: tokens,
                  );
                },
              ),
            ),
    );
  }
}

Future<void> pickAndUploadVideo(EditorModel model) async {
  final input = web.HTMLInputElement()
    ..type = 'file'
    ..accept = 'video/*';
  input.click();

  await input.onChange.first;
  final file = input.files?.item(0);
  if (file == null) return;

  final buffer = await file.arrayBuffer().toDart;
  final bytes = buffer.toDart.asUint8List();
  model.uploadVideo(_nameOf(file.name), _extensionOf(file.name), base64Encode(bytes));
}

String _nameOf(String fileName) {
  final stem = fileName.contains('.') ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
  final cleaned = stem.toLowerCase().replaceAll(RegExp('[^a-z0-9_]'), '_');
  return cleaned.isEmpty ? 'video' : cleaned.substring(0, cleaned.length.clamp(0, 48));
}

String _extensionOf(String fileName) {
  if (!fileName.contains('.')) return 'mp4';
  return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
}

class VideoThumbnail extends StatelessWidget {
  const VideoThumbnail({super.key, required this.data, this.fit = BoxFit.cover});

  final String data;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) => Image.memory(base64Decode(data), fit: fit, gaplessPlayback: true);
}

class _VideoTile extends StatelessWidget {
  const _VideoTile({
    required this.video,
    required this.onInsert,
    required this.onDelete,
    required this.canInsert,
    required this.tokens,
  });

  final VideoEntry video;
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
                child: ClipRRect(
                  borderRadius: Corners.small,
                  child: SizedBox.expand(
                    child: video.hasThumbnail
                        ? VideoThumbnail(data: video.thumbnail)
                        : Center(
                            child: Icon(Icons.movie, size: 26, color: tokens.textTertiary),
                          ),
                  ),
                ),
              ),
              const SizedBox(height: Insets.xs),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      video.name,
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
                video.summary,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 9,
                  color: video.issue == null ? tokens.textTertiary : tokens.danger,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
