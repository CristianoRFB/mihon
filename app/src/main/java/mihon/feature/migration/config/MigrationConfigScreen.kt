package mihon.feature.migration.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSearchScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.feature.migration.list.MigrationListScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Deselect
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.SelectAll
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.shouldExpandFAB

class MigrationConfigScreen(private val mangaIds: Collection<Long>) : Screen() {

    constructor(mangaId: Long) : this(listOf(mangaId))

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = metroViewModel<Model>()
        val state by viewModel.state.collectAsState()

        var migrationSheetOpen by rememberSaveable { mutableStateOf(false) }

        fun continueMigration(openSheet: Boolean, extraSearchQuery: String?) {
            val mangaId = mangaIds.singleOrNull()
            if (mangaId == null && openSheet) {
                migrationSheetOpen = true
                return
            }
            val screen = if (mangaId == null) {
                MigrationListScreen(mangaIds, extraSearchQuery)
            } else {
                MigrateSearchScreen(mangaId)
            }
            navigator.replace(screen)
        }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        var sourceQuery by rememberSaveable { mutableStateOf("") }
        var searchSection by rememberSaveable { mutableStateOf(SourceSearchSection.All) }
        val query = sourceQuery.trim()
        val (selectedSources, availableSources) = state.sources.partition { it.isSelected }
        val visibleSelectedSources = if (searchSection != SourceSearchSection.Available) {
            selectedSources.filter { source ->
                matchesMigrationSourceSearch(source.name, source.source.lang, source.id, query)
            }
        } else {
            emptyList()
        }
        val visibleAvailableSources = if (searchSection != SourceSearchSection.Selected) {
            availableSources.filter { source ->
                matchesMigrationSourceSearch(source.name, source.source.lang, source.id, query)
            }
        } else {
            emptyList()
        }
        // Reordering a filtered subset would otherwise move unrelated hidden entries.
        val canReorder = query.isEmpty() && searchSection == SourceSearchSection.All
        val showLanguage by remember(state) {
            derivedStateOf {
                state.sources.distinctBy { it.source.lang }.size > 1
            }
        }

