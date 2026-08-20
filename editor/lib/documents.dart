import 'package:flutter/material.dart' hide Element;
import 'package:flutter/services.dart';

import 'chrome.dart';
import 'fields.dart';
import 'model.dart';
import 'protocol.dart';
import 'theme.dart';

final _documentName = RegExp(r'^[a-z0-9][a-z0-9_-]{0,47}$');

const _nameHelp = 'lowercase letters, digits, dashes and underscores';

class DocumentBar extends StatelessWidget {
  const DocumentBar({super.key});

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final open = model.openRef;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const _DocumentPicker(),
        const SizedBox(width: Insets.xs),
        ToolButton(
          icon: Icons.add,
          tooltip: 'New page or component',
          onPressed: () => showNewDocumentDialog(context, model),
        ),
        if (open != null)
          _DocumentMenu(ref: open, model: model),
      ],
    );
  }
}

class _DocumentPicker extends StatelessWidget {
  const _DocumentPicker();

  @override
  Widget build(BuildContext context) {
    final model = EditorScope.of(context);
    final tokens = context.tokens;
    final open = model.openRef;
    final pages = model.documents.where((d) => d.kind == DocumentKind.page).toList();
    final components = model.documents.where((d) => d.kind == DocumentKind.component).toList();

    return Container(
      height: 24,
      padding: const EdgeInsets.symmetric(horizontal: Insets.sm),
      decoration: BoxDecoration(
        color: tokens.surfaceSunken,
        borderRadius: Corners.small,
        border: Border.all(color: tokens.border),
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<DocumentRef>(
          value: model.documents.contains(open) ? open : null,
          isDense: true,
          hint: Text(
            model.documents.isEmpty ? 'Nothing yet' : 'Open…',
            style: context.texts.bodySmall,
          ),
          dropdownColor: tokens.surfaceRaised,
          icon: Icon(Icons.expand_more, size: 14, color: tokens.textTertiary),
          style: context.texts.bodyMedium,
          selectedItemBuilder: (context) => [
            for (final ref in [...pages, ...components])
              Align(
                alignment: Alignment.centerLeft,
                child: Text(ref.name, style: context.texts.bodyMedium),
              ),
          ],
          items: [
            for (final ref in [...pages, ...components])
              DropdownMenuItem(
                value: ref,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      ref.kind == DocumentKind.component
                          ? Icons.extension_outlined
                          : Icons.description_outlined,
                      size: 13,
                      color: ref.kind == DocumentKind.component
                          ? tokens.attention
                          : tokens.textTertiary,
                    ),
                    const SizedBox(width: Insets.sm),
                    Text(ref.name, style: context.texts.bodyMedium),
                  ],
                ),
              ),
          ],
          onChanged: (ref) {
            if (ref != null) model.open(ref);
          },
        ),
      ),
    );
  }
}

class _DocumentMenu extends StatelessWidget {
  const _DocumentMenu({required this.ref, required this.model});

  final DocumentRef ref;
  final EditorModel model;

  @override
  Widget build(BuildContext context) => MenuAnchor(
        menuChildren: [
          MenuItemButton(
            leadingIcon: const Icon(Icons.drive_file_rename_outline, size: 15),
            onPressed: () => _rename(context),
            child: const Text('Rename…'),
          ),
          MenuItemButton(
            leadingIcon: const Icon(Icons.copy_all_outlined, size: 15),
            onPressed: () => _duplicate(context),
            child: const Text('Duplicate…'),
          ),
          MenuItemButton(
            leadingIcon: const Icon(Icons.delete_outline, size: 15),
            onPressed: () => _delete(context),
            child: const Text('Delete…'),
          ),
        ],
        builder: (context, controller, _) => ToolButton(
          icon: Icons.more_horiz,
          tooltip: 'Rename, duplicate or delete ${ref.name}',
          onPressed: () => controller.isOpen ? controller.close() : controller.open(),
        ),
      );

  Future<void> _rename(BuildContext context) async {
    final to = await promptForName(
      context,
      title: 'Rename ${ref.name}',
      initial: ref.name,
      action: 'Rename',
    );
    if (to != null && to != ref.name) model.renameDocument(ref, to);
  }

  Future<void> _duplicate(BuildContext context) async {
    final to = await promptForName(
      context,
      title: 'Duplicate ${ref.name}',
      initial: '${ref.name}_copy',
      action: 'Duplicate',
    );
    if (to != null) model.duplicateDocument(ref, to);
  }

  Future<void> _delete(BuildContext context) async {
    final kind = ref.kind == DocumentKind.component ? 'component' : 'page';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Delete $kind ${ref.name}?'),
        content: Text(
          'The file is removed from disk. Anything still pointing at this $kind will stop '
          'resolving.',
          style: context.texts.bodySmall,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed ?? false) model.deleteDocument(ref);
  }
}

Future<void> showNewDocumentDialog(BuildContext context, EditorModel model) async {
  final request = await showDialog<_NewDocument>(
    context: context,
    builder: (context) => const _NewDocumentDialog(),
  );
  if (request == null) return;
  model.createDocument(
    DocumentRef(name: request.name, kind: request.kind),
    hud: request.hud,
    width: request.width,
    height: request.height,
  );
}

