package com.oryksa.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL

/**
 * What your app does with a link the AI gave (a product, a service, a page). Return true when your
 * app opened it itself (for example its own product screen); return false and the SDK opens it in the
 * browser (or the app that owns the address).
 */
typealias OryksaLinkHandler = (Uri) -> Boolean

private val oryksaUrl = Regex("""https?://[^\s<>"'\)\]]+""", RegexOption.IGNORE_CASE)
private val oryksaMail = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
private val oryksaTrailing = Regex("""[.,;:!?…]+$""")

private fun trimEnd(u: String) = u.replace(oryksaTrailing, "")

/** The distinct web links of [text], in order (at most [max]). */
fun oryksaLinksIn(text: String, max: Int = 3): List<String> {
    val out = mutableListOf<String>()
    for (m in oryksaUrl.findAll(text)) {
        val u = trimEnd(m.value)
        if (u !in out) out.add(u)
        if (out.size >= max) break
    }
    return out
}

/** [text] without its web links (for the voice screen: the link is on the button). */
fun oryksaWithoutLinks(text: String): String = text
    .replace(oryksaUrl, "")
    .replace(Regex("""[ \t]+\n"""), "\n")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .replace(Regex("""[ \t]{2,}"""), " ")
    .replace(Regex("""\s+([.,;!?])"""), "\$1")
    .replace(Regex(""":\s*$""", RegexOption.MULTILINE), "")
    .trim()

/** The parts of [s] that are links: web addresses and e-mails (as `mailto:`), in order. */
internal fun oryksaLinkHits(s: String): List<Triple<Int, Int, String>> {
    val hits = mutableListOf<Triple<Int, Int, String>>()
    for (m in oryksaUrl.findAll(s)) {
        val u = trimEnd(m.value)
        hits.add(Triple(m.range.first, m.range.first + u.length, u))
    }
    for (m in oryksaMail.findAll(s)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (hits.any { start < it.second && end > it.first }) continue
        hits.add(Triple(start, end, "mailto:" + m.value))
    }
    return hits.sortedBy { it.first }
}

/**
 * Text of a message with **bold** parts and links (web and e-mail) the customer can tap, in
 * [linkColor] and underlined. [onTap] gets the address of a tapped link; null leaves it to the system.
 */
fun oryksaRichText(text: String, linkColor: Color, onTap: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    fun links(s: String) {
        var at = 0
        for ((start, end, url) in oryksaLinkHits(s)) {
            if (start < at) continue
            if (start > at) append(s.substring(at, start))
            val styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            val link = LinkAnnotation.Url(url, styles, onTap?.let { tap -> LinkInteractionListener { tap(url) } })
            withLink(link) { append(s.substring(start, end)) }
            at = end
        }
        if (at < s.length) append(s.substring(at))
    }
    val parts = text.split("**")
    if (parts.size < 3) { links(text); return@buildAnnotatedString }
    parts.forEachIndexed { i, p ->
        if (p.isEmpty()) return@forEachIndexed
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { links(p) } else links(p)
    }
}

/** Opens [link]: your [handler] first, then the browser or the app that owns the address. */
fun oryksaOpenLink(context: Context, link: Uri, handler: OryksaLinkHandler? = null) {
    if (handler?.invoke(link) == true) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, link)
            .addFlags(if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The host of [url] without `www.` (what the card shows when it has no title). */
internal fun oryksaHostOf(url: String): String =
    (runCatching { URI(url).host }.getOrNull() ?: url.substringAfter("://").substringBefore('/').substringBefore('?'))
        .orEmpty().removePrefix("www.")

/** Title, then the sub line (price · site, or site, or host), of the card for [url]. */
internal fun oryksaCardTexts(url: String, p: OryksaLinkPreview?): Pair<String, String> {
    val host = oryksaHostOf(url)
    val title = p?.title?.takeIf { it.isNotEmpty() } ?: host
    val sub = when {
        !p?.price.isNullOrEmpty() -> "${p!!.price} · ${p.site}"
        !p?.site.isNullOrEmpty() -> p!!.site
        else -> host
    }
    return title to sub
}

/** A small picture from the web (decoded at the size of the card). */
private suspend fun loadPicture(url: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = URL(url).openStream().use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** A link drawn with lines (no icon library needed). */
@Composable
private fun LinkGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = w * 0.1f)
        rotate(-45f) {
            drawRoundRect(color, topLeft = Offset(w * 0.04f, h * 0.35f), size = Size(w * 0.52f, h * 0.3f), cornerRadius = CornerRadius(h * 0.15f), style = st)
            drawRoundRect(color, topLeft = Offset(w * 0.44f, h * 0.35f), size = Size(w * 0.52f, h * 0.3f), cornerRadius = CornerRadius(h * 0.15f), style = st)
        }
    }
}

/**
 * The card under a reply for a link of the business's own site: its picture, its title and an Open
 * button. Links of other sites show the address and the button. [onOpened] is called when the card
 * opens the link (the voice screen closes itself).
 */
@Composable
fun OryksaLinkCard(
    url: String,
    client: OryksaClient,
    openLabel: String,
    modifier: Modifier = Modifier,
    onLink: OryksaLinkHandler? = null,
    accent: Color = Color(0xFF5B47E0),
    ink: Color = Color(0xFF1F2937),
    muted: Color = Color(0xFF6B7280),
    background: Color = Color.White,
    line: Color = Color(0xFFE5E7EB),
    onOpened: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf<OryksaLinkPreview?>(null) }
    var picture by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val p = client.linkPreview(url) ?: return@LaunchedEffect
        preview = p
        if (p.image.isNotEmpty()) picture = loadPicture(p.image, 156)
    }
    val (title, sub) = oryksaCardTexts(url, preview)
    val open = {
        onOpened?.invoke()
        oryksaOpenLink(context, Uri.parse(url), onLink)
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.clip(shape).background(background).border(1.dp, line, shape)
            .clickable(role = Role.Button) { open() }
            .semantics(mergeDescendants = true) { contentDescription = "$openLabel: $title" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(line), contentAlignment = Alignment.Center) {
            val pic = picture
            if (pic != null) Image(pic, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else LinkGlyph(muted)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 13.5.sp, lineHeight = 17.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(accent)
                .clickable(role = Role.Button) { open() }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(openLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 9.dp))
        }
    }
}

/** The cards for the links of [text] (at most 3), one under the other. */
@Composable
internal fun OryksaLinkCards(
    text: String,
    client: OryksaClient,
    openLabel: String,
    theme: OryksaChatTheme,
    onLink: OryksaLinkHandler?,
    modifier: Modifier = Modifier,
    onOpened: (() -> Unit)? = null,
) {
    for (u in oryksaLinksIn(text)) {
        OryksaLinkCard(u, client, openLabel, modifier.fillMaxWidth(), onLink = onLink, accent = theme.accent, ink = theme.ink,
            muted = theme.muted, background = theme.background, onOpened = onOpened)
    }
}
