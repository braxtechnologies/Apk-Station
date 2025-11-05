package com.brax.apkstation.presentation.ui.imageviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Full-screen image viewer with carousel and zoom support
 */
@Composable
fun ImageViewerScreen(
    images: List<String>,
    initialIndex: Int = 0,
    onNavigateBack: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { Int.MAX_VALUE }
    )
    var isZoomed by remember { mutableStateOf(false) }

    // Calculate the actual image index (infinite loop)
    fun getActualIndex(page: Int): Int {
        return if (images.isEmpty()) 0 else page % images.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Horizontal pager for carousel
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isZoomed // Disable pager swipe when zoomed
        ) { page ->
            val actualIndex = getActualIndex(page)
            ZoomableImage(
                imageUrl = images[actualIndex],
                onSingleTap = onNavigateBack,
                onZoomChange = { zoomed -> isZoomed = zoomed }
            )
        }

        // Top bar with back button and page indicator
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 42.dp)
        ) {
            // Back button with circular background
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            // Page indicator with circular background showing "1/6" format
            if (images.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${getActualIndex(pagerState.currentPage) + 1}/${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // Start at the correct initial page
    LaunchedEffect(Unit) {
        if (initialIndex > 0 && images.isNotEmpty()) {
            // Jump to a large number that when modulo'd gives us initialIndex
            val startPage = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2) % images.size + initialIndex
            pagerState.scrollToPage(startPage)
        }
    }
}

/**
 * Zoomable image component with pinch-to-zoom and double-tap support
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    onSingleTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Notify parent when zoom state changes
    LaunchedEffect(scale) {
        onZoomChange(scale > 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Handle tap gestures
                detectTapGestures(
                    onDoubleTap = {
                        // Double tap - toggle between 1x and 2.5x zoom
                        if (scale > 1.1f) {  // Use 1.1f threshold to handle floating point precision
                            // Zoom out to 1x
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            // Zoom in to 2.5x
                            scale = 2.5f
                        }
                    },
                    onTap = {
                        // Single tap - exit only when not zoomed
                        if (scale == 1f) {
                            onSingleTap()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Detect pinch-to-zoom gestures - only consume when actively zooming
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        
                        // Only handle multi-touch gestures (pinch) or panning when already zoomed
                        val isMultiTouch = event.changes.size > 1
                        val isPanning = scale > 1f && event.changes.any { it.positionChanged() }
                        
                        if (isMultiTouch || isPanning) {
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                                
                                // Limit panning based on scale
                                val maxX = (size.width * (scale - 1)) / 2
                                val maxY = (size.height * (scale - 1)) / 2
                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)
                                
                                // Consume the event when zoomed or pinching
                                event.changes.forEach { it.consume() }
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Full screen image",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = ContentScale.Fit
        )
    }
}
