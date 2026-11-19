package io.github.brickystudios.freehub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * A photo of the rider's own bike at the top of the Bike tab. Picked once through Android's photo picker, which
 * needs no permission; a scaled-down copy stays in the app's own files and never leaves the phone.
 * Tapping the photo picks another one.
 */
@Composable
fun BikePhoto(key: String) {
    val ctx = LocalContext.current
    val file = remember(key) { BikePhotos.file(ctx, key) }
    var shown by remember(key) { mutableStateOf(BikePhotos.load(file)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) BikePhotos.save(ctx, uri, file)?.let { shown = it }
    }
    val choose = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Spacer(Modifier.height(16.dp))
    val photo = shown
    if (photo == null) Action(tr("Foto vom Rad hinzufügen", "Add a photo of the bike"), Modifier.fillMaxWidth(), primary = false, onClick = choose)
    else Image(
        photo.asImageBitmap(), tr("Dein Rad, tippen für ein anderes Foto", "Your bike, tap for another photo"),
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(Card).clickable(onClick = choose),
        contentScale = ContentScale.Crop,
    )
}

object BikePhotos {
    /** long side in pixels: sharp on a phone, a few hundred kB on disk */
    private const val MAX = 1600

    fun file(ctx: Context, key: String) = File(ctx.filesDir, "photo-$key.jpg")

    fun load(f: File): Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null

    /** Copies the picked photo in, scaled down and turned upright as the camera meant it. null if it can't be read. */
    fun save(ctx: Context, uri: Uri, f: File): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val turn = cr.openInputStream(uri)?.use { degrees(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) } ?: 0
        val upright = if (turn == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(f)
        upright
    }.getOrNull()

    /** the power-of-two step that brings the long side down to [MAX] or just above it */
    fun sampleSize(longSide: Int): Int {
        var s = 1
        while (longSide / (s * 2) >= MAX) s *= 2
        return s
    }

    private fun degrees(orientation: Int) = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
