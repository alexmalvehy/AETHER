// Ported from BitChord (app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt, and
// rangeBytesFor from shared/.../data/innertube/PlayerClient.kt; github.com/kushagrasinghx/BitChord, GPL-3.0).
// Unchanged but for two things AETHER doesn't have: BitChord's track log, and its report of a refused range
// to its stream resolver.
package app.aether.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Locale

/**
 * Fetches a stream as a run of bounded ranges instead of one open-ended read.
 *
 * googlevideo paces a continuous response down to roughly playback speed: ask for a whole track in
 * one request and it arrives at about 15 kB/s, barely ahead of the rate it is being consumed at. Ask
 * for the same bytes as bounded ranges and each one is served at line rate (BitChord measured it on the
 * same track, over the same connection, seconds apart):
 *
 * ```
 *   GET (unbounded)               15.5 kB/s
 *   Range: bytes=0-2097151         5.7 MB/s
 * ```
 *
 * That's the difference between a four-minute song kept in a second and kept in four minutes, a player
 * that can build a buffer and one that can only just keep up, and Automix measuring the next song before
 * it's due rather than after.
 *
 * Transparent to everything above it: a [androidx.media3.datasource.cache.CacheDataSource] sees one
 * continuous stream of the length it asked for. Bounded requests are split too, so no caller can exceed
 * [rangeBytesFor].
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var rangeBytes = chunkBytes

    /** Set when the request can't be improved on, and is simply forwarded. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * The total size has to be known up front, or there is no way to say where the last range ends. Every
     * progressive googlevideo URL carries it as `clen`, which costs nothing to read; anything else is
     * forwarded as it is.
     */
    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position

        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            passthrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }

        passthrough = false
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
        bytesRemaining = (end - position).coerceAtLeast(0L)
        rangeBytes = minOf(chunkBytes, rangeBytesFor(dataSpec.uri))
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        upstream.open(spec)
        chunkRemaining = length
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // A range that ends early is re-opened for the part that didn't arrive, which is also how the step
        // to the next range happens. The attempt limit is what stops a server that has decided to send
        // nothing from spinning here forever.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    private companion object {
        /** Enough to ride out a truncated range, not enough to hang on a dead one. */
        const val MAX_EMPTY_RANGES = 3
    }

    /** Wraps [upstream]'s sources so everything opened through it is ranged. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }
}

/** BitChord's range size: large enough to amortise a request, small enough to cancel promptly. */
const val CHUNK_BYTES = 2L * 1024 * 1024

/**
 * Largest single range googlevideo reliably serves for [uri]'s client (BitChord's, after InnerTubeX's
 * `mediaRangeChunkSize`): a megabyte, half that for the clients that refuse more. Anything not
 * googlevideo's isn't bounded.
 */
internal fun rangeBytesFor(uri: Uri): Long {
    val host = uri.host ?: return Long.MAX_VALUE
    if (!host.endsWith("googlevideo.com")) return Long.MAX_VALUE
    val name = uri.getQueryParameter("c")?.uppercase(Locale.ROOT)
    return if (name == "ANDROID_VR" || name?.startsWith("TVHTML5_SIMPLY") == true) NARROW_RANGE_BYTES else RANGE_BYTES
}

private const val RANGE_BYTES = 1024L * 1024
private const val NARROW_RANGE_BYTES = 512L * 1024
