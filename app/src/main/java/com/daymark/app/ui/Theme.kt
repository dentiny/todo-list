package com.daymark.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.daymark.app.domain.Priority

val Paper = Color(0xFFF6F4EF)
val Ink = Color(0xFF252D28)
val Muted = Color(0xFF686F65)
val Forest = Color(0xFF315C49)
val Line = Color(0xFFE2E4DA)
val Clay = Color(0xFFA54D39)
val Olive = Color(0xFF6A713C)
val Slate = Color(0xFF526E83)
fun Priority.color() = when (this) { Priority.P0 -> Clay; Priority.P1 -> Olive; Priority.P2 -> Slate }

@Composable fun DaymarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = Color.White, primaryContainer = Color(0xFFE4EBDF),
            onPrimaryContainer = Forest, secondary = Olive, secondaryContainer = Color(0xFFE7EAD9), onSecondaryContainer = Olive,
            tertiary = Slate, tertiaryContainer = Color(0xFFE3EAF0), onTertiaryContainer = Slate,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink, surfaceVariant = Color(0xFFEEEFE7), onSurfaceVariant = Muted,
            outline = Color(0xFF848B80), outlineVariant = Line, error = Clay,
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
        ),
        content = content,
    )
}
