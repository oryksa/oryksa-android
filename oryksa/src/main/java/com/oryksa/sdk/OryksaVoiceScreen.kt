package com.oryksa.sdk

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val oryksaVoiceTexts = mapOf(
    "en" to mapOf("listening" to "I'm listening", "listeningSub" to "Speak to me. You can cut me off any time.", "hearing" to "Go on, I am listening.",
        "thinking" to "One moment...", "muted" to "Paused", "mutedSub" to "Tap the mic to talk again.",
        "micError" to "Microphone unavailable", "micErrorSub" to "Allow microphone access, or close other apps using it.",
        "noisy" to "Too much background noise", "noisySub" to "I cannot tell your voice from the noise. Move somewhere quieter, or type to me.",
        "notUnderstood" to "I could not understand", "notUnderstoodSub" to "Say it again, please.",
        "tapToSend" to "Tap the picture to send what you said.", "mute" to "Mute", "unmute" to "Unmute", "close" to "Close"),
    "pt" to mapOf("listening" to "Estou a ouvir", "listeningSub" to "Fala comigo. Podes interromper-me quando quiseres.", "hearing" to "Continua, estou a ouvir.",
        "thinking" to "Um momento...", "muted" to "Em pausa", "mutedSub" to "Toca no microfone para voltar a falar.",
        "micError" to "Microfone indisponível", "micErrorSub" to "Permite o acesso ao microfone, ou fecha outras apps que o estejam a usar.",
        "noisy" to "Demasiado barulho", "noisySub" to "Não consigo distinguir a tua voz do barulho. Vai para um sítio mais calmo, ou escreve-me.",
        "notUnderstood" to "Não percebi", "notUnderstoodSub" to "Diz outra vez, por favor.",
        "tapToSend" to "Toca na imagem para enviar o que disseste.", "mute" to "Silenciar", "unmute" to "Ativar som", "close" to "Fechar"),
    "br" to mapOf("listening" to "Estou ouvindo", "listeningSub" to "Fale comigo. Você pode me interromper quando quiser.", "hearing" to "Continue, estou ouvindo.",
        "thinking" to "Um momento...", "muted" to "Em pausa", "mutedSub" to "Toque no microfone para voltar a falar.",
        "micError" to "Microfone indisponível", "micErrorSub" to "Permita o acesso ao microfone, ou feche outros apps que estejam usando.",
        "noisy" to "Barulho demais", "noisySub" to "Não consigo separar sua voz do barulho. Vá para um lugar mais calmo, ou digite para mim.",
        "notUnderstood" to "Não entendi", "notUnderstoodSub" to "Fale de novo, por favor.",
        "tapToSend" to "Toque na imagem para enviar o que você disse.", "mute" to "Silenciar", "unmute" to "Ativar som", "close" to "Fechar"),
    "es" to mapOf("listening" to "Te escucho", "listeningSub" to "Háblame. Puedes interrumpirme cuando quieras.", "hearing" to "Sigue, te escucho.",
        "thinking" to "Un momento...", "muted" to "En pausa", "mutedSub" to "Toca el micrófono para volver a hablar.",
        "micError" to "Micrófono no disponible", "micErrorSub" to "Permite el acceso al micrófono, o cierra otras apps que lo estén usando.",
        "noisy" to "Demasiado ruido", "noisySub" to "No distingo tu voz del ruido. Ve a un sitio más tranquilo, o escríbeme.",
        "notUnderstood" to "No te entendí", "notUnderstoodSub" to "Dilo otra vez, por favor.",
        "tapToSend" to "Toca la imagen para enviar lo que dijiste.", "mute" to "Silenciar", "unmute" to "Activar sonido", "close" to "Cerrar"),
)

/**
 * Full-screen voice conversation, the same as the ORYKSA app: the photo of the AI with a halo,
 * "I'm listening" / her answer, Mute and Close. Asks for the microphone permission, starts listening
 * when it appears and closes the microphone when it goes.
 */
