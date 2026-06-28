package com.tadmor.app.ui.gltest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoGLSurfaceView
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.ui.components.DisclaimerLabel
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Test screen embedding a GLSurfaceView with a lit sphere.
 * Validates the full Compose-to-GL pipeline: shader compilation,
 * mesh generation, camera control, touch handling, and overlay compositing.
 */
@Composable
fun GLTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    val cameraController = remember {
        CameraController(
            minDistance = 1.5f,
            maxDistance = 10f,
        ).apply {
            setDistance(3f)
            setOrbitAngles(0f, 15f)
        }
    }

    val bridge = remember { GLBridge(TestSphereParams()) }

    Box(modifier = modifier.fillMaxSize()) {
        // Layer 1: GL view
        AndroidView(
            factory = { ctx ->
                val renderer = TestSphereRenderer(appContext, cameraController, bridge)
                ExoGLSurfaceView(ctx, renderer, cameraController)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2: Compose overlays

        // Back button (top-left)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = spacing.xxxl, top = 24.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .drawBehind {
                        val sw = 1.2.dp.toPx()
                        val chevronColor = colors.textTertiary
                        val cx = size.width * 0.55f
                        val top = size.height * 0.2f
                        val bot = size.height * 0.8f
                        val left = size.width * 0.25f
                        drawLine(chevronColor, Offset(cx, top), Offset(left, size.height / 2f), sw, cap = StrokeCap.Round)
                        drawLine(chevronColor, Offset(left, size.height / 2f), Offset(cx, bot), sw, cap = StrokeCap.Round)
                    },
            )
            Spacer(Modifier.width(spacing.sm))
            BasicText(
                text = "GL Test",
                style = type.labelLarge.copy(color = colors.textTertiary),
            )
        }

        // Disclaimer label (bottom-left)
        DisclaimerLabel(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
        )
    }
}
