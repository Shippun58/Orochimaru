package ani.sanin.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val FocusSpringSpec = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)
private val SpringSpec = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow)
private val BreatheSpec = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessVeryLow)

val IcyBlueBorder = Color(0xFF87CEEB).copy(alpha = 0.5f)

@Composable
fun Modifier.glowFocusEffect(isFocused: Boolean): Modifier {
    val scale = animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = FocusSpringSpec,
        label = "glowScale"
    ).value
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (isFocused) Modifier.border(2.dp, IcyBlueBorder, RoundedCornerShape(50))
            else Modifier
        )
}

@Composable
fun Modifier.scaleFocusEffect(isFocused: Boolean): Modifier {
    val scale = animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = FocusSpringSpec,
        label = "scaleAnim"
    ).value
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun Modifier.pulseFocusEffect(isFocused: Boolean): Modifier {
    val pulseScale = remember { mutableStateOf(1.0f) }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            while (true) {
                pulseScale.value = 1.12f
                delay(400)
                pulseScale.value = 1.08f
                delay(400)
            }
        } else {
            pulseScale.value = 1.0f
        }
    }
    val scale = animateFloatAsState(
        targetValue = pulseScale.value,
        animationSpec = SpringSpec,
        label = "pulse"
    ).value
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun Modifier.breatheFocusEffect(isFocused: Boolean): Modifier {
    val target = remember { mutableStateOf(1.0f) }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            while (true) {
                target.value = 1.08f
                delay(800)
                target.value = 1.0f
                delay(800)
            }
        } else {
            target.value = 1.0f
        }
    }
    val scale = animateFloatAsState(
        targetValue = target.value,
        animationSpec = BreatheSpec,
        label = "breathe"
    ).value
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun Modifier.pulseGlowFocusEffect(isFocused: Boolean): Modifier {
    val target = remember { mutableStateOf(1.0f) }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            while (true) {
                target.value = 1.08f
                delay(600)
                target.value = 1.0f
                delay(600)
            }
        } else {
            target.value = 1.0f
        }
    }
    val scale = animateFloatAsState(
        targetValue = target.value,
        animationSpec = BreatheSpec,
        label = "pulseGlowScale"
    ).value
    val glowAlpha = ((scale - 1.0f) / 0.08f * 0.5f + 0.5f).coerceIn(0.3f, 1.0f)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (isFocused) Modifier.border(2.dp, IcyBlueBorder.copy(alpha = glowAlpha), RoundedCornerShape(50))
            else Modifier
        )
}

@Composable
fun Modifier.navigationPillFocusEffect(isFocused: Boolean, effect: String): Modifier = when (effect) {
    "glow" -> this.glowFocusEffect(isFocused)
    "scale" -> this.scaleFocusEffect(isFocused)
    "pulse" -> this.pulseFocusEffect(isFocused)
    "breathe" -> this.breatheFocusEffect(isFocused)
    "pulseglow" -> this.pulseGlowFocusEffect(isFocused)
    else -> this
}
