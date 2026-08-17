import 'package:flutter/material.dart';

@immutable
class EditorTokens extends ThemeExtension<EditorTokens> {
  const EditorTokens({
    required this.canvasVoid,
    required this.canvasPage,
    required this.surface,
    required this.surfaceRaised,
    required this.surfaceSunken,
    required this.surfaceSelected,
    required this.border,
    required this.borderStrong,
    required this.textPrimary,
    required this.textSecondary,
    required this.textTertiary,
    required this.accent,
    required this.accentMuted,
    required this.attention,
    required this.danger,
    required this.guide,
    required this.hitbox,
  });

  final Color canvasVoid;

  final Color canvasPage;

  final Color surface;
  final Color surfaceRaised;

  final Color surfaceSunken;
  final Color surfaceSelected;

  final Color border;
  final Color borderStrong;

  final Color textPrimary;
  final Color textSecondary;
  final Color textTertiary;

  final Color accent;

  final Color accentMuted;

  final Color attention;
  final Color danger;

  final Color guide;

  final Color hitbox;

  static const dark = EditorTokens(
    canvasVoid: Color(0xFF0E0E11),
    canvasPage: Color(0xFF141418),
    surface: Color(0xFF16161A),
    surfaceRaised: Color(0xFF1C1C21),
    surfaceSunken: Color(0xFF101013),
    surfaceSelected: Color(0xFF1E3038),
    border: Color(0xFF26262C),
    borderStrong: Color(0xFF34343C),
    textPrimary: Color(0xFFE6E6EA),
    textSecondary: Color(0xFF9A9AA5),
    textTertiary: Color(0xFF6B6B76),
    accent: Color(0xFF4CC9F0),
    accentMuted: Color(0x334CC9F0),
    attention: Color(0xFFE9A15C),
    danger: Color(0xFFF2545B),
    guide: Color(0xFFF2545B),
    hitbox: Color(0xFFFF4D9D),
  );

  @override
  EditorTokens copyWith({
    Color? canvasVoid,
    Color? canvasPage,
    Color? surface,
    Color? surfaceRaised,
    Color? surfaceSunken,
    Color? surfaceSelected,
    Color? border,
    Color? borderStrong,
    Color? textPrimary,
    Color? textSecondary,
    Color? textTertiary,
    Color? accent,
    Color? accentMuted,
    Color? attention,
    Color? danger,
    Color? guide,
    Color? hitbox,
  }) =>
      EditorTokens(
        canvasVoid: canvasVoid ?? this.canvasVoid,
        canvasPage: canvasPage ?? this.canvasPage,
        surface: surface ?? this.surface,
        surfaceRaised: surfaceRaised ?? this.surfaceRaised,
        surfaceSunken: surfaceSunken ?? this.surfaceSunken,
        surfaceSelected: surfaceSelected ?? this.surfaceSelected,
        border: border ?? this.border,
        borderStrong: borderStrong ?? this.borderStrong,
        textPrimary: textPrimary ?? this.textPrimary,
        textSecondary: textSecondary ?? this.textSecondary,
        textTertiary: textTertiary ?? this.textTertiary,
        accent: accent ?? this.accent,
        accentMuted: accentMuted ?? this.accentMuted,
        attention: attention ?? this.attention,
        danger: danger ?? this.danger,
        guide: guide ?? this.guide,
        hitbox: hitbox ?? this.hitbox,
      );

  @override
  EditorTokens lerp(EditorTokens? other, double t) {
    if (other == null) return this;
    Color mix(Color a, Color b) => Color.lerp(a, b, t)!;
    return EditorTokens(
      canvasVoid: mix(canvasVoid, other.canvasVoid),
      canvasPage: mix(canvasPage, other.canvasPage),
      surface: mix(surface, other.surface),
      surfaceRaised: mix(surfaceRaised, other.surfaceRaised),
      surfaceSunken: mix(surfaceSunken, other.surfaceSunken),
      surfaceSelected: mix(surfaceSelected, other.surfaceSelected),
      border: mix(border, other.border),
      borderStrong: mix(borderStrong, other.borderStrong),
      textPrimary: mix(textPrimary, other.textPrimary),
      textSecondary: mix(textSecondary, other.textSecondary),
      textTertiary: mix(textTertiary, other.textTertiary),
      accent: mix(accent, other.accent),
      accentMuted: mix(accentMuted, other.accentMuted),
      attention: mix(attention, other.attention),
      danger: mix(danger, other.danger),
      guide: mix(guide, other.guide),
      hitbox: mix(hitbox, other.hitbox),
    );
  }
}

