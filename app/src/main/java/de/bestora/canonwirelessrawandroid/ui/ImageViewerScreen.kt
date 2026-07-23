package de.bestora.canonwirelessrawandroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 8f

/**
 * Full-screen zoomable viewer. Loads the CR3's embedded full-HD preview JPEG (via
 * [de.bestora.canonwirelessrawandroid.data.CameraRepository.loadPreview]) and shows it with pinch-to-zoom
 * (transformable + graphicsLayer) and double-tap to reset. All Compose built-ins, no image library.
 */
@Composable
fun ImageViewerScreen(
    container: AppContainer,
    item: GalleryItem,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    val handle = item.obj.handle

    // decode off the main thread; null while loading, Result once done
    val preview by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = handle) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val data = container.repo.loadPreview(handle)
                    ?: error("No preview image found in the file")
                val bmp = BitmapFactory.decodeByteArray(data.jpeg, 0, data.jpeg.size)
                    ?: error("Failed to decode preview image")
                // Embedded preview JPEGs have no EXIF, so apply the CR3's orientation ourselves.
                rotateForOrientation(bmp, data.orientation).asImageBitmap()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val result = preview
            when {
                result == null -> CircularProgressIndicator(color = Color.White)
                result.isFailure -> Text(
                    result.exceptionOrNull()?.message ?: "Error loading image",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
                else -> ZoomableImage(result.getOrThrow())
            }

            // Close button (top-left), always available.
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text("Close", color = Color.White)
            }
            // Download this image without the back-and-long-press detour through the gallery.
            TextButton(onClick = onDownload, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Download", color = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Two-finger pinch/pan. When fully zoomed out, snap the pan back so the image can't drift off.
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = if (scale <= 1f) Offset.Zero else offset + offsetChange
    }

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(state)
            // Double-tap toggles between fit and 3x; reset pan on the way back.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    },
                )
            },
    )
}
