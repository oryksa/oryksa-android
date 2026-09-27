package com.oryksa.sdk

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Colors of the chat. The defaults are the ORYKSA website chat colors. */
data class OryksaChatTheme(
    val accent: Color = Color(0xFF5B57E0),
    val ink: Color = Color(0xFF161B3D),
    val soft: Color = Color(0xFFEEEBFB),
    val background: Color = Color.White,
    val muted: Color = Color(0xFF6B7280),
)

internal val oryksaTexts = mapOf(
    "en" to mapOf("talk" to "Talk to", "ph" to "Type your question", "send" to "Send", "err" to "Sorry, something went wrong. Try again.", "voice" to "Talk by voice"),
    "pt" to mapOf("talk" to "Falar com", "ph" to "Escreve a tua pergunta", "send" to "Enviar", "err" to "Desculpa, algo correu mal. Tenta de novo.", "voice" to "Falar por voz"),
    "br" to mapOf("talk" to "Falar com", "ph" to "Digite sua pergunta", "send" to "Enviar", "err" to "Desculpe, algo deu errado. Tente de novo.", "voice" to "Falar por voz"),
    "es" to mapOf("talk" to "Hablar con", "ph" to "Escribe tu pregunta", "send" to "Enviar", "err" to "Lo siento, algo salió mal. Inténtalo de nuevo.", "voice" to "Hablar por voz"),
)

internal fun oryksaLang(l: String) = if (oryksaTexts.containsKey(l)) l else "en"

private data class ChatMsg(val id: Long, val role: String, val text: String)

@Composable
internal fun OryksaAvatarImage(url: String?, size: Int) = Avatar(url, size)

@Composable
private fun Avatar(url: String?, size: Int) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching { URL(url ?: OryksaAgent.DEFAULT_AVATAR).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0xFFEEEBFB))) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Text with **bold** parts (the server marks them, the app only draws them). */
internal fun oryksaBold(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    if (parts.size < 3) { append(text); return@buildAnnotatedString }
    parts.forEachIndexed { i, p ->
        if (p.isEmpty()) return@forEachIndexed
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
    }
}

