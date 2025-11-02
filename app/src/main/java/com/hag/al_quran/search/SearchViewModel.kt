package com.hag.al_quran.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SearchRepository(app)

    data class UiState(
        val query: String = "",
        val options: SearchOptions = SearchOptions(),
        val isLoading: Boolean = false,
        val totalCount: Int = 0,
        val results: List<SearchResultItem> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // مراقبة الاستعلام + الخيارات مع ديباونس
        viewModelScope.launch {
            _state.debounce { _state.value.options.debounceMs }
                .mapLatest { st ->
                    if (st.query.isBlank()) return@mapLatest st.copy(results = emptyList(), totalCount = 0, isLoading = false)
                    // ابدأ تحميل
                    _state.update { it.copy(isLoading = true) }
                    val res = withContext(Dispatchers.Default) { repo.search(st.query, st.options) }
                    val cnt = withContext(Dispatchers.Default) { repo.count(st.query, st.options) }
                    st.copy(results = res, totalCount = cnt, isLoading = false)
                }
                .distinctUntilChanged()
                .collect { newSt -> _state.value = newSt }
        }
    }

    fun setQuery(q: String) { _state.update { it.copy(query = q) } }

    fun setMode(mode: SearchOptions.Mode) {
        _state.update { it.copy(options = it.options.copy(mode = mode)) }
    }

    fun setExactTashkeel(enabled: Boolean) {
        _state.update { it.copy(options = it.options.copy(exactTashkeel = enabled)) }
    }
}