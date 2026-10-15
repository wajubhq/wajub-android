package com.wajub.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * A modal bottom sheet from stable Compose APIs (a full-screen dialog with a
 * scrim and a sheet that slides up, dragged down to close): Material 3's
 * `ModalBottomSheet` is experimental and changes between releases, which
 * would break apps on another Material 3 version than this SDK's.
 */
@Composable
internal fun BottomSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    elevation: Float,
    scrimAlpha: Float?,
    radius: Float,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        var dragged by remember { mutableFloatStateOf(0f) }
        val closeAfter = with(LocalDensity.current) { 120.dp.toPx() }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val maxHeight = maxHeight * 0.95f
            // The scrim: a tap outside the sheet closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha ?: 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
                    .testTag("wajub-scrim"),
            )
            AnimatedVisibility(visible = shown, enter = slideInVertically(tween(250)) { it }) {
                Surface(
                    color = containerColor,
                    shadowElevation = elevation.dp,
                    shape = RoundedCornerShape(topStart = (radius * 1.5f).dp, topEnd = (radius * 1.5f).dp),
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .offset { IntOffset(0, dragged.roundToInt()) }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> dragged = (dragged + delta).coerceAtLeast(0f) },
                            onDragStopped = { if (dragged > closeAfter) onDismissRequest() else dragged = 0f },
                        )
                        .testTag("wajub-bottom-sheet"),
                ) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        // The drag handle.
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 10.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                        content()
                    }
                }
            }
        }
    }
}
