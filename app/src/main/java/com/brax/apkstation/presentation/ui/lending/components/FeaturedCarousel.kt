package com.brax.apkstation.presentation.ui.lending.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.brax.apkstation.presentation.ui.lending.AppItem
import com.brax.apkstation.presentation.ui.lending.AppStatus
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCarousel(
    featuredApps: List<AppItem>,
    onAppClick: (AppItem) -> Unit,
    onActionClick: (AppItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (featuredApps.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { featuredApps.size })

    Column(modifier = modifier) {
        // Carousel
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            FeaturedAppCard(
                app = featuredApps[page],
                onCardClick = { onAppClick(featuredApps[page]) },
                onActionClick = { onActionClick(featuredApps[page]) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Page indicator
        if (featuredApps.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(featuredApps.size) { iteration ->
                    val color = if (pagerState.currentPage == iteration) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    }
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedAppCard(
    app: AppItem,
    onCardClick: () -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Generate random gradient colors based on app package name for consistency
    val gradientColors = remember(app.packageName) {
        getGradientColors(app.packageName)
    }

    Card(
        onClick = onCardClick,
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = gradientColors
                    )
                )
                .padding(16.dp)
        ) {
            // Featured badge at top left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-16).dp, y = (-16).dp)
                    .background(
                        color = Color.White.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 0.dp,
                            bottomStart = 0.dp,
                            bottomEnd = 12.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Featured",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = gradientColors.first()
                )
            }

            // App info and action button at bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // App icon and info
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App icon
                    SubcomposeAsyncImage(
                        model = app.icon,
                        contentDescription = "${app.name} icon",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // App name and publisher
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        app.author?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Action button at bottom right
                Button(
                    onClick = onActionClick,
                    modifier = Modifier
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = gradientColors.first()
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        text = getActionButtonText(app.status),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

        }
    }
}

private fun getActionButtonText(status: AppStatus): String {
    return when (status) {
        AppStatus.INSTALLED -> "Open"
        AppStatus.UPDATE_AVAILABLE -> "Update"
        AppStatus.DOWNLOADING -> "Cancel"
        AppStatus.INSTALLING -> "Installing..."
        AppStatus.REQUESTING -> "Requesting..."
        AppStatus.REQUESTED -> "Requested"
        AppStatus.UNAVAILABLE -> "Unavailable"
        else -> "Install"
    }
}

// Generate consistent gradient colors based on package name
private fun getGradientColors(packageName: String): List<Color> {
    val hash = packageName.hashCode()
    val colorPairs = listOf(
        listOf(Color(0xFF667eea), Color(0xFF764ba2)), // Purple gradient
        listOf(Color(0xFFf093fb), Color(0xFFf5576c)), // Pink gradient
        listOf(Color(0xFF4facfe), Color(0xFF00f2fe)), // Blue gradient
        listOf(Color(0xFF43e97b), Color(0xFF38f9d7)), // Green gradient
        listOf(Color(0xFFfa709a), Color(0xFFfee140)), // Warm gradient
        listOf(Color(0xFFa8edea), Color(0xFFfed6e3)), // Pastel gradient
        listOf(Color(0xFFff9a56), Color(0xFFff6a88)), // Orange gradient
        listOf(Color(0xFF6a11cb), Color(0xFF2575fc)), // Deep blue gradient
    )

    val index = (hash.absoluteValue % colorPairs.size)
    return colorPairs[index]
}

@Composable
private fun remember(key: Any?, calculation: () -> List<Color>): List<Color> {
    return androidx.compose.runtime.remember(key) { calculation() }
}


