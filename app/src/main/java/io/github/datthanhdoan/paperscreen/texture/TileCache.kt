package io.github.datthanhdoan.paperscreen.texture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Raw ALPHA_8 tiles on disk, keyed by everything that affects the pixels, so a
 * service restart (START_STICKY, boot) does not have to regenerate the texture.
 */
class TileCache(context: Context) {

    private val dir = File(context.cacheDir, "tiles")

    fun loadOrGenerate(
        params: TextureParams,
        densityDpi: Int,
        windowAlpha: Float,
        checkCancelled: () -> Unit,
    ): TextureTile {
        val size = TextureGenerator.tileSizeFor(densityDpi)
        val file = File(dir, key(params, densityDpi, windowAlpha) + ".a8")
        read(file, size)?.let { return it }

        val start = System.nanoTime()
        val tile = TextureGenerator.generate(params, densityDpi, windowAlpha, checkCancelled)
        Log.i(TAG, "Generated ${size}px tile for ${densityDpi}dpi in ${(System.nanoTime() - start) / 1_000_000} ms")
        write(file, tile)
        return tile
    }

    private fun read(file: File, size: Int): TextureTile? = try {
        if (file.length() == size.toLong() * size) TextureTile(size, file.readBytes()) else null
    } catch (e: IOException) {
        null
    }

    private fun write(file: File, tile: TextureTile) {
        try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(tile.alpha)
            tmp.renameTo(file)
            prune(keep = file)
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache tile", e)
        }
    }

    private fun prune(keep: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".a8") && f != keep } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES - 1)
            .forEach { it.delete() }
    }

    private fun key(p: TextureParams, dpi: Int, windowAlpha: Float): String {
        val h = listOf(VERSION, p.intensity, p.coarseness, p.seed, dpi, windowAlpha).hashCode()
        return "tile_${dpi}_" + Integer.toHexString(h)
    }

    private companion object {
        const val TAG = "TileCache"
        const val MAX_FILES = 6
        /** Bump when TextureGenerator output changes so stale files are not reused. */
        const val VERSION = 1
    }
}

fun TextureTile.toBitmap(): Bitmap {
    // size is a multiple of 8, so ALPHA_8 rows are not padded and the copy is 1:1.
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
    return bitmap
}
