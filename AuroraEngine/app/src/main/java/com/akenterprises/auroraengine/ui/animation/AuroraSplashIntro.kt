package com.akenterprises.auroraengine.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenterprises.auroraengine.R
import kotlinx.coroutines.delay

@Composable
fun AuroraSplashIntro(onFinished: () -> Unit) {
    var snapped by remember { mutableStateOf(false) }
    var signature by remember { mutableStateOf("") }
    val drop = remember { Animatable(0f) }
    val cursor by rememberInfiniteTransition(label = "signature-cursor")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500)),
            label = "signature-cursor"
        )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07090E), Color(0xFF11141D), Color(0xFF07090E))
                )
            )
    ) {
        val maxDrop = (maxHeight.value - 76f).coerceAtLeast(0f)
        LaunchedEffect(maxHeight) {
            drop.animateTo(maxDrop, tween(900, easing = FastOutSlowInEasing))
            drop.animateTo(
                maxHeight.value * 0.34f,
                spring(dampingRatio = 0.56f, stiffness = Spring.StiffnessMediumLow)
            )
            snapped = true
            delay(350)
            "AK by AK Enterprises".forEachIndexed { index, _ ->
                signature = "AK by AK Enterprises".take(index + 1)
                delay(65)
            }
            delay(550)
            onFinished()
        }

        if (!snapped) {
            Canvas(Modifier.fillMaxSize()) {
                val beamY = drop.value.dp.toPx().coerceIn(0f, size.height)
                drawLine(
                    color = Color(0x6600E5FF),
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, beamY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = Color(0xAA00E5FF),
                    radius = 8.dp.toPx(),
                    center = Offset(size.width / 2f, beamY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        if (!snapped) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .offset(y = drop.value.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AURORA",
                    color = Color(0xFF00E5FF),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_aurora_brand_logo),
                    contentDescription = "Aurora Engine AE emblem",
                    modifier = Modifier.size(170.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AURORA ENGINE",
                    color = Color(0xFFFFD700),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }
        }

        if (snapped) {
            Text(
                text = signature + if (cursor > 0.5f) " |" else "",
                color = Color(0xFFFFD700),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp)
            )
        }
    }
}
