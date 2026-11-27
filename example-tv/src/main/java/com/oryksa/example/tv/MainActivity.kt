package com.oryksa.example.tv

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.oryksa.sdk.OryksaAiResult
import com.oryksa.sdk.OryksaAppContext
import com.oryksa.sdk.OryksaReply
import com.oryksa.sdk.tv.OryksaPairing
import com.oryksa.sdk.tv.OryksaPairingCard
import com.oryksa.sdk.tv.OryksaPhoneEventType
import com.oryksa.sdk.tv.OryksaPhoneEventsPage
import com.oryksa.sdk.tv.OryksaPresenterHost
import com.oryksa.sdk.tv.OryksaTv
import com.oryksa.sdk.tv.OryksaTvActionKind
import com.oryksa.sdk.tv.OryksaTvAssistantDialog
import com.oryksa.sdk.tv.OryksaTvBackend
import com.oryksa.sdk.tv.OryksaTvButton
import com.oryksa.sdk.tv.OryksaTvClientBackend
import com.oryksa.sdk.tv.OryksaTvConfig
import com.oryksa.sdk.tv.OryksaTvDialog
import com.oryksa.sdk.tv.OryksaTvFakeBackend
import com.oryksa.sdk.tv.OryksaTvMic
import com.oryksa.sdk.tv.OryksaTvPlatform
import com.oryksa.sdk.tv.OryksaTvSpeech
import com.oryksa.sdk.tv.oryksaTvLocaleTag
import com.oryksa.sdk.tv.rememberOryksaTvMetrics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

private const val TAG = "ORYKSA_TV_DEMO"

/** A text on one log line, its quotes kept apart from the line's own. */
private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").replace('"', '\'').trim()

/** Writes to logcat what the server answered for each call, for the proof. Technical labels live here, never on screen. */
private class LoggingBackend(private val inner: OryksaTvBackend) : OryksaTvBackend {
    override val platform: String get() = inner.platform

    override suspend fun config(locale: String): OryksaTvConfig = inner.config(locale).also {
        Log.i(TAG, "config requested=$locale locale=${it.locale} from_server=${it.fromServer} voice_rev=${it.voiceRev} texts=${it.texts.values.size}")
    }

    override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?): OryksaTvSpeech? = try {
        inner.speak(text, locale, whisper, voice, cachedRev).also { s ->
            Log.i(TAG, if (s == null) "speak requested=$locale result=no_voice"
            // The text is what she says (sent to the voice), logged for the proof of what was spoken.
            else "speak requested=$locale voice=$voice cached_rev=${cachedRev ?: "-"} server_locale=${s.locale} voice_rev=${s.voiceRev} interactions_spent=${s.interactionsSpent} mp3_bytes=${s.bytes.size} chars=${text.length} text=\"${oneLine(text)}\"")
        }
    } catch (e: Exception) {
        Log.i(TAG, "speak requested=$locale error=$e"); throw e
    }

    override suspend fun chat(message: String, locale: String, appContext: OryksaAppContext?, voice: Boolean): OryksaReply = try {
        inner.chat(message, locale, appContext, voice).also {
            // The message is the demo's test text (a suggestion, the keyboard or the phone), logged for the proof.
            Log.i(TAG, "chat locale=$locale status=${it.status} message=\"$message\" reply_chars=${it.reply?.length ?: 0} speech_chars=${it.speech?.length ?: 0} speech=\"${oneLine(it.speech.orEmpty())}\"")
        }
    } catch (e: Exception) {
        Log.i(TAG, "chat locale=$locale error=$e"); throw e
    }

    override suspend fun ai(instructions: String?, input: String?, schema: JSONObject?): OryksaAiResult = inner.ai(instructions, input, schema)

    override suspend fun pair(locale: String): OryksaPairing = try {
        // The code is on the TV screen anyway; it is short-lived and serves once.
        inner.pair(locale).also { Log.i(TAG, "pair ok code=${it.code} expires_in=${it.expiresInSec}s") }
    } catch (e: Exception) {
        Log.i(TAG, "pair error=$e"); throw e
    }

    override suspend fun phoneEvents(pairId: String, after: Int, waitSec: Int): OryksaPhoneEventsPage =
        inner.phoneEvents(pairId, after, waitSec).also { page ->
            page.events.forEach { e ->
                Log.i(TAG, "phone event seq=${e.seq} type=${e.type}" +
                    if (e.type == OryksaPhoneEventType.TEXT) " lang=${e.lang} final=${e.isFinal} text=\"${e.text}\"" else "")
            }
        }

    override suspend fun unpair(pairId: String) = inner.unpair(pairId)
}

