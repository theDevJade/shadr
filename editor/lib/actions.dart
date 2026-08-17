import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' hide Element;

import 'model.dart';

class UndoIntent extends Intent {
  const UndoIntent();
}

class RedoIntent extends Intent {
  const RedoIntent();
}

class SaveIntent extends Intent {
  const SaveIntent();
}

class ReloadIntent extends Intent {
  const ReloadIntent();
}

class DeleteSelectionIntent extends Intent {
  const DeleteSelectionIntent();
}

class SelectAllIntent extends Intent {
  const SelectAllIntent();
}

class ClearSelectionIntent extends Intent {
  const ClearSelectionIntent();
}

class ToggleSnappingIntent extends Intent {
  const ToggleSnappingIntent();
}

class ZoomInIntent extends Intent {
  const ZoomInIntent();
}

class ZoomOutIntent extends Intent {
  const ZoomOutIntent();
}

class ZoomToFitIntent extends Intent {
  const ZoomToFitIntent();
}

class ZoomToActualSizeIntent extends Intent {
  const ZoomToActualSizeIntent();
}

class BringForwardIntent extends Intent {
  const BringForwardIntent();
}

class SendBackwardIntent extends Intent {
  const SendBackwardIntent();
}

class BringToFrontIntent extends Intent {
  const BringToFrontIntent();
}

class SendToBackIntent extends Intent {
  const SendToBackIntent();
}

class NudgeIntent extends Intent {
  const NudgeIntent(this.delta, {this.coarse = false});

  final Offset delta;

  final bool coarse;
}

const Map<ShortcutActivator, Intent> editorShortcuts = <ShortcutActivator, Intent>{
  SingleActivator(LogicalKeyboardKey.keyZ, control: true): UndoIntent(),
  SingleActivator(LogicalKeyboardKey.keyZ, meta: true): UndoIntent(),
  SingleActivator(LogicalKeyboardKey.keyZ, control: true, shift: true): RedoIntent(),
  SingleActivator(LogicalKeyboardKey.keyZ, meta: true, shift: true): RedoIntent(),
  SingleActivator(LogicalKeyboardKey.keyS, control: true): SaveIntent(),
  SingleActivator(LogicalKeyboardKey.keyS, meta: true): SaveIntent(),
  SingleActivator(LogicalKeyboardKey.keyR, control: true): ReloadIntent(),
  SingleActivator(LogicalKeyboardKey.keyR, meta: true): ReloadIntent(),
  SingleActivator(LogicalKeyboardKey.keyA, control: true): SelectAllIntent(),
  SingleActivator(LogicalKeyboardKey.keyA, meta: true): SelectAllIntent(),

  SingleActivator(LogicalKeyboardKey.delete): DeleteSelectionIntent(),
  SingleActivator(LogicalKeyboardKey.backspace): DeleteSelectionIntent(),
  SingleActivator(LogicalKeyboardKey.escape): ClearSelectionIntent(),

  SingleActivator(LogicalKeyboardKey.keyF): ZoomToFitIntent(),
  SingleActivator(LogicalKeyboardKey.digit0, control: true): ZoomToActualSizeIntent(),
  SingleActivator(LogicalKeyboardKey.digit0, meta: true): ZoomToActualSizeIntent(),
  SingleActivator(LogicalKeyboardKey.equal, control: true): ZoomInIntent(),
  SingleActivator(LogicalKeyboardKey.equal, meta: true): ZoomInIntent(),
  SingleActivator(LogicalKeyboardKey.minus, control: true): ZoomOutIntent(),
  SingleActivator(LogicalKeyboardKey.minus, meta: true): ZoomOutIntent(),
  SingleActivator(LogicalKeyboardKey.equal): ZoomInIntent(),
  SingleActivator(LogicalKeyboardKey.add): ZoomInIntent(),
  SingleActivator(LogicalKeyboardKey.numpadAdd): ZoomInIntent(),
  SingleActivator(LogicalKeyboardKey.minus): ZoomOutIntent(),
  SingleActivator(LogicalKeyboardKey.numpadSubtract): ZoomOutIntent(),

  SingleActivator(LogicalKeyboardKey.bracketRight, control: true): BringForwardIntent(),
  SingleActivator(LogicalKeyboardKey.bracketRight, meta: true): BringForwardIntent(),
  SingleActivator(LogicalKeyboardKey.bracketLeft, control: true): SendBackwardIntent(),
  SingleActivator(LogicalKeyboardKey.bracketLeft, meta: true): SendBackwardIntent(),
  SingleActivator(LogicalKeyboardKey.bracketRight, control: true, shift: true): BringToFrontIntent(),
  SingleActivator(LogicalKeyboardKey.bracketRight, meta: true, shift: true): BringToFrontIntent(),
  SingleActivator(LogicalKeyboardKey.bracketLeft, control: true, shift: true): SendToBackIntent(),
  SingleActivator(LogicalKeyboardKey.bracketLeft, meta: true, shift: true): SendToBackIntent(),

  SingleActivator(LogicalKeyboardKey.arrowLeft): NudgeIntent(Offset(-1, 0)),
  SingleActivator(LogicalKeyboardKey.arrowRight): NudgeIntent(Offset(1, 0)),
  SingleActivator(LogicalKeyboardKey.arrowUp): NudgeIntent(Offset(0, -1)),
  SingleActivator(LogicalKeyboardKey.arrowDown): NudgeIntent(Offset(0, 1)),
  SingleActivator(LogicalKeyboardKey.arrowLeft, shift: true): NudgeIntent(Offset(-1, 0), coarse: true),
  SingleActivator(LogicalKeyboardKey.arrowRight, shift: true): NudgeIntent(Offset(1, 0), coarse: true),
  SingleActivator(LogicalKeyboardKey.arrowUp, shift: true): NudgeIntent(Offset(0, -1), coarse: true),
  SingleActivator(LogicalKeyboardKey.arrowDown, shift: true): NudgeIntent(Offset(0, 1), coarse: true),

  SingleActivator(LogicalKeyboardKey.keyG, shift: true): ToggleSnappingIntent(),
};

