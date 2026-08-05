package com.example.esp32controller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    secondary = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFFF8FAFC),
    outline = Color(0xFFDCE3EB),
    onSurface = Color(0xFF0F172A),
    onBackground = Color(0xFF0F172A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DB3FF),
    onPrimary = Color(0xFF04101F),
    secondary = Color(0xFFE5ECF5),
    background = Color(0xFF09111C),
    surface = Color(0xFF101A29),
    surfaceVariant = Color(0xFF162234),
    outline = Color(0xFF2C3B50),
    onSurface = Color(0xFFF7FAFC),
    onBackground = Color(0xFFF7FAFC)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun Esp32ControllerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
