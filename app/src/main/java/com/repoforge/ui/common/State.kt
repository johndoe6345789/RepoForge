package com.repoforge.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repoforge.data.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** A single value loaded once and refreshable, observable from Compose. */
class Loadable<T>(private val scope: CoroutineScope, private val loader: suspend () -> T) {
    var value by mutableStateOf<T?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loaded = false
    private var job: Job? = null

    /** Loads unless a value is already present or a load is running. */
    fun ensureLoaded() {
        if (!loaded && job?.isActive != true) refresh()
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            try {
                value = loader()
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                loading = false
            }
        }
    }
}

/** An append-only paged list: call [loadMore] as the user scrolls to the end. */
class Paged<T>(private val scope: CoroutineScope, private val fetch: suspend (page: Int) -> Page<T>) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    private var nextPage = 1
    private var job: Job? = null

    val isEmptyAndDone: Boolean get() = items.isEmpty() && endReached && error == null

    fun loadMore() {
        if (loading || endReached || error != null) return
        load(nextPage, replace = false)
    }

    /** Retries after an error, continuing from where the list stopped. */
    fun retry() {
        error = null
        loadMore()
    }

    fun refresh() {
        job?.cancel()
        loading = false
        refreshing = items.isNotEmpty()
        endReached = false
        error = null
        load(1, replace = true)
    }

    private fun load(page: Int, replace: Boolean) {
        loading = true
        job = scope.launch {
            try {
                val result = fetch(page)
                if (replace) items.clear()
                items.addAll(result.items)
                nextPage = result.nextPage ?: page
                endReached = result.nextPage == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                loading = false
                refreshing = false
            }
        }
    }
}