bool textEditingHasFocus() {
  final context = FocusManager.instance.primaryFocus?.context;
  if (context == null) return false;
  return context.findAncestorWidgetOfExactType<EditableText>() != null;
}

class EditorShortcutManager extends ShortcutManager {
  EditorShortcutManager({required super.shortcuts});

  static final _globalWhileTyping = <LogicalKeyboardKey>{
    LogicalKeyboardKey.keyS,
    LogicalKeyboardKey.keyR,
  };

  @override
  KeyEventResult handleKeypress(BuildContext context, KeyEvent event) {
    if (!textEditingHasFocus()) return super.handleKeypress(context, event);

    if (event.logicalKey == LogicalKeyboardKey.escape) {
      if (event is KeyDownEvent) FocusManager.instance.primaryFocus?.unfocus();
      return KeyEventResult.handled;
    }

    final keyboard = HardwareKeyboard.instance;
    final command = keyboard.isControlPressed || keyboard.isMetaPressed;
    if (command && _globalWhileTyping.contains(event.logicalKey)) {
      return super.handleKeypress(context, event);
    }
    return KeyEventResult.ignored;
  }
}

abstract class _ModelAction<T extends Intent> extends Action<T> {
  _ModelAction(this.model) {
    model.addListener(_onModelChanged);
  }

  final EditorModel model;

  void _onModelChanged() => notifyActionListeners();

  void detach() => model.removeListener(_onModelChanged);
}

class _Undo extends _ModelAction<UndoIntent> {
  _Undo(super.model);

  @override
  bool isEnabled(UndoIntent intent) => model.snapshot?.canUndo ?? false;

  @override
  void invoke(UndoIntent intent) => model.undo();
}

class _Redo extends _ModelAction<RedoIntent> {
  _Redo(super.model);

  @override
  bool isEnabled(RedoIntent intent) => model.snapshot?.canRedo ?? false;

  @override
  void invoke(RedoIntent intent) => model.redo();
}

class _Save extends _ModelAction<SaveIntent> {
  _Save(super.model);

  @override
  bool isEnabled(SaveIntent intent) => model.snapshot != null;

  @override
  void invoke(SaveIntent intent) => model.save();
}

class _Reload extends _ModelAction<ReloadIntent> {
  _Reload(super.model);

  @override
  bool isEnabled(ReloadIntent intent) => model.snapshot != null;

  @override
  void invoke(ReloadIntent intent) => model.reload();
}

class _DeleteSelection extends _ModelAction<DeleteSelectionIntent> {
  _DeleteSelection(super.model);

