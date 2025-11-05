package com.brax.apkstation.presentation.ui.lending.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionTabs(
    selectedTab: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Find the selected index based on the query name
    val selectedIndex = SectionTab.entries.indexOfFirst { section ->
        selectedTab == section.queryName
    }.takeIf { it >= 0 } ?: 0

    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        edgePadding = 0.dp,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier
                    .tabIndicatorOffset(selectedIndex, matchContentSize = true)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            )
        }
    ) {
        SectionTab.entries.forEachIndexed { index, section ->
            val isSelected = index == selectedIndex

            Tab(
                selected = isSelected,
                onClick = {
                    onTabSelected(section.queryName)
                },
                text = {
                    Text(
                        text = section.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class SectionTab(val displayName: String, val queryName: String) {
    BRAX_PICKS("BRAX Picks", "featured"),
    TOP_CHARTS("Top Charts", "requests"),
    NEW_RELEASES("Recently Updated", "date"),
    CATEGORIES("Categories", "categories")
}
