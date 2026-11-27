package com.oryksa.sdk.tv

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oryksa.sdk.OryksaAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Colours of the TV pieces. Pass your app's colours; the sizes and the focus ring stay big. */
@Immutable
data class OryksaTvTheme(
    val background: Color = Color(0xFF0E0B1A),
    val surface: Color = Color(0xFF221C38),
    val text: Color = Color.White,
    val muted: Color = Color(0xFFC4BFD8),
    val accent: Color = Color(0xFF6E4BFF),
    val focus: Color = Color.White,
    val subtitleBackground: Color = Color(0xD9000000),
)

/**
 * Sizes for a TV screen, given in pixels of a 1080p screen and scaled to the real one, so they look the same on
 * any TV whatever its density.
 */
class OryksaTvMetrics internal constructor(private val unitDp: Float, private val fontScale: Float, val widthDp: Float, val heightDp: Float) {
    /** [v] pixels of a 1080p screen. */
    fun px(v: Float): Dp = (v * unitDp).dp
    fun sp(v: Float): TextUnit = TextUnit(v * unitDp / fontScale, androidx.compose.ui.unit.TextUnitType.Sp)
    val text get() = sp(28f)
    val small get() = sp(24f)
    val title get() = sp(44f)
    val subtitle get() = sp(40f)
    val focusWidth get() = px(5f)
    /** 5 % margin on every side: TVs may cut the edges of the picture. */
    val safeH get() = (widthDp * 0.05f).dp
    val safeV get() = (heightDp * 0.05f).dp
}

@Composable
fun rememberOryksaTvMetrics(): OryksaTvMetrics {
    val c = LocalConfiguration.current
    val d = LocalDensity.current
    val w = c.screenWidthDp.toFloat()
    val h = c.screenHeightDp.toFloat()
    val base = minOf(h, w * 9f / 16f).takeIf { it > 0 } ?: 540f
    return remember(w, h, d.fontScale) { OryksaTvMetrics(base / 1080f, d.fontScale, w, h) }
}

/**
 * The OK key of a button: it clicks only when the same button saw the key go down AND up. Compose clicks on the
 * release; when OK opens a dialog, the release reached the dialog's focused button and clicked it too (Fire TV,
 * 26/11/2026: opening the panel sent "Ajuda" by itself).
 */
internal class OryksaTvPressTracker {
    private var down: Long? = null

    /** A key went down on this button. Returns true (the key is consumed). */
    fun onDown(keyCode: Long): Boolean {
        if (down == null) down = keyCode
        return true
    }

    /** A key went up on this button. Returns true when it is a click (the same key went down here). */
    fun onUp(keyCode: Long): Boolean {
        val click = down == keyCode
        down = null
        return click
    }

    /** The button lost the focus: a pending press is forgotten. */
    fun reset() {
        down = null
    }
}

/**
 * A button for the TV remote: OK presses it, the arrows move to it, and when it has the focus it grows, fills with
 * the accent colour and gets a thick ring.
 */