class _NewDocument {
  const _NewDocument({
    required this.name,
    required this.kind,
    required this.hud,
    required this.width,
    required this.height,
  });

  final String name;
  final DocumentKind kind;
  final bool hud;
  final double width;
  final double height;
}

class _NewDocumentDialog extends StatefulWidget {
  const _NewDocumentDialog();

  @override
  State<_NewDocumentDialog> createState() => _NewDocumentDialogState();
}

class _NewDocumentDialogState extends State<_NewDocumentDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();

  DocumentKind _kind = DocumentKind.page;
  bool _hud = false;
  double _width = 1920;
  double _height = 1080;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  void _submit() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    Navigator.of(context).pop(
      _NewDocument(
        name: _name.text.trim(),
        kind: _kind,
        hud: _kind == DocumentKind.page && _hud,
        width: _width,
        height: _height,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final isPage = _kind == DocumentKind.page;

    return AlertDialog(
      backgroundColor: tokens.surfaceRaised,
      shape: const RoundedRectangleBorder(borderRadius: Corners.medium),
      title: Text('New document', style: context.texts.titleSmall),
      content: SizedBox(
        width: 360,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ChoiceField<DocumentKind>(
                value: _kind,
                options: const [
                  (value: DocumentKind.page, icon: null, label: 'Page'),
                  (value: DocumentKind.component, icon: null, label: 'Component'),
                ],
                onChanged: (kind) => setState(() => _kind = kind),
              ),
              const SizedBox(height: Insets.sm),
              Text(
                isPage
                    ? 'A page is a screen of its own, opened for a player.'
                    : 'A component is a piece other pages place, with parameters they fill in.',
                style: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
              ),
              const SizedBox(height: Insets.md),
              TextFormField(
                controller: _name,
                autofocus: true,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                inputFormatters: [
                  FilteringTextInputFormatter.allow(RegExp(r'[a-zA-Z0-9_-]')),
                ],
                decoration: const InputDecoration(
                  labelText: 'name',
                  helperText: _nameHelp,
                ),
                validator: (value) {
                  final name = (value ?? '').trim();
                  if (name.isEmpty) return 'required';
                  if (!_documentName.hasMatch(name)) return _nameHelp;
                  return null;
                },
                onFieldSubmitted: (_) => _submit(),
              ),
              if (isPage) ...[
                const SizedBox(height: Insets.md),
                _HudChoice(value: _hud, onChanged: (v) => setState(() => _hud = v)),
                const SizedBox(height: Insets.md),
                Row(
                  children: [
                    Expanded(
                      child: ScrubField(
                        label: 'w',
                        value: _width,
                        min: 16,
                        onChanged: (v) => setState(() => _width = v),
                      ),
                    ),
                    const SizedBox(width: Insets.sm),
                    Expanded(
                      child: ScrubField(
                        label: 'h',
                        value: _height,
                        min: 16,
                        onChanged: (v) => setState(() => _height = v),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(onPressed: _submit, child: const Text('Create')),
      ],
    );
  }
}

class _HudChoice extends StatelessWidget {
  const _HudChoice({required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ChoiceField<bool>(
          value: value,
          options: const [
            (value: false, icon: null, label: 'Menu'),
            (value: true, icon: null, label: 'HUD'),
          ],
          onChanged: onChanged,
        ),
        const SizedBox(height: Insets.sm),
        Text(
          value
              ? 'A HUD draws over the game and leaves the camera alone, so the player keeps '
                  'playing. It has no cursor.'
              : 'A menu locks the camera and gives the player a cursor to click with.',
          style: context.texts.bodySmall?.copyWith(color: tokens.textTertiary),
        ),
      ],
    );
  }
}

Future<String?> promptForName(
  BuildContext context, {
  required String title,
  required String initial,
  required String action,
}) async {
  final controller = TextEditingController(text: initial);
  final formKey = GlobalKey<FormState>();

  String? validate(String? value) {
    final name = (value ?? '').trim();
    if (name.isEmpty) return 'required';
    if (!_documentName.hasMatch(name)) return _nameHelp;
    return null;
  }

  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: context.tokens.surfaceRaised,
      shape: const RoundedRectangleBorder(borderRadius: Corners.medium),
      title: Text(title, style: context.texts.titleSmall),
      content: SizedBox(
        width: 320,
        child: Form(
          key: formKey,
          child: TextFormField(
            controller: controller,
            autofocus: true,
            autovalidateMode: AutovalidateMode.onUserInteraction,
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[a-zA-Z0-9_-]'))],
            decoration: const InputDecoration(labelText: 'name', helperText: _nameHelp),
            validator: validate,
            onFieldSubmitted: (value) {
              if (formKey.currentState?.validate() ?? false) {
                Navigator.of(context).pop(value.trim());
              }
            },
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            if (formKey.currentState?.validate() ?? false) {
              Navigator.of(context).pop(controller.text.trim());
            }
          },
          child: Text(action),
        ),
      ],
    ),
  );
  return (result?.isEmpty ?? true) ? null : result;
}
