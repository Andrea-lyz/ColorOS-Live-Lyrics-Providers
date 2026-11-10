package io.github.andrealtb.coloroslyrics.provider.universal.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andrealtb.coloroslyrics.provider.universal.NoLyricPolicy
import io.github.andrealtb.coloroslyrics.provider.universal.R
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppNetwork
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.SearchCandidate
import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import io.github.andrealtb.coloroslyrics.provider.universal.api.SongMatchScorer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.andrealtb.coloroslyrics.provider.universal.cache.UniversalLyricCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SearchUiState {
    var query by mutableStateOf("")
    var selectedFilter by mutableStateOf<String?>(null)
    var candidates by mutableStateOf<List<SearchCandidate>>(emptyList())
    var statusText by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var seeded by mutableStateOf(false)
}
private data class CandidateOrder(val bucket: Int, val priority: Int, val originalIndex: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    initialQuery: String,
    currentPkg: String,
    currentTitle: String,
    currentArtist: String,
    currentGen: Long,
    onLyricBound: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filterSources by produceState(
        initialValue = LyricsSourceAggregator.SourceId.values().toList(),
        key1 = context
    ) {
        val configured = withContext(Dispatchers.IO) { LyricsSourceConfigStore.read(context) }
        val ordered = configured.mapNotNull { id ->
            runCatching { LyricsSourceAggregator.SourceId.valueOf(id) }.getOrNull()
        }.distinct()
        value = ordered.ifEmpty { LyricsSourceAggregator.SourceId.values().toList() }
    }

    fun orderedSources(): List<LyricsSourceAggregator.SourceId> {
        val configured = LyricsSourceConfigStore.read(context)
        val ordered = configured.mapNotNull { id ->
            runCatching { LyricsSourceAggregator.SourceId.valueOf(id) }.getOrNull()
        }.distinct()
        return ordered.ifEmpty { LyricsSourceAggregator.SourceId.values().toList() }
    }

    /**
     * Relevance first, then the user's source order. A shared edition suffix no longer lets a
     * different song outrank the real one, and a disabled source never appears at all.
     */
    fun mergeAndSort(
        rows: List<SearchCandidate>,
        sources: List<LyricsSourceAggregator.SourceId>,
        searchQuery: String = state.query
    ): List<SearchCandidate> {
        val priority = sources.withIndex().associate { (index, id) -> id.label to index }
        val rankInSource = HashMap<String, Int>()
        val trimmedQuery = searchQuery.trim()
        val matchesPlayingTrack = currentTitle.isNotBlank() &&
            trimmedQuery.contains(currentTitle.trim(), ignoreCase = true)
        val targetTitle = if (matchesPlayingTrack) currentTitle else trimmedQuery
        val targetArtist = if (matchesPlayingTrack) currentArtist else ""
        // A source disabled in the priority screen must not survive in a stale candidate list.
        return rows.filter { it.source in priority }.mapIndexed { index, item ->
            val sourceRank = rankInSource.getOrDefault(item.source, 0)
            rankInSource[item.source] = sourceRank + 1
            val bucket = if (targetTitle.isBlank()) 0 else {
                val score = SongMatchScorer.calculate(
                    targetTitle,
                    targetArtist,
                    null,
                    item.title,
                    item.artist,
                    item.durationMs,
                    rankIndex = sourceRank
                )
                when {
                    score >= 0.70 -> 0
                    score >= 0.45 -> 1
                    else -> 2
                }
            }
            CandidateOrder(bucket, priority[item.source] ?: Int.MAX_VALUE, index) to item
        }.sortedWith(
            compareBy<Pair<CandidateOrder, SearchCandidate>> { it.first.bucket }
                .thenBy { it.first.priority }
                .thenBy { it.first.originalIndex }
        ).map { it.second }
    }

    fun doSearch(kw: String) {
        if (kw.isBlank()) return
        state.isSearching = true
        state.statusText = context.getString(R.string.searching_sources)
        scope.launch(Dispatchers.IO) {
            UniversalAppNetwork.initialize(context)
            val sources = orderedSources()
            val aggregator = LyricsSourceAggregator.shared
            // Parallel: a slow source can no longer delay or hide the ones behind it.
            val perSource = coroutineScope {
                sources.map { id -> async { aggregator.searchCandidates(id, kw) } }.awaitAll()
            }
            val combined = mergeAndSort(perSource.flatten(), sources, kw)
            withContext(Dispatchers.Main) {
                state.candidates = combined
                state.statusText = context.getString(R.string.search_results, combined.size)
                state.isSearching = false
            }
        }
    }

    /** Refills one source when the combined search lost it, so a chip tap self-heals. */
    fun doSearchSource(id: LyricsSourceAggregator.SourceId, kw: String) {
        if (kw.isBlank()) return
        scope.launch(Dispatchers.IO) {
            UniversalAppNetwork.initialize(context)
            val rows = LyricsSourceAggregator.shared.searchCandidates(id, kw)
            if (rows.isEmpty()) return@launch
            val sources = orderedSources()
            withContext(Dispatchers.Main) {
                val merged = mergeAndSort(
                    (state.candidates + rows).distinctBy { it.source + it.id },
                    sources,
                    kw
                )
                state.candidates = merged
                state.statusText = context.getString(R.string.search_results, merged.size)
            }
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) state.query = initialQuery
    }

    LaunchedEffect(Unit) {
        if (!state.seeded) {
            state.seeded = true
            if (state.query.isBlank()) {
                state.query = initialQuery.ifBlank {
                    listOf(currentTitle, currentArtist).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
    }

    val displayedCandidates = remember(state.candidates, state.selectedFilter) {
        if (state.selectedFilter == null) state.candidates
        else state.candidates.filter { it.source == state.selectedFilter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(
                        R.string.search_now_playing,
                        currentTitle.ifBlank { stringResource(R.string.track_placeholder) },
                        currentArtist.ifBlank { stringResource(R.string.artist_placeholder) }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                }
            },
            windowInsets = WindowInsets.statusBars,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.search_songs_hint)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { doSearch(state.query) },
                modifier = Modifier.height(56.dp)
            ) {
                Text(stringResource(R.string.action_search), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 来源 Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val allLabel = stringResource(R.string.filter_all)
            val qqLabel = stringResource(R.string.source_qq)
            val netEaseLabel = stringResource(R.string.source_netease)
            val appleLabel = stringResource(R.string.source_apple)
            val chips = listOf<Pair<String, String?>>(allLabel to null) + filterSources.map { id ->
                val label = when (id) {
                    LyricsSourceAggregator.SourceId.QQ -> qqLabel
                    LyricsSourceAggregator.SourceId.NETEASE -> netEaseLabel
                    LyricsSourceAggregator.SourceId.APPLE_MUSIC -> appleLabel
                }
                label to id.label
            }
            chips.forEach { (label, src) ->
                val isSelected = state.selectedFilter == src
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        state.selectedFilter = src
                        val id = LyricsSourceAggregator.SourceId.values().firstOrNull { it.label == src }
                        if (id != null && state.query.isNotBlank() && state.candidates.none { it.source == src }) {
                            doSearchSource(id, state.query)
                        }
                    },
                    label = { Text(label) },
                    leadingIcon = {
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                val json = NoLyricPolicy.payloadJson(currentTitle, currentArtist, NoLyricPolicy.REASON_USER)
                UniversalLyricCache.putManualBinding(context, currentTitle, currentArtist, json)
                val intent = Intent(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC).apply {
                    putExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE, currentPkg)
                    putExtra(UniversalSnapshotStore.EXTRA_GENERATION, currentGen)
                    putExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO, json)
                    putExtra("extra_is_manual", true)
                    putExtra("extra_track_key", "${currentTitle.trim()}|${currentArtist.trim()}".lowercase())
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(0x01000000)
                }
                context.sendBroadcast(intent)
                Toast.makeText(context, context.getString(R.string.toast_no_lyric), Toast.LENGTH_SHORT).show()
                onLyricBound()
            }) {
                Text(stringResource(R.string.confirm_no_lyric))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displayedCandidates, key = { it.source + it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clickable {
                            scope.launch(Dispatchers.IO) {
                                UniversalAppNetwork.initialize(context)
                                val aggregator = LyricsSourceAggregator.shared
                                val result = LyricsSourceAggregator.shared.fetchByCandidate(item)
                                if (result != null && result.lyric.isNotBlank()) {
                                    val json = JSONObject().apply {
                                        put("songName", currentTitle.ifBlank { result.title })
                                        put("artist", currentArtist.ifBlank { result.artist })
                                        put("album", item.album)
                                        put("songId", "universal-$currentGen")
                                        put("lyricType", 0)
                                        put("id", "")
                                        put("noLyric", false)
                                        put("lyric", result.lyric)
                                        put("rawLyric", result.rawLyric ?: result.lyric)
                                        if (!result.translationLyric.isNullOrBlank()) {
                                            put("transLyric", result.translationLyric)
                                            put("translationLyric", result.translationLyric)
                                            put("translationLanguageTag", Locale.getDefault().toLanguageTag())
                                        }
                                        put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
                                        put("source", result.source + "/manual")
                                        put("sessionGeneration", currentGen)
                                        put("remark", "[CLLUniversal]")
                                    }.toString()

                                    UniversalLyricCache.putManualBinding(context, currentTitle, currentArtist, json)
                                    val intent = Intent(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC).apply {
                                        putExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE, currentPkg)
                                        putExtra(UniversalSnapshotStore.EXTRA_GENERATION, currentGen)
                                        putExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO, json)
                                        putExtra("extra_is_manual", true)
                                        putExtra("extra_track_key", "${currentTitle.trim()}|${currentArtist.trim()}".lowercase())
                                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                        addFlags(0x01000000)
                                    }
                                    context.sendBroadcast(intent)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.toast_lyric_bound), Toast.LENGTH_SHORT).show()
                                        onLyricBound()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.toast_lyric_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(130.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                        ) {
                            CoverArt(url = item.coverUrl)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(item.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${item.artist} · ${item.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    formatDuration(item.durationMs),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverArt(url: String) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf(CoverArtCache.peek(url)) }
    LaunchedEffect(url) {
        if (bitmap == null && url.isNotBlank()) {
            bitmap = CoverArtCache.load(context, url)
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSec = durationMs / 1000L
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}
