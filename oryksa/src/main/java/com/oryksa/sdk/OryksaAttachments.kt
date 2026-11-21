package com.oryksa.sdk

/** A photo from the customer (JPEG, PNG or WebP, up to 2 MB). The AI looks at it. */
class OryksaImage(
    /** The picture bytes. */
    val bytes: ByteArray,
    /** `image/jpeg`, `image/png` or `image/webp`. */
    val mime: String = "image/jpeg",
)

/** A document from the customer (PDF, Word, text or CSV, up to 8 MB). The AI reads it. */
class OryksaFile(
    /** The file bytes. */
    val bytes: ByteArray,
    /** The file name, for example `orcamento.pdf`. */
    val name: String,
    /** The type, for example `application/pdf`. */
    val mime: String = "application/octet-stream",
)

/** The largest photo the chat sends (after it is made smaller): 2 MB. */
const val ORYKSA_MAX_PHOTO_BYTES = 2 * 1024 * 1024

/** The largest document the chat sends: 8 MB. */
const val ORYKSA_MAX_FILE_BYTES = 8 * 1024 * 1024

/** The longest side of a photo the chat sends, in pixels. */
const val ORYKSA_PHOTO_MAX_SIDE = 1600

internal val oryksaDocMime = mapOf(
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
)

/** The types the document picker offers. */
internal val oryksaDocPickerTypes = arrayOf(
    "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain", "text/csv", "text/comma-separated-values", "image/*",
)

private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()

/** The type of a file from its name (`application/octet-stream` when it is not a known one). */
fun oryksaMimeOf(name: String): String = oryksaDocMime[extOf(name)] ?: "application/octet-stream"

/** What the chat sends for something the customer attached. */
sealed class OryksaAttachment {
    class Image(val image: OryksaImage) : OryksaAttachment()
    class File(val file: OryksaFile) : OryksaAttachment()
    /** Larger than the limit: nothing is sent and the chat says so. */
    object TooBig : OryksaAttachment()
}

/**
 * A document the customer picked: pictures up to 2 MB go as a photo (the AI looks at it), the rest
 * as a file up to 8 MB.
 */
fun oryksaDocumentAttachment(bytes: ByteArray, name: String, mime: String? = null): OryksaAttachment {
    if (bytes.size > ORYKSA_MAX_FILE_BYTES) return OryksaAttachment.TooBig
    val type = oryksaDocMime[extOf(name)] ?: mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    if (type.startsWith("image/") && bytes.size <= ORYKSA_MAX_PHOTO_BYTES) return OryksaAttachment.Image(OryksaImage(bytes, type))
    return OryksaAttachment.File(OryksaFile(bytes, name, type))
}

/** A photo ready to send (already made smaller): up to 2 MB. */
fun oryksaPhotoAttachment(jpeg: ByteArray): OryksaAttachment =
    if (jpeg.size > ORYKSA_MAX_PHOTO_BYTES) OryksaAttachment.TooBig else OryksaAttachment.Image(OryksaImage(jpeg, "image/jpeg"))

/** The size of a photo of [w] x [h] pixels with its longest side at most [maxSide] (never larger). */
fun oryksaScaledSize(w: Int, h: Int, maxSide: Int = ORYKSA_PHOTO_MAX_SIDE): Pair<Int, Int> {
    if (w <= 0 || h <= 0) return 0 to 0
    val k = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
    return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
}

/** The power of two to decode a [w] x [h] picture with, so it is still at least [maxSide] (memory). */
internal fun oryksaSampleSize(w: Int, h: Int, maxSide: Int = ORYKSA_PHOTO_MAX_SIDE): Int {
    var s = 1
    while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
    return s
}

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard Base64 with padding (works on every Android version and in plain JVM tests). */
fun oryksaBase64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or (bytes[i + 2].toInt() and 0xff)
        out.append(B64[n shr 18 and 63]).append(B64[n shr 12 and 63]).append(B64[n shr 6 and 63]).append(B64[n and 63])
        i += 3
    }
    val rest = bytes.size - i
    if (rest == 1) {
        val n = bytes[i].toInt() and 0xff shl 16
        out.append(B64[n shr 18 and 63]).append(B64[n shr 12 and 63]).append("==")
    } else if (rest == 2) {
        val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
        out.append(B64[n shr 18 and 63]).append(B64[n shr 12 and 63]).append(B64[n shr 6 and 63]).append('=')
    }
    return out.toString()
}
