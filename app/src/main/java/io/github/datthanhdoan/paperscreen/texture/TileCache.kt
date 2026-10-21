package io.github.datthanhdoan.paperscreen.texture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Raw premultiplied RGBA tiles on disk, keyed by everything that affects the pixels, so a
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
        val file = File(dir, key(params, densityDpi, windowAlpha) + EXTENSION)
        read(file, size)?.let { return it }

        val start = System.nanoTime()
        val tile = TextureGenerator.generate(params, densityDpi, windowAlpha, checkCancelled)
        Log.i(TAG, "Generated ${size}px tile for ${densityDpi}dpi in ${(System.nanoTime() - start) / 1_000_000} ms")
        write(file, tile)
        return tile
    }

    private fun read(file: File, size: Int): TextureTile? = try {
        if (file.length() == size.toLong() * size * 4) TextureTile(size, file.readBytes()) else null
    } catch (e: IOException) {
        null
    }

    private fun write(file: File, tile: TextureTile) {
        try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(tile.pixels)
            tmp.renameTo(file)
            prune(keep = file)
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache tile", e)
        }
    }

    private fun prune(keep: File) {
        // ".a8" files are ALPHA_8 tiles from v0.1 and can never be read again.
        dir.listFiles { f -> f.name.endsWith(".a8") }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.name.endsWith(EXTENSION) && f != keep } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES - 1)
            .forEach { it.delete() }
    }

    private fun key(p: TextureParams, dpi: Int, windowAlpha: Float): String {
        val h = listOf(VERSION, p.preset.name, p.intensity, p.coarseness, p.relief, p.seed, dpi, windowAlpha)
            .hashCode()
        return "tile_${dpi}_" + Integer.toHexString(h)
    }

    private companion object {
        const val TAG = "TileCache"
        const val MAX_FILES = 6
        const val EXTENSION = ".rgba"
        /** Bump when TextureGenerator output changes so stale files are not reused. */
        const val VERSION = 2
    }
}

fun TextureTile.toBitmap(): Bitmap {
    // ARGB_8888 rows are 4 * size bytes with no padding, and the buffer is already
    // premultiplied RGBA in memory order, so the copy is 1:1.
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return bitmap
}
