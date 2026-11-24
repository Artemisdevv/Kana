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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.dictionary.DictionaryResult
import com.artemis.ebookreader.data.dictionary.OfflineDictionary
import com.artemis.ebookreader.data.dictionary.OnlineDictionary
import com.artemis.ebookreader.data.dictionary.dictionaryQuery
import kotlinx.coroutines.launch

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
                    text = query?.selection ?: stringResource(R.string.dictionary_title),
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
                    item {
                        DictionarySource(
                            stringResource(R.string.dictionary_offline),
                            stringResource(R.string.dictionary_wordnet),
                        )
                    }
                    dictionaryResults(offline, query.key, online = false)
                    item { HorizontalDivider() }
                    if (onlineRequest == 0) {
                        item {
                            Column {
                                TextButton(onClick = { onlineRequest++ }) {
                                    Text(stringResource(R.string.dictionary_more_online))
                                }
                                DictionaryNote(stringResource(R.string.dictionary_online_privacy))
                            }
                        }
                    } else {
                        item {
                            DictionarySource(
                                stringResource(R.string.dictionary_online),
                                stringResource(R.string.dictionary_online_provider),
                            )
                        }
                        dictionaryResults(online, query.key, online = true)
                        if (online == DictionaryResult.Unavailable || online == DictionaryResult.RateLimited) {
                            item {
                                TextButton(onClick = {
                                    online = null
                                    onlineRequest++
                                }) { Text(stringResource(R.string.dictionary_retry_online)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.dictionaryResults(result: DictionaryResult?, key: String, online: Boolean) {
    when (result) {
        null -> item { DictionaryNote(stringResource(R.string.dictionary_looking_up)) }
        DictionaryResult.Missing -> item {
            DictionaryNote(stringResource(if (online) R.string.dictionary_online_missing else R.string.dictionary_offline_missing))
        }
        DictionaryResult.Unavailable -> item {
            DictionaryNote(stringResource(if (online) R.string.dictionary_online_unavailable else R.string.dictionary_offline_unavailable))
        }
        DictionaryResult.RateLimited -> item { DictionaryNote(stringResource(R.string.dictionary_online_rate_limited)) }
        is DictionaryResult.Found -> for (entry in result.entries) {
            if (entry.word != key || entry.phonetics.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (entry.word != key) {
                            val label = if (online) entry.word else stringResource(R.string.dictionary_base_word, entry.word)
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                        if (entry.phonetics.isNotEmpty()) DictionaryNote(entry.phonetics.joinToString("  "))
                    }
                }
            }
            if (entry.credits.isNotEmpty()) {
                item {
                    val browser = LocalUriHandler.current
                    Column {
                        for (credit in entry.credits) {
                            TextButton(onClick = { runCatching { browser.openUri(credit.url) } }) { Text(credit.name) }
                        }
                    }
                }
            }
            for (meaning in entry.meanings) {
                meaning.partOfSpeech?.let { part ->
                    item {
                        Text(
                            part, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
                items(meaning.senses.size) { index ->
                    val sense = meaning.senses[index]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sense.definition, style = MaterialTheme.typography.bodyMedium)
                        for (example in sense.examples) {
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
        }
    }
}

@Composable
private fun DictionarySource(title: String, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        DictionaryNote(source)
    }
}

@Composable
private fun DictionaryNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
