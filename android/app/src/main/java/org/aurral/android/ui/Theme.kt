package org.aurral.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Glass {
    val Night = Color(0xFF08131F)
    val Deep = Color(0xFF0E2236)
    val Panel = Color(0xFF132B44)
    val Line = Color(0x3384C8FF)
    val Aqua = Color(0xFF4FB8FF)
    val Sky = Color(0xFF9EDCFF)
    val Muted = Color(0xFF9BB3C9)

    /** The glossy title/command bar band. */
    val bar = Brush.verticalGradient(
        0f to Color(0xFF3B6189),
        0.48f to Color(0xFF1D3B5C),
        0.52f to Color(0xFF132C47),
        1f to Color(0xFF0E2236),
    )
    val page = Brush.verticalGradient(listOf(Color(0xFF10263D), Night))
    val orb = Brush.radialGradient(
        0f to Color(0xFF9EE2FF),
        0.45f to Color(0xFF2E8FE0),
        1f to Color(0xFF0A3F7E),
    )
}

private val scheme = darkColorScheme(
    primary = Glass.Aqua,
    onPrimary = Color(0xFF00213D),
    secondary = Glass.Sky,
    background = Glass.Night,
    surface = Glass.Deep,
    surfaceVariant = Glass.Panel,
    surfaceContainer = Glass.Deep,
    surfaceContainerHigh = Glass.Panel,
    onSurface = Color(0xFFE6F1FB),
    onSurfaceVariant = Glass.Muted,
    outline = Glass.Line,
)

@Composable
fun AurralTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A round glass button like the transport orb on the desktop skin. */
@Composable
fun GlassOrb(size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Glass.orb)
            .border(1.dp, Color(0x88D6F1FF), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
