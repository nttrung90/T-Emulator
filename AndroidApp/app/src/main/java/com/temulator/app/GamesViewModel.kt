package com.temulator.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.temulator.app.data.db.GameRecord
import com.temulator.app.search.GameSearchNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    object Idle : ScanUiState
    object Scanning : ScanUiState
    data class Success(val foundCount: Int) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

data class GamesUiState(
    val games: List<GameRecord> = emptyList(),
    val totalCount: Int = 0,
    val filteredCount: Int = 0,
    val searchQuery: String = "",
    val isLibraryEmpty: Boolean = false,
    val isNoSearchResult: Boolean = false,
    val scanState: ScanUiState = ScanUiState.Idle
)

@OptIn(FlowPreview::class)
class GamesViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository = GameRepository(application)
    private val scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    companion object {
        private const val KEY_SEARCH_QUERY = "search_query"
    }

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    private val allGamesFlow = repository.getAllGamesFlow()

    // Debounce search query changes by 200ms when non-empty to ensure smooth typing on large libraries
    private val debouncedQueryFlow = searchQuery
        .debounce { query -> if (query.isEmpty()) 0L else 200L }
        .distinctUntilChanged()

    val uiState: StateFlow<GamesUiState> = combine(
        allGamesFlow,
        debouncedQueryFlow,
        scanState
    ) { allGames, query, scan ->
        val filtered = if (query.isBlank()) {
            allGames
        } else {
            allGames.filter { game ->
                GameSearchNormalizer.matches(game.displayName, query) ||
                        GameSearchNormalizer.matches(game.originalFileName, query)
            }
        }

        val total = allGames.size
        val filteredCount = filtered.size

        GamesUiState(
            games = filtered,
            totalCount = total,
            filteredCount = filteredCount,
            searchQuery = query,
            isLibraryEmpty = total == 0,
            isNoSearchResult = total > 0 && filteredCount == 0,
            scanState = scan
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GamesUiState()
    )

    fun setSearchQuery(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun getScanFolderUri(): Uri? {
        return repository.getScanFolderUri()
    }

    fun setScanFolderUri(uri: Uri) {
        repository.setScanFolderUri(uri)
    }

    fun scanFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            scanState.value = ScanUiState.Scanning
            try {
                val scannedGames = repository.scanDirectory(treeUri)
                repository.addGameRecords(scannedGames)
                scanState.value = ScanUiState.Success(scannedGames.size)
            } catch (e: Exception) {
                scanState.value = ScanUiState.Error(e.localizedMessage ?: "Scan error")
            }
        }
    }

    fun resetScanState() {
        scanState.value = ScanUiState.Idle
    }

    fun deleteGame(game: GameRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeGame(game.gameId)
        }
    }
}