extension EditorThemeAccess on BuildContext {
  EditorTokens get tokens => Theme.of(this).extension<EditorTokens>()!;
  TextTheme get texts => Theme.of(this).textTheme;
}

abstract final class Insets {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 20;
}

abstract final class Metrics {
  static const double fieldHeight = 24;
  static const double fieldGutter = 20;
}

abstract final class Corners {
  static const Radius sm = Radius.circular(4);
  static const Radius md = Radius.circular(6);
  static const BorderRadius small = BorderRadius.all(sm);
  static const BorderRadius medium = BorderRadius.all(md);
}

const String canvasFontFamily = 'JetBrainsMono Nerd Font Mono';

ThemeData buildEditorTheme() {
  const tokens = EditorTokens.dark;

  final scheme = ColorScheme.fromSeed(
    seedColor: tokens.accent,
    brightness: Brightness.dark,
  ).copyWith(
    surface: tokens.surface,
    primary: tokens.accent,
    onPrimary: const Color(0xFF06222B),
    error: tokens.danger,
  );

  final texts = TextTheme(
    titleSmall: TextStyle(fontSize: 13, height: 1.2, fontWeight: FontWeight.w600, color: tokens.textPrimary),
    bodyMedium: TextStyle(fontSize: 12, height: 1.3, color: tokens.textPrimary),
    bodySmall: TextStyle(fontSize: 11, height: 1.3, color: tokens.textSecondary),
    labelSmall: TextStyle(
      fontSize: 10,
      height: 1.2,
      letterSpacing: 0.6,
      fontWeight: FontWeight.w600,
      color: tokens.textTertiary,
    ),
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: tokens.canvasVoid,
    textTheme: texts,
    extensions: const [tokens],
    dividerTheme: DividerThemeData(color: tokens.border, space: 1, thickness: 1),

    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: tokens.surfaceSunken,
      indicatorColor: tokens.accentMuted,
      selectedIconTheme: IconThemeData(color: tokens.accent, size: 18),
      unselectedIconTheme: IconThemeData(color: tokens.textTertiary, size: 18),
      selectedLabelTextStyle: TextStyle(fontSize: 9, color: tokens.textPrimary),
      unselectedLabelTextStyle: TextStyle(fontSize: 9, color: tokens.textTertiary),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected) ? tokens.accent : tokens.textTertiary,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (states) =>
            states.contains(WidgetState.selected) ? tokens.accentMuted : tokens.surfaceSunken,
      ),
      trackOutlineColor: WidgetStateProperty.all(tokens.border),
    ),
    expansionTileTheme: ExpansionTileThemeData(
      iconColor: tokens.textTertiary,
      collapsedIconColor: tokens.textTertiary,
      textColor: tokens.textPrimary,
      collapsedTextColor: tokens.textSecondary,
    ),
    listTileTheme: ListTileThemeData(
      selectedTileColor: tokens.surfaceSelected,
      selectedColor: tokens.textPrimary,
      textColor: tokens.textSecondary,
      minVerticalPadding: 0,
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: tokens.surfaceRaised,
      contentTextStyle: TextStyle(fontSize: 12, color: tokens.textPrimary),
      behavior: SnackBarBehavior.floating,
      shape: const RoundedRectangleBorder(borderRadius: Corners.medium),
    ),
    iconTheme: IconThemeData(color: tokens.textSecondary, size: 16),
    tooltipTheme: TooltipThemeData(
      waitDuration: const Duration(milliseconds: 500),
      decoration: BoxDecoration(
        color: tokens.surfaceRaised,
        borderRadius: Corners.small,
        border: Border.all(color: tokens.borderStrong),
      ),
      textStyle: texts.bodySmall,
    ),
    visualDensity: VisualDensity.compact,
    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
  );
}