  @override
  bool isEnabled(DeleteSelectionIntent intent) =>
      model.selection.isNotEmpty && !model.isPreviewing;

  @override
  void invoke(DeleteSelectionIntent intent) => model.deleteSelection();
}

class _SelectAll extends _ModelAction<SelectAllIntent> {
  _SelectAll(super.model);

  @override
  bool isEnabled(SelectAllIntent intent) => (model.snapshot?.elements.isNotEmpty ?? false);

  @override
  void invoke(SelectAllIntent intent) => model.selectAll();
}

class _ClearSelection extends _ModelAction<ClearSelectionIntent> {
  _ClearSelection(super.model);

  @override
  bool isEnabled(ClearSelectionIntent intent) => model.selection.isNotEmpty;

  @override
  void invoke(ClearSelectionIntent intent) => model.clearSelection();
}

class _ToggleSnapping extends _ModelAction<ToggleSnappingIntent> {
  _ToggleSnapping(super.model);

  @override
  void invoke(ToggleSnappingIntent intent) => model.setSnapping(!model.snapEnabled);
}

class _Nudge extends _ModelAction<NudgeIntent> {
  _Nudge(super.model);

  @override
  bool isEnabled(NudgeIntent intent) => model.selection.isNotEmpty && !model.isPreviewing;

  @override
  void invoke(NudgeIntent intent) => model.nudge(intent.delta * (intent.coarse ? 10 : 1));
}

class _Reorder<T extends Intent> extends _ModelAction<T> {
  _Reorder(super.model, this.move);

  final void Function() move;

  @override
  bool isEnabled(T intent) => model.canReorder;

  @override
  void invoke(T intent) => move();
}

class _ZoomBy<T extends Intent> extends _ModelAction<T> {
  _ZoomBy(super.model, this.viewportSize, this.factor);

  final Size Function() viewportSize;
  final double factor;

  @override
  void invoke(T intent) {
    final size = viewportSize();
    model.zoomAt(Offset(size.width / 2, size.height / 2), factor);
  }
}

class _ZoomToFit extends _ModelAction<ZoomToFitIntent> {
  _ZoomToFit(super.model, this.viewportSize);

  final Size Function() viewportSize;

  @override
  bool isEnabled(ZoomToFitIntent intent) => model.snapshot != null;

  @override
  void invoke(ZoomToFitIntent intent) => model.fit(viewportSize());
}

class _ZoomToActualSize extends _ModelAction<ZoomToActualSizeIntent> {
  _ZoomToActualSize(super.model, this.viewportSize);

  final Size Function() viewportSize;

  @override
  bool isEnabled(ZoomToActualSizeIntent intent) => model.snapshot != null;

  @override
  void invoke(ZoomToActualSizeIntent intent) => model.zoomTo(1, viewportSize());
}

class EditorActionRegistry {
  EditorActionRegistry(EditorModel model, Size Function() viewportSize)
      : map = <Type, Action<Intent>>{
          UndoIntent: _Undo(model),
          RedoIntent: _Redo(model),
          SaveIntent: _Save(model),
          ReloadIntent: _Reload(model),
          DeleteSelectionIntent: _DeleteSelection(model),
          SelectAllIntent: _SelectAll(model),
          ClearSelectionIntent: _ClearSelection(model),
          ToggleSnappingIntent: _ToggleSnapping(model),
          NudgeIntent: _Nudge(model),
          BringForwardIntent: _Reorder<BringForwardIntent>(model, model.bringForward),
          SendBackwardIntent: _Reorder<SendBackwardIntent>(model, model.sendBackward),
          BringToFrontIntent: _Reorder<BringToFrontIntent>(model, model.bringToFront),
          SendToBackIntent: _Reorder<SendToBackIntent>(model, model.sendToBack),
          ZoomInIntent: _ZoomBy<ZoomInIntent>(model, viewportSize, 1.25),
          ZoomOutIntent: _ZoomBy<ZoomOutIntent>(model, viewportSize, 0.8),
          ZoomToFitIntent: _ZoomToFit(model, viewportSize),
          ZoomToActualSizeIntent: _ZoomToActualSize(model, viewportSize),
        };

  final Map<Type, Action<Intent>> map;

  void dispose() {
    for (final action in map.values) {
      if (action is _ModelAction) action.detach();
    }
  }
}
