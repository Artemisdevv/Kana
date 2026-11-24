package com.artemis.ebookreader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.dictionary.DictionaryCredit
import com.artemis.ebookreader.data.dictionary.DictionaryResult
import com.artemis.ebookreader.data.dictionary.OfflineDictionary
import com.artemis.ebookreader.data.dictionary.OnlineDictionary
import com.artemis.ebookreader.data.dictionary.dictionaryQuery
import kotlinx.coroutines.launch

private const val COLLAPSED_DEFINITION_COUNT = 3

private data class DefinitionGroup(val online: Boolean, val entryIndex: Int, val partOfSpeech: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderDictionarySheet(
    selection: String,
    dictionary: OfflineDictionary,
    fullscreen: Boolean,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val query = remember(selection) { dictionaryQuery(selection) }
    var offline by remember(query) { mutableStateOf<DictionaryResult?>(null) }
    var online by remember(query) { mutableStateOf<DictionaryResult?>(null) }
    var onlineRequest by remember(query) { mutableIntStateOf(0) }
    var expandedGroups by remember(query) { mutableStateOf(emptySet<DefinitionGroup>()) }
    val offlineEntries = (offline as? DictionaryResult.Found)?.entries.orEmpty()
    val resolvedWord = offlineEntries.map { it.word }.distinct().singleOrNull()?.takeUnless { it == query?.key }
    val toggleGroup: (DefinitionGroup) -> Unit = { group ->
        expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query != null) offline = dictionary.lookup(query)
    }
    LaunchedEffect(query, onlineRequest) {
        if (query != null && onlineRequest > 0) online = OnlineDictionary.lookup(query)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.10f),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightNavigationBars = !dark
                    isAppearanceLightStatusBars = !dark
                    if (fullscreen) {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = resolvedWord ?: query?.selection ?: stringResource(R.string.dictionary_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                        if (!sheetState.isVisible) onDismiss()
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_close_search), stringResource(R.string.close))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (query == null) {
                    item { DictionaryNote(stringResource(R.string.dictionary_select_word)) }
                } else {
                    val headingWord = resolvedWord ?: query.key
                    dictionaryResults(
                        offline, headingWord, online = false,
                        expandedGroups = expandedGroups, toggleGroup = toggleGroup,
                    )
                    item { HorizontalDivider() }
                    if (onlineRequest == 0) {
                        item {
                            Column {
                                TextButton(onClick = { onlineRequest++ }) {
                                    Text(stringResource(R.string.dictionary_search_online))
                                }
                                DictionaryNote(stringResource(R.string.dictionary_online_privacy))
                            }
                        }
                    } else {
                        item {
                            Text(
                                stringResource(R.string.dictionary_online),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        dictionaryResults(
                            online, headingWord, online = true,
                            expandedGroups = expandedGroups, toggleGroup = toggleGroup,
                        )
                        if (online == DictionaryResult.Unavailable || online == DictionaryResult.RateLimited) {
                            item {
                                TextButton(onClick = {
                                    online = null
                                    onlineRequest++
                                }) { Text(stringResource(R.string.dictionary_retry_online)) }
                            }
                        }
                    }
                    item {
                        DictionaryFooter(
                            onlineRequested = onlineRequest > 0,
                            credits = (online as? DictionaryResult.Found)?.entries.orEmpty()
                                .flatMap { it.credits }.distinct(),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.dictionaryResults(
    result: DictionaryResult?,
    headingWord: String,
    online: Boolean,
    expandedGroups: Set<DefinitionGroup>,
    toggleGroup: (DefinitionGroup) -> Unit,
) {
    when (result) {
        null -> item { DictionaryNote(stringResource(R.string.dictionary_looking_up)) }
        DictionaryResult.Missing -> item {
            DictionaryNote(stringResource(if (online) R.string.dictionary_online_missing else R.string.dictionary_offline_missing))
        }
        DictionaryResult.Unavailable -> item {
            DictionaryNote(stringResource(if (online) R.string.dictionary_online_unavailable else R.string.dictionary_offline_unavailable))
        }
        DictionaryResult.RateLimited -> item { DictionaryNote(stringResource(R.string.dictionary_online_rate_limited)) }
        is DictionaryResult.Found -> for ((entryIndex, entry) in result.entries.withIndex()) {
            val showWord = entry.word != headingWord || result.entries.any { it.word != entry.word }
            if (showWord || entry.phonetics.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (showWord) {
                            Text(
                                entry.word,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        if (entry.phonetics.isNotEmpty()) DictionaryNote(entry.phonetics.joinToString("  "))
                    }
                }
            }
            val groups = entry.meanings.groupBy { it.partOfSpeech }.entries
            for ((groupIndex, groupMeanings) in groups.withIndex()) {
                val (part, meanings) = groupMeanings
                val groupKey = "dictionary:$online:$entryIndex:$groupIndex"
                if (part != null) {
                    item(key = "$groupKey:part") {
                        Text(
                            part,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
                val senses = meanings.flatMap { it.senses }
                val group = DefinitionGroup(online, entryIndex, part)
                val expanded = group in expandedGroups
                items(
                    count = if (expanded) senses.size else minOf(senses.size, COLLAPSED_DEFINITION_COUNT),
                    key = { index -> "$groupKey:$index" },
                ) { index ->
                    val sense = senses[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.dictionary_number, index + 1),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sense.definition, style = MaterialTheme.typography.bodyMedium)
                            sense.examples.firstOrNull()?.let { example ->
                                Text(
                                    example,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
                if (senses.size > COLLAPSED_DEFINITION_COUNT) {
                    item(key = "$groupKey:toggle") {
                        TextButton(onClick = { toggleGroup(group) }) {
                            val label = if (expanded) R.string.dictionary_show_less else R.string.dictionary_show_more
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryFooter(onlineRequested: Boolean, credits: List<DictionaryCredit>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DictionaryNote(stringResource(R.string.dictionary_wordnet))
        if (onlineRequested) {
            val provider = stringResource(R.string.dictionary_online_provider)
            val browser = LocalUriHandler.current
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            val linkStyles = TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))
            Text(
                text = buildAnnotatedString {
                    append(provider)
                    for (credit in credits) {
                        append(" · ")
                        withLink(LinkAnnotation.Url(credit.url, linkStyles) {
                            runCatching { browser.openUri(credit.url) }
                        }) {
                            append(credit.name)
                        }
                    }
                },
                color = color,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DictionaryNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
