package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import kotlin.time.Duration.Companion.seconds

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen. An item can be a single [PreferenceItem] or a group ([Preference.PreferenceGroup])
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    val highlightPosition = SearchableSettings.highlightPosition
    if (highlightPosition != null || highlightKey != null) {
        LaunchedEffect(Unit) {
            val targetIndex = highlightPosition ?: highlightKey?.let(items::findHighlightedIndex) ?: -1
            if (targetIndex >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(targetIndex)
            }
            SearchableSettings.highlightKey = null
            SearchableSettings.highlightPosition = null
        }
    }

    fun highlightTitle(title: String, position: Int): String? = when {
        highlightPosition == null -> highlightKey
        highlightPosition == position -> title
        else -> null
    }

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            when (preference) {
                // Create Preference Group
                is Preference.PreferenceGroup -> {
                    if (!preference.visible) return@fastForEachIndexed

                    val headerPosition = items.preferencePosition(i)
                    item {
                        Column {
                            PreferenceGroupHeader(
                                title = preference.title,
                                highlighted = highlightTitle(preference.title, headerPosition) == preference.title,
                            )
                        }
                    }
                    itemsIndexed(preference.preferenceItems) { childIndex, item ->
                        PreferenceItem(
                            item = item,
                            highlightKey = highlightTitle(item.title, headerPosition + childIndex + 1),
                        )
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Create Preference Item
                is Preference.PreferenceItem<*, *> -> {
                    val position = items.preferencePosition(i)
                    item {
                        PreferenceItem(
                            item = preference,
                            highlightKey = highlightTitle(preference.title, position),
                        )
                    }
                }
            }
        }
    }
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int {
    return flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(it.title) // Header
                addAll(it.preferenceItems.map { groupItem -> groupItem.title })
                add(null) // Spacer
            }
        } else {
            listOf(it.title)
        }
    }.indexOfFirst { it == highlightKey }
}

/**
 * Position in the LazyColumn used by [PreferenceScreen].
 *
 * Include group headers and spacer rows, even when a group contains multiple items
 * with identical titles. An invisible group contributes no list rows.
 */
internal fun List<Preference>.preferencePosition(groupIndex: Int, childIndex: Int? = null): Int {
    var position = 0
    for ((index, preference) in withIndex()) {
        if (preference is Preference.PreferenceGroup && !preference.visible) continue
        if (index == groupIndex) {
            return when (preference) {
                is Preference.PreferenceGroup -> {
                    when {
                        childIndex == null -> position
                        childIndex in preference.preferenceItems.indices -> position + childIndex + 1
                        else -> -1
                    }
                }
                else -> if (childIndex == null) position else -1
            }
        }
        position += if (preference is Preference.PreferenceGroup) {
            2 + preference.preferenceItems.size // Header + children + spacer
        } else {
            1
        }
    }
    return -1
}