/**
 * The ORYKSA chat panel: header with the photo and name of the AI, messages, suggestions, input and
 * (when the plan has voice) the microphone that opens the voice conversation.
 * [appContext]: where the customer is in your app right now, sent with each message.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OryksaChat(
    client: OryksaClient,
    modifier: Modifier = Modifier,
    lang: String = "en",
    theme: OryksaChatTheme = OryksaChatTheme(),
    onClose: (() -> Unit)? = null,
    appContext: (() -> OryksaAppContext?)? = null,
    voice: Boolean = true,
) {
    val lg = oryksaLang(lang)
    val tx = oryksaTexts.getValue(lg)
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<ChatMsg>() }
    var agent by remember { mutableStateOf<OryksaAgent?>(null) }
    var sug by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    LaunchedEffect(client, lg) {
        try {
            val a = client.agent()
            val hist = runCatching { client.messages() }.getOrDefault(emptyList())
            agent = a
            if (hist.isEmpty()) {
                OryksaAgent.pick(a.greeting, lg)?.takeIf { it.isNotBlank() }?.let { msgs.add(ChatMsg(0, "assistant", it)) }
                sug = (OryksaAgent.pick(a.suggestions, lg) ?: emptyList()).take(4)
            } else {
                hist.forEachIndexed { i, m -> msgs.add(ChatMsg(i.toLong(), if (m.role == "user") "user" else "assistant", m.content)) }
            }
        } catch (_: Exception) {
            agent = OryksaAgent("ORYKSA", OryksaAgent.DEFAULT_AVATAR)
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    fun send(q0: String) {
        val q = q0.trim()
        if (q.isEmpty() || busy) return
        busy = true; sug = emptyList(); input = ""
        val id = System.nanoTime()
        msgs.add(ChatMsg(id, "user", q)); msgs.add(ChatMsg(id + 1, "typing", "..."))
        scope.launch {
            val reply = runCatching { client.sendAndWait(q, appContext = appContext?.invoke()) }.getOrNull()
            msgs.removeAll { it.role == "typing" }
            msgs.add(ChatMsg(id + 2, "assistant", reply ?: tx.getValue("err")))
            busy = false
        }
    }

    val voiceAgent = agent
    if (voiceOpen && voiceAgent != null) {
        OryksaVoiceScreen(client = client, agent = voiceAgent, onClose = { voiceOpen = false }, lang = lg, theme = theme,
            appContext = appContext,
            onUserText = { sug = emptyList(); msgs.add(ChatMsg(System.nanoTime(), "user", it)) },
            onReply = { msgs.add(ChatMsg(System.nanoTime(), "assistant", it)) })
        return
    }

    Column(modifier.fillMaxSize().background(theme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(agent?.avatar, 46)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text((agent?.name ?: "").uppercase(), color = theme.ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                val sub = agent?.let { OryksaAgent.pick(it.subtitle, lg) ?: it.business } ?: ""
                if (sub.isNotBlank()) Text(sub, color = theme.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onClose != null) {
                Text("×", color = theme.muted, fontSize = 26.sp, modifier = Modifier.clickable { onClose() }.padding(horizontal = 10.dp))
            }
        }
        HorizontalDivider(color = Color(0xFFECEEF6))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs, key = { it.id }) { m ->
                val mine = m.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier.widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 6.dp, bottomEnd = if (mine) 6.dp else 16.dp))
                            .background(if (mine) theme.accent else theme.soft)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .alpha(if (m.role == "typing") 0.6f else 1f)
                    ) {
                        SelectionContainer { Text(oryksaBold(m.text), color = if (mine) Color.White else theme.ink, fontSize = 14.sp, lineHeight = 21.sp) }
                    }
                }
            }
        }
        if (sug.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sug.forEach { s ->
                    Surface(shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0xFFDCDCF5)), color = Color.White,
                        modifier = Modifier.clickable { send(s) }) {
                        Text(s, color = theme.accent, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFECEEF6))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input, onValueChange = { if (it.length <= 2000) input = it },
                placeholder = { Text(tx.getValue("ph"), fontSize = 14.sp) }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = theme.ink, unfocusedTextColor = theme.ink,
                ),
            )
            if (voice && agent?.voiceReplies == true && input.isBlank()) {
                Box(Modifier.fillMaxHeight().clickable(enabled = !busy) { voiceOpen = true }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) { MicGlyph(theme.accent) }
            }
            Box(Modifier.fillMaxHeight().background(theme.accent.copy(alpha = if (busy) 0.6f else 1f))
                .clickable(enabled = !busy) { send(input) }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(tx.getValue("send").uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            }
        }
        Text(
            buildAnnotatedString {
                append("POWERED BY ")
                withStyle(SpanStyle(color = theme.accent, fontWeight = FontWeight.ExtraBold)) { append("ORYKSA") }
            },
            color = Color(0xFF9CA3AF), fontSize = 10.5.sp, letterSpacing = 1.3.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
        )
    }
}

/** Floating "Talk to name" button with the photo of the AI. [onClick] usually calls [OryksaChatActivity.open]. */
@Composable
fun OryksaChatButton(
    client: OryksaClient,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lang: String = "en",
    theme: OryksaChatTheme = OryksaChatTheme(),
) {
    var agent by remember { mutableStateOf<OryksaAgent?>(null) }
    LaunchedEffect(client) { agent = runCatching { client.agent() }.getOrNull() }
    val tx = oryksaTexts.getValue(oryksaLang(lang))
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(theme.accent)
            .clickable { onClick() }.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.border(2.dp, Color.White, CircleShape)) { Avatar(agent?.avatar, 42) }
        Spacer(Modifier.size(10.dp))
        Text("${tx.getValue("talk")} ${agent?.name ?: "ORYKSA"}".uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp, letterSpacing = 1.sp)
    }
}

/**
 * Full screen chat for any Android app (Compose or Views). Register nothing: the SDK manifest declares it.
 * Open it with [OryksaChatActivity.open] after setting [OryksaChatActivity.client].
 */
class OryksaChatActivity : ComponentActivity() {
    companion object {
        /** Client used by the activity. Set it once, for example in your Application class. */
        @JvmStatic var client: OryksaClient? = null

        /** Where the customer is in your app when the chat opens (sent with each message). */
        @JvmStatic var appContext: OryksaAppContext? = null

        /** Opens the chat. */
        @JvmStatic @JvmOverloads
        fun open(context: Context, lang: String = "en") {
            context.startActivity(Intent(context, OryksaChatActivity::class.java).putExtra("lang", lang)
                .addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = client ?: run { finish(); return }
        val lang = intent.getStringExtra("lang") ?: "en"
        setContent { OryksaChat(client = c, lang = lang, onClose = { finish() }, appContext = { appContext }) }
    }
}