        val lazyListState = rememberLazyListState()
        Scaffold(
            topBar = {
                AppBar(
                    title = null,
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                                    icon = MaterialSymbols.Rounded.SelectAll,
                                    onClick = { viewModel.toggleSelection(Model.SelectionConfig.All) },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    icon = MaterialSymbols.Rounded.Deselect,
                                    onClick = { viewModel.toggleSelection(Model.SelectionConfig.None) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectEnabledLabel),
                                    onClick = { viewModel.toggleSelection(Model.SelectionConfig.Enabled) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    onClick = { viewModel.toggleSelection(Model.SelectionConfig.Pinned) },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                    icon = {
                        Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null)
                    },
                    onClick = {
                        viewModel.saveSources()
                        continueMigration(openSheet = true, extraSearchQuery = null)
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { contentPadding ->
            val reorderableState = rememberReorderableLazyListState(lazyListState, PaddingValues(0.dp)) { from, to ->
                if (!canReorder) return@rememberReorderableLazyListState
                val fromIndex = selectedSources.indexOfFirst { it.id == from.key }
                val toIndex = selectedSources.indexOfFirst { it.id == to.key }
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                viewModel.orderSource(fromIndex, toIndex)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                OutlinedTextField(
                    value = sourceQuery,
                    onValueChange = { sourceQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium),
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.action_search)) },
                )
                Row(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    FilterChip(
                        selected = searchSection == SourceSearchSection.Selected,
                        onClick = {
                            searchSection = if (searchSection == SourceSearchSection.Selected) {
                                SourceSearchSection.All
                            } else {
                                SourceSearchSection.Selected
                            }
                        },
                        label = { Text(stringResource(MR.strings.migrationConfigScreen_selectedHeader)) },
                    )
                    FilterChip(
                        selected = searchSection == SourceSearchSection.Available,
                        onClick = {
                            searchSection = if (searchSection == SourceSearchSection.Available) {
                                SourceSearchSection.All
                            } else {
                                SourceSearchSection.Available
                            }
                        },
                        label = { Text(stringResource(MR.strings.migrationConfigScreen_availableHeader)) },
                    )
                }
                FastScrollLazyColumn(
                    modifier = Modifier.weight(1f),
                    state = lazyListState,
                ) {
                    if (visibleSelectedSources.isEmpty() && visibleAvailableSources.isEmpty()) {
                        item("empty-source-search") {
                            Text(
                                text = stringResource(MR.strings.empty_screen),
                                modifier = Modifier.padding(MaterialTheme.padding.medium),
                            )
                        }
                    }
                    listOf(visibleSelectedSources, visibleAvailableSources)
                        .fastForEachIndexed { listIndex, sources ->
                            val selectedSourceList = listIndex == 0
                            if (sources.isNotEmpty()) {
                                val headerPrefix = if (selectedSourceList) "selected" else "available"
                                item("$headerPrefix-header") {
                                    Text(
                                        text = stringResource(
                                            resource = if (selectedSourceList) {
                                                MR.strings.migrationConfigScreen_selectedHeader
                                            } else {
                                                MR.strings.migrationConfigScreen_availableHeader
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .padding(MaterialTheme.padding.medium)
                                            .animateItem(),
                                    )
                                }
                            }
                            itemsIndexed(
                                items = sources,
                                key = { _, item -> item.id },
                            ) { index, item ->
                                SourceItemContainer(
                                    firstItem = index == 0,
                                    lastItem = index == (sources.size - 1),
                                    source = item,
                                    showLanguage = showLanguage,
                                    dragEnabled = selectedSourceList && canReorder && sources.size > 1,
                                    state = reorderableState,
                                    key = { if (selectedSourceList) it.id else "available-${it.id}" },
                                    onClick = { viewModel.toggleSelection(item.id) },
                                )
                            }
                        }
                }
            }
        }

        if (migrationSheetOpen) {
            MigrationConfigScreenSheet(
                preferences = viewModel.sourcePreferences,
                onDismissRequest = { migrationSheetOpen = false },
                onStartMigration = { extraSearchQuery ->
                    migrationSheetOpen = false
                    continueMigration(openSheet = false, extraSearchQuery = extraSearchQuery)
                },
            )
        }
    }

    @Composable
    private fun LazyItemScope.SourceItemContainer(
        firstItem: Boolean,
        lastItem: Boolean,
        source: MigrationSource,
        showLanguage: Boolean,
        dragEnabled: Boolean,
        state: ReorderableLazyListState,
        key: (MigrationSource) -> Any,
        onClick: () -> Unit,
    ) {
        val shape = remember(firstItem, lastItem) {
            val top = if (firstItem) 12.dp else 0.dp
            val bottom = if (lastItem) 12.dp else 0.dp
            RoundedCornerShape(top, top, bottom, bottom)
        }

        ReorderableItem(
            state = state,
            key = key(source),
            enabled = dragEnabled,
        ) { _ ->
            ElevatedCard(
                shape = shape,
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .animateItem(),
            ) {
                SourceItem(
                    source = source,
                    showLanguage = showLanguage,
                    dragEnabled = dragEnabled,
                    scope = this@ReorderableItem,
                    onClick = onClick,
                )
            }
        }

        if (!lastItem) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
        }
    }

    @Composable
    private fun SourceItem(
        source: MigrationSource,
        showLanguage: Boolean,
        dragEnabled: Boolean,
        scope: ReorderableCollectionItemScope,
        onClick: () -> Unit,
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            trailingContent = if (dragEnabled) {
                {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.DragHandle,
                        contentDescription = null,
                        modifier = with(scope) {
                            Modifier.draggableHandle()
                        },
                    )
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(source = source.source)
                Text(
                    text = source.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (showLanguage) {
                    Pill(
                        text = LocaleHelper.getShortDisplayName(source.shortLanguage, uppercase = true),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    @Inject
    @ViewModelKey
    @ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
    class Model(
        val sourcePreferences: SourcePreferences,
        private val sourceManager: SourceManager,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        private val sourcesComparator = { includedSources: List<Long> ->
            compareBy<MigrationSource>(
                { !it.isSelected },
                { includedSources.indexOf(it.id) },
                { with(it) { "$name ($shortLanguage)" } },
            )
        }

        init {
            viewModelScope.launch(Dispatchers.IO) {
                initSources()
                state.update { it.copy(isLoading = false) }
            }
        }

        private fun updateSources(action: (List<MigrationSource>) -> List<MigrationSource>) {
            state.update { state ->
                val updatedSources = action(state.sources)
                val includedSources = updatedSources.mapNotNull { if (!it.isSelected) null else it.id }
                state.copy(sources = updatedSources.sortedWith(sourcesComparator(includedSources)))
            }
            saveSources()
        }

        private suspend fun initSources() {
            val languages = sourcePreferences.enabledLanguages.get()
            val pinnedSources = sourcePreferences.pinnedSources.get().mapNotNull { it.toLongOrNull() }
            val includedSources = sourcePreferences.migrationSources.get()
            val disabledSources = sourcePreferences.disabledSources.get()
                .mapNotNull { it.toLongOrNull() }
            val sources = sourceManager.getAll()
                .asSequence()
                .filterIsInstance<HttpSource>()
                .filter { it.lang in languages }
                .map {
                    val source = Source(
                        id = it.id,
                        lang = it.lang,
                        name = it.name,
                        supportsLatest = false,
                        isStub = false,
                    )
                    MigrationSource(
                        source = source,
                        isSelected = when {
                            includedSources.isNotEmpty() -> source.id in includedSources
                            pinnedSources.isNotEmpty() -> source.id in pinnedSources
                            else -> source.id !in disabledSources
                        },
                    )
                }
                .toList()

            state.update { state ->
                state.copy(sources = sources.sortedWith(sourcesComparator(includedSources)))
            }
        }

        fun toggleSelection(id: Long) {
            updateSources { sources ->
                sources.map { source ->
                    source.copy(isSelected = if (source.source.id == id) !source.isSelected else source.isSelected)
                }
            }
        }

        fun toggleSelection(config: SelectionConfig) {
            val pinnedSources = sourcePreferences.pinnedSources.get().mapNotNull { it.toLongOrNull() }
            val disabledSources = sourcePreferences.disabledSources.get().mapNotNull { it.toLongOrNull() }
            val isSelected: (Long) -> Boolean = {
                when (config) {
                    SelectionConfig.All -> true
                    SelectionConfig.None -> false
                    SelectionConfig.Pinned -> it in pinnedSources
                    SelectionConfig.Enabled -> it !in disabledSources
                }
            }
            updateSources { sources ->
                sources.map { source ->
                    source.copy(isSelected = isSelected(source.source.id))
                }
            }
        }

        fun orderSource(from: Int, to: Int) {
            updateSources {
                it.toMutableList()
                    .apply {
                        add(to, removeAt(from))
                    }
                    .toList()
            }
        }

        fun saveSources() {
            state.value.sources
                .filter { source -> source.isSelected }
                .map { source -> source.source.id }
                .let { sources -> sourcePreferences.migrationSources.set(sources) }
        }

        data class State(
            val isLoading: Boolean = true,
            val sources: List<MigrationSource> = emptyList(),
        )

        enum class SelectionConfig {
            All,
            None,
            Pinned,
            Enabled,
        }
    }

    data class MigrationSource(
        val source: Source,
        val isSelected: Boolean,
    ) {
        val id: Long
            inline get() = source.id

        val name: String
            inline get() = source.name

        val shortLanguage: String = LocaleHelper.getShortDisplayName(source.lang)
    }
}

private enum class SourceSearchSection {
    All,
    Selected,
    Available,
}

/** Search source names, languages and IDs without altering the user's saved selection or order. */
internal fun matchesMigrationSourceSearch(name: String, language: String, id: Long, query: String): Boolean {
    val term = query.trim()
    return term.isEmpty() ||
        name.contains(term, ignoreCase = true) ||
        language.contains(term, ignoreCase = true) ||
        id.toString().contains(term)
}