@Composable
fun OryksaVoiceScreen(
    controller: OryksaVoiceController,
    agent: OryksaAgent,
    onClose: () -> Unit,
    lang: String = "en",
    theme: OryksaChatTheme = OryksaChatTheme(),
) {
    val t = oryksaVoiceTexts[lang] ?: oryksaVoiceTexts.getValue("en")
    val phase by controller.phase.collectAsState()
    val reply by controller.lastReply.collectAsState()
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.start() }
    LaunchedEffect(controller) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }

    val (title, sub) = when (phase) {
        OryksaVoicePhase.STARTING, OryksaVoicePhase.LISTENING -> t.getValue("listening") to t.getValue("listeningSub")
        OryksaVoicePhase.HEARING -> t.getValue("listening") to t.getValue("hearing")
        // never the words the person said on screen: only "listening" in the interface language; her answer shows as text
        OryksaVoicePhase.THINKING -> t.getValue("listening") to t.getValue("thinking")
        OryksaVoicePhase.SPEAKING -> agent.name to reply
        OryksaVoicePhase.MUTED -> t.getValue("muted") to t.getValue("mutedSub")
        OryksaVoicePhase.MIC_ERROR -> t.getValue("micError") to t.getValue("micErrorSub")
        OryksaVoicePhase.NOISY -> t.getValue("noisy") to t.getValue("noisySub")
        OryksaVoicePhase.NOT_UNDERSTOOD -> t.getValue("notUnderstood") to t.getValue("notUnderstoodSub")
    }
    val active = phase == OryksaVoicePhase.HEARING || phase == OryksaVoicePhase.SPEAKING
    val pulse = rememberInfiniteTransition(label = "halo")
    val k by pulse.animateFloat(1f, 1.07f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "k")
    val red = Color(0xFFE05A52)

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(theme.background, theme.soft))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OryksaAvatarImage(agent.avatar, 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, color = theme.ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                agent.business?.takeIf { it.isNotBlank() }?.let { Text(it, color = theme.muted, fontSize = 11.sp, maxLines = 1) }
            }
            Text("×", color = theme.muted, fontSize = 26.sp, modifier = Modifier.clickable { controller.close(); onClose() }.padding(horizontal = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(250.dp).clickable { controller.sendNow() }, contentAlignment = Alignment.Center) {
            listOf(236 to 0.06f, 184 to 0.09f, 136 to 0.13f).forEach { (s, a) ->
                Box(Modifier.size(s.dp).scale(if (active) k else 1f).clip(CircleShape).background(theme.accent.copy(alpha = a)))
            }
            OryksaAvatarImage(agent.avatar, 124)
        }
        Text(title, color = theme.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 30.dp, end = 30.dp))
        Text(sub, color = theme.muted, fontSize = 13.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 30.dp, end = 30.dp))
        if (phase == OryksaVoicePhase.HEARING) {
            Text(t.getValue("tapToSend"), color = Color(0xFFAAB0CC), fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(46.dp)) {
            VoiceButton({ MicGlyph(theme.muted, crossed = phase == OryksaVoicePhase.MUTED) },
                if (phase == OryksaVoicePhase.MUTED) t.getValue("unmute") else t.getValue("mute"),
                theme.muted, theme.background, Color(0xFFECECF6)) { controller.toggleMute() }
            VoiceButton({ Text("×", color = red, fontSize = 26.sp) }, t.getValue("close"), red, Color(0xFFFDF0EF), Color(0xFFF3C0BE)) {
                controller.close(); onClose()
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            buildAnnotatedString {
                append("POWERED BY ")
                withStyle(SpanStyle(color = theme.accent, fontWeight = FontWeight.ExtraBold)) { append("ORYKSA") }
            },
            color = Color(0xFF9CA3AF), fontSize = 10.5.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(bottom = 22.dp),
        )
    }
}

@Composable
private fun VoiceButton(icon: @Composable () -> Unit, label: String, color: Color, bg: Color, line: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(bg).border(1.5.dp, line, CircleShape), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}

/** A microphone drawn with lines (no icon library needed); [crossed] = muted. */
@Composable
internal fun MicGlyph(color: Color, size: Int = 22, crossed: Boolean = false) {
    androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.06f),
            size = androidx.compose.ui.geometry.Size(w * 0.30f, h * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f), style = st)
        drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.5f), style = st)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.75f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.92f), sw,
            androidx.compose.ui.graphics.StrokeCap.Round)
        if (crossed) drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.1f),
            androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.9f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The voice screen with its own controller, kept while the screen is shown. */
@Composable
fun OryksaVoiceScreen(
    client: OryksaClient,
    agent: OryksaAgent,
    onClose: () -> Unit,
    lang: String = "en",
    theme: OryksaChatTheme = OryksaChatTheme(),
    appContext: (() -> OryksaAppContext?)? = null,
    onUserText: ((String) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val controller = remember(client) { OryksaVoiceController(ctx, client, appContext, onUserText, onReply) }
    OryksaVoiceScreen(controller, agent, onClose, lang, theme)
}
