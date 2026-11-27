package app.aether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.aether.Container
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError

val LocalContainer = staticCompositionLocalOf<Container> { error("No container") }

/** A screen's data: on its way, there, or not coming (with why, and what to do). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val title: String, val body: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

fun SourceError.explain(): Load.Failed = when (this) {
    SourceError.Offline -> Load.Failed("You're offline", "Connect to the internet, then try again. Your library still plays what's on this phone.")
    SourceError.Network -> Load.Failed("The connection dropped", "Check your network and try again.")
    is SourceError.RateLimited -> Load.Failed("The music service is busy", "Give it a moment, then try again.")
    SourceError.AuthRequired -> Load.Failed("Sign in to see this", "This needs a YouTube Music account.")
    // The session was refused: it ended (signed out elsewhere, or a password changed), not the page.
    SourceError.AuthExpired -> Load.Failed("Your sign-in has ended", "In Settings, sign out of YouTube Music and sign in again.")
    is SourceError.NotFound -> Load.Failed("This isn't here any more", "It may have been removed by the music service.")
    is SourceError.NotPlayable -> Load.Failed("This can't play right now", reason ?: "Try again later.")
    SourceError.Unsupported -> Load.Failed("Not available", "This source doesn't offer it.")
    is SourceError.Server -> Load.Failed("The music service didn't answer", "Try again in a moment.")
}

/**
 * Answers kept for a while, so going back to a page shows it at once instead of loading it again.
 * Keys are the request; a failure is never kept.
 */
class Catalog(private val ttlMs: Long = 15 * 60 * 1000L, private val capacity: Int = 200) {
    private class Entry(val at: Long, val value: Any?)
    private val map = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> peek(key: String): T? = map[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.value as T?

    @Synchronized
    fun put(key: String, value: Any?) { map[key] = Entry(System.currentTimeMillis(), value) }

    @Synchronized
    fun forget(key: String) { map.remove(key) }

    /** Everything: after signing in or out, every page is the account's (or the guest's) afresh. */
    @Synchronized
    fun clear() { map.clear() }
}

/**
 * Loads [key]'s data once (or takes it from the catalog), and offers a retry. The state survives
 * recomposition; leaving and coming back reads the catalog. Not [enabled], nothing is asked for
 * (what's already in the catalog still shows).
 */
@Composable
fun <T> rememberLoad(key: String, enabled: Boolean = true, load: suspend () -> Outcome<T>): Pair<Load<T>, () -> Unit> {
    val catalog = LocalContainer.current.catalog
    val attempt = remember(key) { mutableIntStateOf(0) }
    val state: MutableState<Load<T>> = remember(key) {
        mutableStateOf(catalog.peek<T>(key)?.let { Load.Ready(it) } ?: Load.Loading)
    }
    LaunchedEffect(key, attempt.intValue, enabled) {
        if (!enabled) return@LaunchedEffect
        if (state.value is Load.Ready && attempt.intValue == 0) return@LaunchedEffect
        state.value = Load.Loading
        state.value = when (val r = load()) {
            is Outcome.Ok -> { catalog.put(key, r.value); Load.Ready(r.value) }
            is Outcome.Err -> r.error.explain()
        }
    }
    return state.value to { attempt.intValue++ }
}
