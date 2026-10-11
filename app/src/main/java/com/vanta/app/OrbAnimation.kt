package com.vanta.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

enum class ArgonOrbMode { STANDBY, LISTENING, THINKING, SPEAKING }

/**
 * Animated Argon HUD orb. Entirely Compose-drawn; no GIF or large bitmap required.
 * The animation responds to actual speech and request lifecycle states.
 */
@Composable
fun ArgonOrbPanel(mode: ArgonOrbMode, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val transition = rememberInfiniteTransition(label = "argon-orb")
    val pulseMin = when (mode) {
        ArgonOrbMode.STANDBY -> 0.96f
        ArgonOrbMode.LISTENING -> 1.00f
        ArgonOrbMode.THINKING -> 0.94f
        ArgonOrbMode.SPEAKING -> 0.98f
    }
    val pulseMax = when (mode) {
        ArgonOrbMode.STANDBY -> 1.035f
        ArgonOrbMode.LISTENING -> 1.12f
        ArgonOrbMode.THINKING -> 1.07f
        ArgonOrbMode.SPEAKING -> 1.15f
    }
    val duration = when (mode) {
        ArgonOrbMode.STANDBY -> 2400
        ArgonOrbMode.LISTENING -> 1050
        ArgonOrbMode.THINKING -> 1450
        ArgonOrbMode.SPEAKING -> 780
    }
    val pulse by transition.animateFloat(
        initialValue = pulseMin,
        targetValue = pulseMax,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mode) {
                    ArgonOrbMode.STANDBY -> 9000
                    ArgonOrbMode.LISTENING -> 4200
                    ArgonOrbMode.THINKING -> 1700
                    ArgonOrbMode.SPEAKING -> 2300
                },
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-rotation"
    )
    val ripple by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mode == ArgonOrbMode.LISTENING) 1150 else 1900,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-ripple"
    )

    val caption = when (mode) {
        ArgonOrbMode.STANDBY -> "Say “Hey Argon” or tap to talk"
        ArgonOrbMode.LISTENING -> "Listening · speak naturally"
        ArgonOrbMode.THINKING -> "Working on your request"
        ArgonOrbMode.SPEAKING -> "Argon is responding"
    }
    val captionColor = when (mode) {
        ArgonOrbMode.STANDBY -> Color(0xFF7194BB)
        ArgonOrbMode.LISTENING -> Color(0xFF52E9FF)
        ArgonOrbMode.THINKING -> Color(0xFF70A7FF)
        ArgonOrbMode.SPEAKING -> Color(0xFFA5A0FF)
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(142.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(142.dp).clickable(onClick = onTap)) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val min = size.minDimension
                val core = min * 0.265f * pulse
                val halo = core * 2.05f
                val listening = mode == ArgonOrbMode.LISTENING
                val thinking = mode == ArgonOrbMode.THINKING
                val speaking = mode == ArgonOrbMode.SPEAKING
                val intensity = when (mode) {
                    ArgonOrbMode.STANDBY -> 0.50f
                    ArgonOrbMode.LISTENING -> 0.88f
                    ArgonOrbMode.THINKING -> 0.78f
                    ArgonOrbMode.SPEAKING -> 0.96f
                }
                val center = Offset(cx, cy)
                val cyan = Color(0xFF36EEFF)
                val blue = Color(0xFF087DFF)
                val indigo = Color(0xFF493CFF)
                val purple = Color(0xFF9363FF)

                // Soft, broad halo and the deep blue glass interior.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            cyan.copy(alpha = 0.19f * intensity),
                            blue.copy(alpha = 0.13f * intensity),
                            Color.Transparent
                        ),
                        center = center,
                        radius = halo * 1.48f
                    ),
                    radius = halo * 1.48f
                )
                if (mode != ArgonOrbMode.STANDBY) {
                    for (i in 0..2) {
                        val expand = if (listening || speaking) ripple * min * 0.075f else 0f
                        val ringR = core * (1.36f + i * 0.20f) + expand
                        drawCircle(
                            color = if (speaking) purple.copy(alpha = 0.17f - i * 0.03f)
                                else cyan.copy(alpha = 0.20f - i * 0.035f),
                            radius = ringR,
                            center = center,
                            style = Stroke(width = (0.8f + i * 0.15f).dp.toPx())
                        )
                    }
                } else {
                    drawCircle(
                        color = blue.copy(alpha = 0.20f),
                        radius = core * 1.53f,
                        center = center,
                        style = Stroke(width = 0.8.dp.toPx())
                    )
                }

                // Rotate luminous broken arcs around the edge rather than rotating the whole orb.
                val arcRadius = core * 1.34f
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, cyan.copy(alpha = intensity), blue.copy(alpha = intensity), indigo.copy(alpha = 0.8f), Color.Transparent),
                        center = center
                    ),
                    startAngle = rotation - 128f,
                    sweepAngle = if (thinking) 250f else 205f,
                    useCenter = false,
                    topLeft = Offset(cx - arcRadius, cy - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = purple.copy(alpha = if (speaking) 0.9f else 0.37f),
                    startAngle = -rotation * 0.62f + 22f,
                    sweepAngle = if (listening) 68f + ripple * 35f else 48f,
                    useCenter = false,
                    topLeft = Offset(cx - arcRadius * 1.12f, cy - arcRadius * 1.12f),
                    size = Size(arcRadius * 2.24f, arcRadius * 2.24f),
                    style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round)
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF154E9F),
                            Color(0xFF08245C),
                            Color(0xFF02091A),
                            Color(0xFF061941)
                        ),
                        center = Offset(cx - core * 0.28f, cy - core * 0.34f),
                        radius = core * 1.62f
                    ),
                    radius = core
                )
                drawCircle(
                    color = cyan.copy(alpha = 0.54f * intensity),
                    radius = core,
                    style = Stroke(width = (1.65f * intensity).dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f * intensity),
                            cyan.copy(alpha = 0.16f * intensity),
                            Color.Transparent
                        ),
                        center = Offset(cx - core * 0.36f, cy - core * 0.62f),
                        radius = core * 1.08f
                    ),
                    radius = core * 0.94f
                )

                // Flowing glass-light ribbons within the sphere, with mode-dependent motion.
                val phase = rotation * (if (thinking) 0.018f else 0.009f)
                val wave = Path().apply {
                    moveTo(cx - core * 0.83f, cy - core * (0.12f + 0.12f * sin(phase.toDouble()).toFloat()))
                    cubicTo(
                        cx - core * 0.64f, cy - core * (0.95f + 0.07f * sin((phase + 1f).toDouble()).toFloat()),
                        cx + core * 0.18f, cy - core * (0.85f + 0.08f * sin((phase + 2f).toDouble()).toFloat()),
                        cx + core * 0.82f, cy + core * (0.04f + 0.10f * sin((phase + 1.5f).toDouble()).toFloat())
                    )
                    cubicTo(
                        cx + core * 0.45f, cy + core * (0.53f + 0.09f * sin((phase + 2.4f).toDouble()).toFloat()),
                        cx - core * 0.25f, cy + core * (0.60f + 0.05f * sin((phase + 3f).toDouble()).toFloat),
                        cx - core * 0.76f, cy + core * 0.28f
                    )
                }
                drawPath(
                    path = wave,
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFFDFFFFF).copy(alpha = 0.65f), cyan.copy(alpha = 0.88f), blue.copy(alpha = 0.75f), purple.copy(alpha = if (speaking) 0.85f else 0.55f), Color(0xFF5DF3FF).copy(alpha = 0.8f)),
                        center = center
                    ),
                    style = Stroke(width = core * 0.12f, cap = StrokeCap.Round)
                )
                val wave2 = Path().apply {
                    moveTo(cx - core * 0.82f, cy + core * 0.30f)
                    cubicTo(cx - core * 0.28f, cy - core * 0.18f, cx + core * 0.04f, cy - core * 0.02f, cx + core * 0.78f, cy - core * 0.44f)
                }
                drawPath(
                    path = wave2,
                    color = cyan.copy(alpha = 0.70f * intensity),
                    style = Stroke(width = 0.9.dp.toPx(), cap = StrokeCap.Round)
                )

                // Glass reflections and tiny energy flecks.
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.70f), cyan.copy(alpha = 0.16f), Color.Transparent),
                        start = Offset(cx - core * 0.72f, cy - core * 0.70f),
                        end = Offset(cx + core * 0.02f, cy - core * 0.26f)
                    ),
                    radius = core * 0.16f,
                    center = Offset(cx - core * 0.50f, cy - core * 0.63f)
                )
                if (thinking || speaking) {
                    for (i in 0..7) {
                        val a = (rotation + i * 45f) * (Math.PI / 180.0)
                        val rr = core * (1.39f + (i % 2) * 0.13f)
                        val dot = Offset(cx + kotlin.math.cos(a).toFloat() * rr, cy + kotlin.math.sin(a).toFloat() * rr)
                        drawCircle(
                            color = (if (speaking && i % 3 == 0) purple else cyan).copy(alpha = 0.35f + 0.55f * ripple),
                            radius = (if (thinking) 1.1f else 1.4f).dp.toPx(),
                            center = dot
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(
            text = caption,
            color = captionColor,
            fontSize = 11.sp,
            letterSpacing = 0.2.sp
        )
        Spacer(Modifier.height(2.dp))
    }
}