/** ORYKSA TV SDK demo for Android TV and Fire TV, a TEST app. Without a token it runs SIMULATED. */
class MainActivity : ComponentActivity() {
    private lateinit var tv: OryksaTv

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = BuildConfig.ORYKSA_TV_CHAT_TOKEN
        val simulated = token.isBlank()
        val platform = OryksaTvPlatform.detect(this)?.wire ?: "android"
        // The first language is the device's when it is one of the 8, otherwise English of the USA.
        val first = demoInitialLocale(oryksaTvLocaleTag(Locale.getDefault()))
        val backend: OryksaTvBackend = if (simulated) OryksaTvFakeBackend(platform)
        else OryksaTvClientBackend(OryksaTv.publicClient(this, token, "tvdemo" + Random.nextLong().toULong().toString(16)))
        tv = OryksaTv.create(this, LoggingBackend(backend), first)
        Log.i(TAG, "start mode=${if (simulated) "SIMULATED" else "REAL"} platform=$platform locale=$first app_id=$packageName")
        setContent { Demo(tv, simulated) }
    }

    override fun onDestroy() {
        tv.close()
        super.onDestroy()
    }
}

@Composable
private fun Demo(tv: OryksaTv, simulated: Boolean) {
    val m = rememberOryksaTvMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale by tv.locale.collectAsState()
    val cfg by tv.config.collectAsState()
    val phone by tv.phoneVoice.state.collectAsState()
    val mic = remember { OryksaTvMic.isAvailable(context) }
    var showAssistant by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    val assistantFr = remember { FocusRequester() }
    val pairFr = remember { FocusRequester() }
    LaunchedEffect(locale) { tv.configOrFallback() }
    // For the proof: when she starts and stops speaking (OK and Back on the remote must stop her).
    LaunchedEffect(Unit) {
        tv.presenter.state.map { it.phase }.distinctUntilChanged().collect { Log.i(TAG, "presenter phase=$it") }
    }

    val s = demoStrings(locale)
    // The phone button: the label the server gives (tv.actions phone_voice) in this language, else the demo's own.
    val serverPhone = cfg?.takeIf { it.locale == OryksaTvFakeBackend.resolveLocale(locale) }
        ?.actions?.firstOrNull { it.kind == OryksaTvActionKind.PHONE_VOICE }?.label
    val muted = Color(0xFFC4BFD8)

    OryksaPresenterHost(tv) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0E0B1A)).padding(horizontal = m.safeH, vertical = m.safeV)) {
            Text(s.getValue("title"), color = Color.White, fontSize = m.title, fontWeight = FontWeight.ExtraBold)
            if (simulated) {
                Spacer(Modifier.height(m.px(12f)))
                Text(
                    s.getValue("simulated"), color = Color.White, fontSize = m.small, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFFB45309)).padding(horizontal = m.px(20f), vertical = m.px(8f)),
                )
            }
            Spacer(Modifier.height(m.px(20f)))
            Text("${s.getValue("language")}: ${s.getValue("language_name")}", color = muted, fontSize = m.text)
            Text("${s.getValue("mic")}: ${s.getValue(if (mic) "mic_yes" else "mic_no")}", color = muted, fontSize = m.text)
            if (phone.lastText.isNotEmpty()) Text("${s.getValue("phone_last")}: “${phone.lastText}”", color = muted, fontSize = m.text)
            Spacer(Modifier.height(m.px(40f)))
            Row(horizontalArrangement = Arrangement.spacedBy(m.px(24f))) {
                OryksaTvButton(s.getValue("presenter"), onClick = { scope.launch { tv.presenter.say(s.getValue("welcome")) } }, autoFocus = true)
                OryksaTvButton(s.getValue("assistant"), onClick = { showAssistant = true }, focusRequester = assistantFr)
                OryksaTvButton(serverPhone ?: s.getValue("phone"), onClick = { showPair = true }, focusRequester = pairFr)
                OryksaTvButton(s.getValue("change_language"), onClick = {
                    // Everything at once: the screen, the server's texts and the voice.
                    val i = (DEMO_LOCALES.indexOf(locale) + 1).mod(DEMO_LOCALES.size)
                    tv.setLocale(DEMO_LOCALES[i])
                    Log.i(TAG, "locale=${DEMO_LOCALES[i]}")
                })
            }
        }
    }
    if (showAssistant) OryksaTvAssistantDialog(tv, onDismiss = { showAssistant = false }, returnFocusTo = assistantFr)
    if (showPair) OryksaTvDialog(onDismiss = { showPair = false }, returnFocusTo = pairFr, tv = tv) { OryksaPairingCard(tv) }
}
