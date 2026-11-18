package com.akenterprises.auroraengine.ui.animation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import com.akenterprises.auroraengine.core.model.LiquidFlowType

object LiquidFlowEngine {
    @Composable
    fun transitionColor(
        target: Color,
        type: LiquidFlowType,
        speed: Float = 1f
    ): State<Color> = animateColorAsState(
        targetValue = target,
        animationSpec = if (type == LiquidFlowType.FLUID_FUSION) {
            tween(
                durationMillis = (450 * speed.coerceIn(0.1f, 1f)).toInt(),
                easing = FastOutSlowInEasing
            )
        } else {
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)
        },
        label = "liquid-flow-color"
    )

}

@Composable
fun Modifier.liquidTransition(
    type: LiquidFlowType,
    enabled: Boolean,
    speed: Float = 1f
): Modifier {
    val scale = animateFloatAsState(
        targetValue = if (enabled) 1f else 0.96f,
        animationSpec = if (type == LiquidFlowType.FLUID_MOTION) {
            spring(
                dampingRatio = 0.72f,
                stiffness = if (speed < 1f) 800f else Spring.StiffnessLow
            )
        } else {
            tween(
                durationMillis = (450 * speed.coerceIn(0.1f, 1f)).toInt(),
                easing = FastOutSlowInEasing
            )
        },
        label = "liquid-flow-scale"
    )
    val sizeSpec = if (type == LiquidFlowType.FLUID_FUSION) {
        spring<IntSize>(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)
    } else {
        spring<IntSize>(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)
    }
    return this
        .animateContentSize(animationSpec = sizeSpec)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            if (type == LiquidFlowType.FLUID_MOTION) {
                translationX = (1f - scale.value) * size.width * 0.15f
            }
        }
}