@Composable
fun OryksaTvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    theme: OryksaTvTheme = OryksaTvTheme(),
) {
    val m = rememberOryksaTvMetrics()
    var focused by remember { mutableStateOf(false) }
    val fr = focusRequester ?: remember { FocusRequester() }
    val press = remember { OryksaTvPressTracker() }
    // Lit only while its window has the focus: with a dialog open, the button behind it kept its ring and the screen
    // showed two (Fire TV, 26/11/2026). With a remote, one ring is the only way to know where you are.
    val lit = focused && LocalWindowInfo.current.isWindowFocused
    val scale by animateFloatAsState(if (lit) 1.08f else 1f, label = "oryksa-tv-button")
    // In a new dialog the first request can come before the window is ready and is lost; then the first key gave
    // the focus to another button (Fire TV, 26/11/2026). Ask again until this button has it.
    if (autoFocus) LaunchedEffect(Unit) {
        repeat(8) { i ->
            delay(if (i == 0) 30L else 120L)
            if (focused) return@LaunchedEffect
            runCatching { fr.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(m.px(16f))
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(fr)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) press.reset()
            }
            // OK is handled here (before clickable): a click needs this button's own key down and key up.
            .onPreviewKeyEvent { e ->
                if (!isOk(e.key)) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> press.onDown(e.key.keyCode)
                    KeyEventType.KeyUp -> { if (press.onUp(e.key.keyCode)) onClick(); true }
                    else -> true
                }
            }
            .clip(shape)
            .background(if (lit) theme.accent else theme.surface)
            .border(m.focusWidth, if (lit) theme.focus else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = m.px(32f), vertical = m.px(16f)),
    ) {
        Text(
            label, color = theme.text, fontSize = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Loads a picture from [url] (null while loading or when it fails). */
@Composable
internal fun rememberOryksaPicture(url: String?): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    return bmp
}

/** Round photo of the AI, with a glow while she speaks. */
@Composable
fun OryksaTvAvatar(url: String, size: Dp, speaking: Boolean = false, theme: OryksaTvTheme = OryksaTvTheme()) {
    val pic = rememberOryksaPicture(url)
    Box(
        Modifier.size(size).clip(CircleShape).background(if (speaking) theme.accent else theme.surface).padding(size * 0.05f),
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(theme.surface)) {
            pic?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** The remote keys that stop the presenter: OK. Back is handled with a BackHandler. */
private fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter || k == Key.ButtonA

/**
 * Your screen with the presenter over it. While she speaks, OK and Back on the remote stop her and are not passed
 * to your screen (with [remoteSkips]). Put your whole TV screen inside it.
 */
@Composable
fun OryksaPresenterHost(
    tv: OryksaTv,
    modifier: Modifier = Modifier,
    theme: OryksaTvTheme = OryksaTvTheme(),
    remoteSkips: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    OryksaPresenterKeys(tv, enabled = remoteSkips, backByKey = false, modifier = modifier.fillMaxSize()) {
        content()
        OryksaPresenterView(tv, theme = theme)
    }
}

/**
 * While she speaks, OK (and Back) on the remote stop her and are not passed on: the release and the repeats of that
 * key neither. Each window gets its own keys: a dialog does not pass them to the screen behind it, so in the
 * assistant panel OK pressed the focused button while she read the reply. The SDK's dialogs use it too.
 * [backByKey]: Back is taken from the key events (in a dialog, before the dialog closes itself); otherwise with a
 * BackHandler of the screen.
 */
@Composable
internal fun OryksaPresenterKeys(
    tv: OryksaTv,
    enabled: Boolean,
    backByKey: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state by tv.presenter.state.collectAsState()
    val busy = enabled && state.phase != OryksaPresenterPhase.IDLE
    var swallowUp by remember { mutableStateOf<Key?>(null) }
    if (!backByKey) BackHandler(enabled = busy) { tv.presenter.stop() }
    Box(
        modifier.onPreviewKeyEvent { e ->
            val k = e.key
            // Read now, not from the last recomposition: a key right after she starts must already stop her.
            val busyNow = enabled && tv.presenter.isBusy
            when {
                k == swallowUp && e.type == KeyEventType.KeyUp -> { swallowUp = null; true }
                // The key held down repeats: swallowed too (a new press of it is not a repeat and goes on below).
                k == swallowUp && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> true
                busyNow && e.type == KeyEventType.KeyDown && (isOk(k) || (backByKey && k == Key.Back)) -> {
                    swallowUp = k
                    tv.presenter.stop()
                    true
                }
                else -> false
            }
        },
        content = content,
    )
}

/**
 * The presenter on screen: her photo and name, and the subtitles in big letters at the bottom. It hides itself when
 * she is quiet unless [showWhenIdle]. Use [OryksaPresenterHost] to get the remote keys too.
 */
@Composable
fun OryksaPresenterView(tv: OryksaTv, modifier: Modifier = Modifier, theme: OryksaTvTheme = OryksaTvTheme(), showWhenIdle: Boolean = false) {
    val state by tv.presenter.state.collectAsState()
    val cfg by tv.config.collectAsState()
    LaunchedEffect(tv) { tv.configOrFallback() }
    if (state.phase == OryksaPresenterPhase.IDLE && !showWhenIdle) return
    val m = rememberOryksaTvMetrics()
    Box(modifier.fillMaxSize().padding(horizontal = m.safeH, vertical = m.safeV), contentAlignment = Alignment.BottomCenter) {
        Row(verticalAlignment = Alignment.Bottom) {
            OryksaTvAvatar(cfg?.presenterAvatar ?: OryksaTvConfig.DEFAULT_AVATAR, m.px(140f), state.phase == OryksaPresenterPhase.SPEAKING, theme)
            Spacer(Modifier.width(m.px(28f)))
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(m.px(20f))).background(theme.subtitleBackground)
                    .padding(horizontal = m.px(36f), vertical = m.px(24f)),
            ) {
                Text(cfg?.presenterName ?: "ORYKSA", color = theme.accent, fontSize = m.small, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(m.px(8f)))
                Text(state.line, color = theme.text, fontSize = m.subtitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The QR to talk on the phone: the code, the short address and where it stands (waiting, connected, listening,
 * expired). Starts `tv.phoneVoice` when it appears (unless [autoStart] is false).
 */
@Composable
fun OryksaPairingCard(tv: OryksaTv, modifier: Modifier = Modifier, autoStart: Boolean = true, theme: OryksaTvTheme = OryksaTvTheme()) {
    val st by tv.phoneVoice.state.collectAsState()
    val cfg by tv.config.collectAsState()
    LaunchedEffect(tv) {
        tv.configOrFallback()
        if (autoStart && !tv.phoneVoice.state.value.isActive) tv.phoneVoice.start()
    }
    val m = rememberOryksaTvMetrics()
    val t = cfg?.texts ?: OryksaTvTexts()
    val p = st.pairing
    val ended = st.phase in setOf(OryksaPhoneVoicePhase.EXPIRED, OryksaPhoneVoicePhase.CLOSED, OryksaPhoneVoicePhase.ERROR, OryksaPhoneVoicePhase.PHONE_LEFT)
    val status = when (st.phase) {
        OryksaPhoneVoicePhase.IDLE, OryksaPhoneVoicePhase.CREATING, OryksaPhoneVoicePhase.WAITING -> t.get("pair_waiting")
        OryksaPhoneVoicePhase.PAIRED -> t.get("pair_connected")
        OryksaPhoneVoicePhase.LISTENING -> t.get("listening")
        OryksaPhoneVoicePhase.PHONE_LEFT -> t.get("phone_left")
        OryksaPhoneVoicePhase.EXPIRED -> t.get("pair_expired")
        OryksaPhoneVoicePhase.CLOSED, OryksaPhoneVoicePhase.ERROR -> t.get("error")
    }
    val showQr = p != null && !ended && st.phase != OryksaPhoneVoicePhase.PAIRED && st.phase != OryksaPhoneVoicePhase.LISTENING
    val qr = rememberOryksaPicture(p?.qrPngUrl)
    Row(modifier.clip(RoundedCornerShape(m.px(24f))).background(theme.surface).padding(m.px(32f)), verticalAlignment = Alignment.CenterVertically) {
        if (showQr) {
            Box(Modifier.size(m.px(300f)).background(Color.White).padding(m.px(16f)), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                else Text(p!!.code, color = Color.Black, fontSize = m.title, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(m.px(36f)))
        }
        Column {
            Text(t.get("pair_title"), color = theme.text, fontSize = m.title, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(m.px(16f)))
            if (showQr) {
                Text(t.get("pair_step_scan"), color = theme.muted, fontSize = m.text)
                Spacer(Modifier.height(m.px(8f)))
                Text(t.get("pair_step_code", mapOf("url" to p!!.shortUrl, "code" to p.code)), color = theme.muted, fontSize = m.text)
                Spacer(Modifier.height(m.px(16f)))
                Text(p.code, color = theme.text, fontSize = m.sp(64f), fontWeight = FontWeight.ExtraBold, letterSpacing = m.sp(10f))
                Spacer(Modifier.height(m.px(16f)))
            }
            Text(status, color = theme.accent, fontSize = m.text, fontWeight = FontWeight.Bold)
            if (st.lastText.isNotEmpty() && !ended) {
                Spacer(Modifier.height(m.px(8f)))
                Text("“${st.lastText}”", color = theme.text, fontSize = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ended) {
                Spacer(Modifier.height(m.px(24f)))
                OryksaTvButton(t.get("retry"), onClick = { tv.phoneVoice.restart() }, autoFocus = true, theme = theme)
            }
        }
    }
}

private data class TvMsg(val mine: Boolean, val text: String)

/**
 * The assistant panel: a conversation with the AI driven only by the remote (arrows, OK, Back). The questions and
 * buttons come from the server; the person can also talk on the phone (QR) or type with the system keyboard.
 * Replies are read by the presenter when the server allows it. Back calls [onClose].
 */
@Composable
fun OryksaTvAssistant(
    tv: OryksaTv,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    appContext: (() -> OryksaAppContext?)? = null,
    sidePanel: Boolean = false,
    theme: OryksaTvTheme = OryksaTvTheme(),
) {
    val m = rememberOryksaTvMetrics()
    val cfgState by tv.config.collectAsState()
    var fallback by remember { mutableStateOf<OryksaTvConfig?>(null) }
    val cfg = cfgState ?: fallback
    val presenter by tv.presenter.state.collectAsState()
    val phone by tv.phoneVoice.state.collectAsState()
    val msgs = remember { mutableStateListOf<TvMsg>() }
    var busy by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var spoke by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val queue = remember { ArrayDeque<String>() }
    val typeFr = remember { FocusRequester() }

    fun close() {
        if (spoke && tv.presenter.isBusy) tv.presenter.stop()
        onClose()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (busy) { queue.addLast(t); return }
        msgs.add(TvMsg(true, t))
        busy = true
        scope.launch {
            var next: String? = t
            while (next != null) {
                val c = tv.configOrFallback()
                val speak = c.rules.speakReplies
                val reply = runCatching { tv.backend.chat(next, tv.locale.value, appContext?.invoke(), voice = speak) }.getOrNull()
                val text0 = reply?.reply?.trim().orEmpty()
                msgs.add(TvMsg(false, if (text0.isNotEmpty()) text0 else c.texts.get("error")))
                runCatching { list.animateScrollToItem(msgs.size - 1) }
                if (text0.isNotEmpty() && speak) {
                    spoke = true
                    val say = reply?.speech?.takeIf { it.isNotBlank() } ?: text0
                    tv.scope.launch { tv.presenter.say(say) }
                }
                next = queue.removeFirstOrNull()?.also { msgs.add(TvMsg(true, it)) }
            }
            busy = false
        }
    }

    LaunchedEffect(tv) { fallback = tv.configOrFallback() }
    LaunchedEffect(tv) { tv.phoneVoice.texts.collect { send(it) } }
    DisposableEffect(tv) { onDispose { if (spoke && tv.presenter.isBusy) tv.presenter.stop() } }
    BackHandler(enabled = !typing) { close() }

    val t = cfg?.texts ?: OryksaTvTexts()
    val panel = @Composable {
        Column(
            Modifier.fillMaxSize().background(theme.background)
                .padding(horizontal = if (sidePanel) m.px(40f) else m.safeH, vertical = if (sidePanel) m.px(40f) else m.safeV),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OryksaTvAvatar(cfg?.presenterAvatar ?: OryksaTvConfig.DEFAULT_AVATAR, m.px(96f), presenter.phase != OryksaPresenterPhase.IDLE, theme)
                Spacer(Modifier.width(m.px(24f)))
                Column {
                    Text(t.get("assistant_title"), color = theme.text, fontSize = m.title, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(m.px(6f)))
                    Text(t.get("assistant_hint"), color = theme.muted, fontSize = m.small)
                }
            }
            Spacer(Modifier.height(m.px(24f)))
            // The messages: with the focus, up and down scroll them; at the ends the arrows move the focus out.
            var listFocused by remember { mutableStateOf(false) }
            val listLit = listFocused && LocalWindowInfo.current.isWindowFocused
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(m.px(20f)))
                    .border(m.focusWidth, if (listLit) theme.focus else Color.Transparent, RoundedCornerShape(m.px(20f)))
                    .onFocusChanged { listFocused = it.isFocused }
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> if (list.canScrollBackward) { scope.launch { list.animateScrollBy(-300f) }; true } else false
                            Key.DirectionDown -> if (list.canScrollForward) { scope.launch { list.animateScrollBy(300f) }; true } else false
                            else -> false
                        }
                    }
                    .then(if (msgs.isNotEmpty()) Modifier.focusable() else Modifier)
                    .padding(m.px(12f)),
            ) {
                itemsIndexed(msgs) { _, msg ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (msg.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Column(
                            Modifier.padding(bottom = m.px(16f))
                                .widthIn(max = (m.widthDp * if (msg.mine) 0.5f else 0.7f).dp)
                                .clip(RoundedCornerShape(m.px(20f)))
                                .background(if (msg.mine) theme.accent.copy(alpha = 0.35f) else theme.surface)
                                .padding(horizontal = m.px(28f), vertical = m.px(18f)),
                        ) {
                            Text(if (msg.mine) t.get("you") else (cfg?.presenterName ?: "ORYKSA"), color = theme.muted, fontSize = m.small, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(m.px(6f)))
                            Text(msg.text, color = theme.text, fontSize = m.text)
                        }
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(m.px(12f)))
                Text(t.get("thinking"), color = theme.muted, fontSize = m.text)
            }
            // Where the phone stands while the QR card is closed, in the chosen language: when the phone leaves, the
            // person sees it here (the phone button then gives a new code).
            val phoneStatus = when (phone.phase) {
                OryksaPhoneVoicePhase.PAIRED -> t.get("pair_connected")
                OryksaPhoneVoicePhase.LISTENING -> t.get("listening")
                OryksaPhoneVoicePhase.PHONE_LEFT -> t.get("phone_left")
                else -> null
            }
            if (!showPairing && phoneStatus != null) {
                Spacer(Modifier.height(m.px(12f)))
                Text(phoneStatus, color = theme.accent, fontSize = m.text, fontWeight = FontWeight.Bold)
            }
            if (showPairing) {
                Spacer(Modifier.height(m.px(20f)))
                OryksaPairingCard(tv, theme = theme)
            }
            Spacer(Modifier.height(m.px(24f)))
            // One row that scrolls to the focused button: left and right always follow the order of the buttons.
            if (cfg != null) {
                val rules = cfg.rules
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = m.px(12f)),
                    horizontalArrangement = Arrangement.spacedBy(m.px(20f)),
                ) {
                    var first = true
                    fun auto(): Boolean = first.also { first = false }
                    cfg.suggestions.forEach { s -> OryksaTvButton(s, onClick = { send(s) }, autoFocus = auto(), theme = theme) }
                    cfg.actions.forEach { a ->
                        if (a.kind == OryksaTvActionKind.PHONE_VOICE && !rules.phoneVoice) return@forEach
                        OryksaTvButton(a.label, onClick = {
                            when (a.kind) {
                                OryksaTvActionKind.MESSAGE -> send(a.message ?: a.label)
                                OryksaTvActionKind.PHONE_VOICE -> showPairing = !showPairing
                                OryksaTvActionKind.CLOSE -> close()
                            }
                        }, autoFocus = auto(), theme = theme)
                    }
                    OryksaTvButton(t.get("type_message"), onClick = { typing = true }, autoFocus = auto(), focusRequester = typeFr, theme = theme)
                    OryksaTvButton(t.get("close"), onClick = { close() }, autoFocus = auto(), theme = theme)
                }
            }
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        if (sidePanel) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        val panelModifier = if (sidePanel) Modifier.fillMaxHeight().width(maxOf(m.widthDp * 0.42f, m.px(700f).value).dp) else Modifier.fillMaxSize()
        Box(panelModifier) { panel() }
    }

    if (typing) OryksaTvTypeDialog(tv, t, theme, typeFr, onDismiss = { typing = false }, onSend = { typing = false; send(it) })
}

/**
 * The system keyboard. Only the Send button sends: on the Fire TV, closing the keyboard with Back also "submits"
 * the field (proven on 26/11/2026), and Back must mean cancel. The keyboard's own action moves the focus to Send.
 */
@Composable
private fun OryksaTvTypeDialog(
    tv: OryksaTv,
    t: OryksaTvTexts,
    theme: OryksaTvTheme,
    returnFocusTo: FocusRequester,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val m = rememberOryksaTvMetrics()
    var value by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val sendFr = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OryksaTvDialog(onDismiss = onDismiss, returnFocusTo = returnFocusTo, fullScreen = true, tv = tv) {
        // Inside the dialog's own window: on the Fire TV focusing the field did not open the system keyboard
        // (26/11/2026), so a remote could not type. Focus the field, then ask for the keyboard.
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            repeat(6) { i ->
                delay(if (i == 0) 60L else 150L)
                if (!fieldFocused) runCatching { field.requestFocus() }
                if (fieldFocused) { keyboard?.show(); return@LaunchedEffect }
            }
        }
        Column(Modifier.width((m.widthDp * 0.6f).dp).clip(RoundedCornerShape(m.px(24f))).background(theme.surface).padding(m.px(32f))) {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(t.get("type_message"), fontSize = m.text, color = theme.muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = m.text, color = theme.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scope.launch { delay(50); runCatching { sendFr.requestFocus() } } }),
                colors = TextFieldDefaults.colors(focusedContainerColor = theme.background, unfocusedContainerColor = theme.background),
                modifier = Modifier.fillMaxWidth().focusRequester(field)
                    // OK on the field opens the keyboard again (after it was closed with Back).
                    .onPreviewKeyEvent { e ->
                        if (isOk(e.key) && e.type == KeyEventType.KeyUp) { keyboard?.show(); true } else isOk(e.key)
                    }
                    .onFocusChanged {
                    // The keyboard closed and the field lost the focus: give it to Send so the remote keeps working.
                    if (fieldFocused && !it.isFocused) scope.launch { delay(50); runCatching { sendFr.requestFocus() } }
                    fieldFocused = it.isFocused
                },
            )
            Spacer(Modifier.height(m.px(24f)))
            Row(horizontalArrangement = Arrangement.spacedBy(m.px(20f))) {
                OryksaTvButton(t.get("send"), onClick = { if (value.isNotBlank()) onSend(value.trim()) }, focusRequester = sendFr, theme = theme)
                OryksaTvButton(t.get("close"), onClick = onDismiss, theme = theme)
            }
        }
    }
}

