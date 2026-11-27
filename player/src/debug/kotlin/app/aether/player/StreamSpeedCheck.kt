package app.aether.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.StreamPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug builds only: how fast a song's stream comes, read as keeping it reads it, once as one open-ended
 * request and once as [ChunkedDataSource]'s bounded ranges. Nothing is kept: the bytes are read and let go.
 */
@UnstableApi
object StreamSpeedCheck {
    suspend fun measure(context: Context, id: MediaId, bytes: Long = 3L * 1024 * 1024): List<String> = withContext(Dispatchers.IO) {
        val env = context.applicationContext as PlaybackEnvironment
        val stream = when (val r = env.stream(id, StreamPurpose.KEEP)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return@withContext listOf("FAIL couldn't resolve the stream: ${r.error}")
        }
        fun plain() = DefaultDataSource.Factory(context, AudioStore.http(env)).createDataSource()
        val spec = DataSpec.Builder().setUri(stream.url).setHttpRequestHeaders(stream.headers).build()
        val out = mutableListOf("stream: ${stream.contentLength ?: "?"} bytes, ${android.net.Uri.parse(stream.url).host}")
        // A first small read, so neither run is the one that pays for the connection.
        read(ChunkedDataSource(plain(), CHUNK_BYTES), spec, 64L * 1024, 10_000)
        for ((name, source) in listOf("one long request" to plain(), "bounded ranges" to ChunkedDataSource(plain(), CHUNK_BYTES))) {
            val (got, ms) = read(source, spec, bytes, 15_000)
            out += "$name: ${got / 1024} kB in $ms ms = ${if (ms > 0) got * 1000 / 1024 / ms else 0} kB/s"
        }
        out
    }

    /** Reads up to [bytes] of [spec] or for [limitMs], whichever comes first: how much, and how long it took. */
    private fun read(source: DataSource, spec: DataSpec, bytes: Long, limitMs: Long): Pair<Long, Long> {
        val buffer = ByteArray(64 * 1024)
        val start = System.nanoTime()
        var got = 0L
        try {
            source.open(spec)
            while (got < bytes && (System.nanoTime() - start) / 1_000_000 < limitMs) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
                got += n
            }
        } finally {
            source.close()
        }
        return got to (System.nanoTime() - start) / 1_000_000
    }
}
