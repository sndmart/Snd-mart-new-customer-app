package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

private val DarkColorScheme = darkColorScheme(
    primary = NaturalPrimaryLight,
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = NaturalPrimaryContainer,
    secondary = Color(0xFF90CEF4),
    onSecondary = Color(0xFF003454),
    secondaryContainer = Color(0xFF004B77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = AmberAccent,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    error = Color(0xFFF2B8B5)
)

private val LightColorScheme = lightColorScheme(
    primary = NaturalPrimary,
    onPrimary = Color.White,
    primaryContainer = NaturalPrimaryContainer,
    onPrimaryContainer = NaturalOnPrimaryContainer,
    secondary = NaturalOceanBlue,
    onSecondary = Color.White,
    secondaryContainer = NaturalOceanBlueContainer,
    onSecondaryContainer = Color(0xFF001D32),
    tertiary = AmberAccent,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = OutlineBorder,
    error = ErrorRed
)

// Wraps the ambient ripple indication so every clickable, Button, IconButton, etc. that
// relies on LocalIndication (i.e. almost everything) gives a short haptic tick on tap,
// like Swiggy/Zomato-style tactile feedback, without touching each screen individually.
// Delegates actual visuals to the original indication's node so ripple still renders.
private class HapticIndicationNode(
    private val interactionSource: InteractionSource,
    private val original: Indication
) : DelegatingNode(), CompositionLocalConsumerModifierNode {

    override fun onAttach() {
        if (original is IndicationNodeFactory) {
            delegate(original.create(interactionSource))
        }
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }
}

private class HapticIndicationNodeFactory(private val original: Indication) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HapticIndicationNode(interactionSource, original)

    override fun equals(other: Any?): Boolean =
        other is HapticIndicationNodeFactory && other.original == original

    override fun hashCode(): Int = original.hashCode()
}

@Composable
private fun rememberHapticIndication(original: Indication): Indication =
    remember(original) { HapticIndicationNodeFactory(original) }

@Composable
fun SndmartTheme(
    darkTheme: Boolean = false, // Keep Natural Tones design theme consistent
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography) {
        CompositionLocalProvider(LocalIndication provides rememberHapticIndication(LocalIndication.current)) {
            content()
        }
    }
}