/**
 * A dialog for the TV that gives the focus back when it closes. Compose does not put the focus back on the button
 * that opened a dialog (Fire TV, 26/11/2026: it landed on another button), and with a remote the focus is the only
 * way to know where you are. Pass the [returnFocusTo] of the button that opens it. With [tv], OK and Back stop the
 * presenter while she speaks, as on your screen (a dialog gets its keys apart from the screen behind it).
 */
@Composable
fun OryksaTvDialog(
    onDismiss: () -> Unit,
    returnFocusTo: FocusRequester? = null,
    fullScreen: Boolean = false,
    tv: OryksaTv? = null,
    content: @Composable () -> Unit,
) {
    DisposableEffect(returnFocusTo) {
        onDispose {
            val fr = returnFocusTo ?: return@onDispose
            // After the dialog window is gone and the screen has the window focus again.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { fr.requestFocus() } }, 150)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = if (fullScreen) DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false) else DialogProperties(),
    ) {
        if (tv != null) OryksaPresenterKeys(tv, enabled = true, backByKey = true) { content() } else content()
    }
}

/**
 * The assistant panel in a full-screen dialog over your screen. Back closes it and, with [returnFocusTo] (the
 * focus requester of the button that opened it), the focus goes back to that button.
 */
@Composable
fun OryksaTvAssistantDialog(
    tv: OryksaTv,
    onDismiss: () -> Unit,
    appContext: (() -> OryksaAppContext?)? = null,
    sidePanel: Boolean = false,
    theme: OryksaTvTheme = OryksaTvTheme(),
    returnFocusTo: FocusRequester? = null,
) {
    OryksaTvDialog(onDismiss = onDismiss, returnFocusTo = returnFocusTo, fullScreen = true, tv = tv) {
        OryksaTvAssistant(tv, onClose = onDismiss, appContext = appContext, sidePanel = sidePanel, theme = theme)
    }
}
