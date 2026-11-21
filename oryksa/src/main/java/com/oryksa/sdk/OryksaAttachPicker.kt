package com.oryksa.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** The SDK's own FileProvider (its own class, so it never clashes with the app's providers). */
class OryksaFileProvider : FileProvider()

/** What came back from the attach menu. */
internal sealed class OryksaPicked {
    object Cancelled : OryksaPicked()
    /** The file could not be read. */
    object Failed : OryksaPicked()
    class Picked(val attachment: OryksaAttachment) : OryksaPicked()
}

/** The three choices of the attach menu ([camera] is null when the device has no camera). */
internal class OryksaAttachActions(val gallery: () -> Unit, val camera: (() -> Unit)?, val document: () -> Unit)

/** A picture as a JPEG of at most 1600 pixels on its longest side, at 80%, turned the right way up. */
internal fun oryksaJpegOf(context: Context, uri: Uri, maxSide: Int = ORYKSA_PHOTO_MAX_SIDE, quality: Int = 80): ByteArray? {
    val cr = context.contentResolver
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            val (w, h) = oryksaScaledSize(info.size.width, info.size.height, maxSide)
            if (w < info.size.width) decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = oryksaSampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        var b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val (w, h) = oryksaScaledSize(b.width, b.height, maxSide)
        if (w < b.width) b = Bitmap.createScaledBitmap(b, w, h, true)
        val degrees = if (Build.VERSION.SDK_INT >= 24) runCatching {
            cr.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f) else 0f
        if (degrees != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)
        b
    }
    return ByteArrayOutputStream().use { out ->
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        out.toByteArray()
    }
}

/** A document the customer picked: its name, size check and bytes. */
internal fun oryksaDocumentOf(context: Context, uri: Uri): OryksaAttachment? {
    val cr = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sz = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (sz >= 0 && !c.isNull(sz)) size = c.getLong(sz)
            }
        }
    }
    if (size > ORYKSA_MAX_FILE_BYTES) return OryksaAttachment.TooBig
    val bytes = cr.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > ORYKSA_MAX_FILE_BYTES) return OryksaAttachment.TooBig
        }
        out.toByteArray()
    } ?: return null
    return oryksaDocumentAttachment(bytes, name, cr.getType(uri))
}

/** True when the app itself declares CAMERA: then Android needs it granted before the camera opens. */
private fun declaresCamera(context: Context): Boolean = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
}.getOrDefault(false)

/**
 * The pickers of the attach button: the photo picker (PickVisualMedia), the camera app (TakePicture,
 * through the SDK's FileProvider; CAMERA is asked only when your app declares it) and documents
 * (OpenDocument). [onPicked] gets what to send.
 */
@Composable
internal fun rememberOryksaAttach(onPicked: (OryksaPicked) -> Unit): OryksaAttachActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun handle(work: () -> OryksaAttachment?) {
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { work() }.getOrNull() }
            onPicked(if (a == null) OryksaPicked.Failed else OryksaPicked.Picked(a))
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onPicked(OryksaPicked.Cancelled) else handle { oryksaJpegOf(context, uri)?.let { oryksaPhotoAttachment(it) } }
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri?.let { Uri.parse(it) }
        if (!ok || u == null) onPicked(OryksaPicked.Cancelled) else handle { oryksaJpegOf(context, u)?.let { oryksaPhotoAttachment(it) } }
    }
    fun openCamera() {
        runCatching {
            val dir = File(context.cacheDir, "oryksa_camera").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the last photo is kept
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val u = FileProvider.getUriForFile(context, context.packageName + ".oryksa.fileprovider", f)
            cameraUri = u.toString()
            take.launch(u)
        }.onFailure { onPicked(OryksaPicked.Failed) }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else onPicked(OryksaPicked.Failed)
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onPicked(OryksaPicked.Cancelled) else handle { oryksaDocumentOf(context, uri) }
    }
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return OryksaAttachActions(
        gallery = {
            runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .onFailure { onPicked(OryksaPicked.Failed) }
        },
        camera = if (!hasCamera) null else ({
            if (declaresCamera(context) && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                askCamera.launch(Manifest.permission.CAMERA)
            } else openCamera()
        }),
        document = {
            runCatching { document.launch(oryksaDocPickerTypes) }.onFailure { onPicked(OryksaPicked.Failed) }
        },
    )
}

private val paperclipPath = PathParser().parsePathString(
    "M21.44 11.05 l-9.19 9.19 a6 6 0 0 1 -8.49 -8.49 l8.57 -8.57 A4 4 0 1 1 18 8.84 l-8.59 8.57 a2 2 0 0 1 -2.83 -2.83 l8.49 -8.48"
).toPath()

private val documentPath = PathParser().parsePathString(
    "M15 2 H6 a2 2 0 0 0 -2 2 v16 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 V7 Z M14 2 v4 a2 2 0 0 0 2 2 h4 M10 9 H8 M16 13 H8 M16 17 H8"
).toPath()

@Composable
private fun Glyph(path: androidx.compose.ui.graphics.Path, color: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The paperclip of the attach button (drawn with lines, no icon library needed). */
@Composable
internal fun PaperclipGlyph(color: Color, size: Int = 20) = Glyph(paperclipPath, color, size)

/** The document icon of a file the customer sent. */
@Composable
internal fun DocumentGlyph(color: Color, size: Int = 20) = Glyph(documentPath, color, size)
