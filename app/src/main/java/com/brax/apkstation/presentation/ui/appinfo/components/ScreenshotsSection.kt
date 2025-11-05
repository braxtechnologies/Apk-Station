package com.brax.apkstation.presentation.ui.appinfo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.brax.apkstation.R

@Composable
fun ScreenshotsSection(
    screenshots: List<String>,
    onScreenshotClick: (Int) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.screenshots),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(screenshots.size) { index ->
                AsyncImage(
                    model = screenshots[index],
                    contentDescription = "Screenshot ${index + 1}",
                    modifier = Modifier
                        .width(180.dp)
                        .height(320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onScreenshotClick(index) },
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
