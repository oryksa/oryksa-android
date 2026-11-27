package com.oryksa.sdk.tv

import android.app.Dialog
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files

/**
 * The TV screens on the JVM (Robolectric), on the SIMULATED backend: the remote keys are sent as the system sends
 * them, to the window that has them (the newest dialog, else the screen). This proves the SDK logic; it does NOT
 * prove a real remote, the Fire TV keyboard or the ORYKSA server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir: File = Files.createTempDirectory("oryksa-tv-ui").toFile()
    private val tvs = mutableListOf<OryksaTv>()

    @After
    fun cleanUp() {
        tvs.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** A TV is driven by keys: the buttons take the focus as on the TV (in touch mode they would not). */
    @OptIn(ExperimentalComposeUiApi::class)
    private val keyboardMode = object : InputModeManager {
        override val inputMode: InputMode get() = InputMode.Keyboard
        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private fun setTvContent(content: @Composable () -> Unit) =
        rule.setContent { CompositionLocalProvider(LocalInputModeManager provides keyboardMode, content = content) }

    private fun config(locale: String) = OryksaTvConfig(
        locale = locale,
        presenterAvatar = "", // no picture to load from the network in a test
        voiceRev = "sim-1",
        texts = OryksaTvTexts(OryksaTvFakeBackend.sampleTextsFor(locale)),
        suggestions = if (locale == "pt-BR") listOf("Como funciona?", "O que você pode fazer?") else listOf("Como funciona?", "O que podes fazer?"),
        actions = listOf(OryksaTvAction("phone_voice", OryksaTvActionKind.PHONE_VOICE, if (locale == "pt-BR") "Falar pelo celular" else "Falar pelo telemóvel")),
        rules = OryksaTvRules(speakReplies = false),
    )

    private fun tv(fake: OryksaTvFakeBackend, locale: String = "pt-BR"): OryksaTv {
        val tv = OryksaTv(fake, locale, dir, FakeTvPlayer(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        tv.presenter.readingTimeMs = { 20_000L } // no voice: she shows the subtitle for 20 s, long enough to stop her
        tv.phoneVoice.pollWaitSec = 1
        runBlocking { tv.loadConfig() }
        tvs.add(tv)
        return tv
    }

    private fun speaking(tv: OryksaTv) = tv.presenter.state.value.phase == OryksaPresenterPhase.SPEAKING
    private fun idle(tv: OryksaTv) = tv.presenter.state.value.phase == OryksaPresenterPhase.IDLE

    private fun startSpeaking(tv: OryksaTv) {
        tv.scope.launch { tv.presenter.say("Olá! Eu sou a ORYKSA.") }
        rule.waitUntil(5_000) { speaking(tv) }
    }

    /** The window that gets the keys now: the newest dialog still showing, else the screen. */
    private fun keyWindow(): Dialog? = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }

    private fun press(keyCode: Int) {
        rule.runOnUiThread {
            val d = keyWindow()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val e = KeyEvent(action, keyCode)
                if (d != null) d.dispatchKeyEvent(e) else rule.activity.dispatchKeyEvent(e)
            }
        }
        rule.waitForIdle()
    }

    private fun focused(label: String): Boolean = runCatching {
        rule.onNode(hasText(label)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    }.getOrDefault(false)

    /** Every text on screen (all windows), for the checks of language and of technical words. */
    private fun screenTexts(): List<String> =
        rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun assertRightVariantAndNothingTechnical(locale: String, texts: List<String>) {
        val forbidden = mapOf(
            "pt-BR" to listOf("telemóvel", "telemovel", "ecrã", "comando", "carrega", "utilizador", "tua", "teu"),
            "pt-PT" to listOf("celular", "tela", "controle", "você", "aperte", "usuário"),
        ).getValue(locale)
        val problems = mutableListOf<String>()
        for (t in texts) {
            for (w in forbidden) {
                if (Regex("(^|[^\\p{L}])" + Regex.escape(w) + "($|[^\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(t)) problems.add("\"$w\" in: $t")
            }
            // A text key shown instead of its text, a placeholder not filled, an error or an address: technical.
            if (Regex("^[a-z]+(_[a-z]+)+$").matches(t.trim()) || t.contains('{') || t.contains("http") ||
                Regex("Exception|null|error", RegexOption.IGNORE_CASE).containsMatchIn(t)
            ) problems.add("technical: $t")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun okOnTheScreenStopsHerAndIsNotPassedToTheButton() {
        val tv = tv(OryksaTvFakeBackend(fixedConfig = config("pt-BR")))
        var clicks = 0
        setTvContent { OryksaPresenterHost(tv) { OryksaTvButton("Ouvir a apresentadora", onClick = { clicks++ }, autoFocus = true) } }
        rule.waitUntil(3_000) { focused("Ouvir a apresentadora") }
        startSpeaking(tv)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(2_000) { idle(tv) }
        assertEquals("OK while she speaks stops her and does not press the button", 0, clicks)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(2_000) { clicks == 1 }
        // Back while she speaks stops her and does not leave the screen. On the screen Back goes through its
        // BackHandler (proven on the Fire TV), switched on at the next frame: a person never presses within one frame
        // of her starting. (OK above is pressed without waiting: it must stop her at once.)
        startSpeaking(tv)
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(2_000) { idle(tv) }
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun insideTheAssistantPanelOkAndBackStopHerFirst() {
        val fake = OryksaTvFakeBackend(fixedConfig = config("pt-BR"))
        val tv = tv(fake)
        var closed = 0
        setTvContent { OryksaTvAssistantDialog(tv, onDismiss = { closed++ }) }
        rule.waitUntil(3_000) { focused("Como funciona?") }
        startSpeaking(tv)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(2_000) { idle(tv) }
        rule.waitForIdle()
        assertTrue("OK stopped her; the focused question was not sent: ${fake.sentMessages}", fake.sentMessages.isEmpty())
        startSpeaking(tv)
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(2_000) { idle(tv) }
        assertEquals("the first Back only stops her; the panel stays", 0, closed)
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(2_000) { closed == 1 }
        // Not speaking: OK sends the focused question.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(3_000) { fake.sentMessages.toList() == listOf("Como funciona?") }
    }

    @Test
    fun typingSendsOnlyWithSendAndBackClosesWithoutSending() {
        val fake = OryksaTvFakeBackend(fixedConfig = config("pt-BR"), chatReply = { "Resposta a: $it" })
        val tv = tv(fake)
        setTvContent { OryksaTvAssistantDialog(tv, onDismiss = {}) }
        rule.waitUntil(3_000) { focused("Como funciona?") }

        // Back on the typing dialog closes it and sends nothing.
        rule.onNodeWithText("Digitar uma mensagem").performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("Quero ajuda com a TV")
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(3_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertTrue(fake.sentMessages.isEmpty())

        // Typed again; while she speaks, OK on Send stops her and does not send; then OK on Send sends.
        rule.onNodeWithText("Digitar uma mensagem").performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("Quero ajuda com a TV")
        rule.onNodeWithText("Enviar").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitUntil(3_000) { focused("Enviar") }
        startSpeaking(tv)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(2_000) { idle(tv) }
        assertTrue(fake.sentMessages.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(3_000) { fake.sentMessages.toList() == listOf("Quero ajuda com a TV") }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Resposta a: Quero ajuda com a TV").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Você").assertExists()
        assertRightVariantAndNothingTechnical("pt-BR", screenTexts())
    }

    @Test
    fun thePhoneTextGoesToTheChatAndThePhoneLeavingIsShown_ptBR() {
        val fake = OryksaTvFakeBackend(fixedConfig = config("pt-BR"), chatReply = { "Resposta a: $it" })
        val tv = tv(fake)
        setTvContent { OryksaTvAssistantDialog(tv, onDismiss = {}) }
        rule.waitUntil(3_000) { focused("Como funciona?") }

        // The QR card in the panel (the phone button), in Portuguese of Brazil.
        rule.onNodeWithText("Falar pelo celular").performClick()
        rule.waitUntil(5_000) { tv.phoneVoice.state.value.phase == OryksaPhoneVoicePhase.WAITING }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Esperando o celular…").fetchSemanticsNodes().isNotEmpty() }
        assertRightVariantAndNothingTechnical("pt-BR", screenTexts())
        rule.onNodeWithText("Falar pelo celular").performClick() // the card closes; the pairing stays

        fake.phoneConnects()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Celular conectado").fetchSemanticsNodes().isNotEmpty() }
        fake.phoneSays("Quero ajuda")
        rule.waitUntil(5_000) { fake.sentMessages.toList() == listOf("Quero ajuda") }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Resposta a: Quero ajuda").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Quero ajuda").assertExists()

        fake.phoneLeaves()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("O celular desconectou").fetchSemanticsNodes().isNotEmpty() }
        assertRightVariantAndNothingTechnical("pt-BR", screenTexts())
    }

    @Test
    fun thePairingCardAndThePanelInPortugueseOfPortugal_ptPT() {
        val fake = OryksaTvFakeBackend(fixedConfig = config("pt-PT"))
        val tv = tv(fake, "pt-PT")
        setTvContent { OryksaTvAssistantDialog(tv, onDismiss = {}) }
        rule.waitUntil(3_000) { focused("Como funciona?") }
        rule.onNodeWithText("Falar pelo telemóvel").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithText("À espera do telemóvel…").fetchSemanticsNodes().isNotEmpty() }
        assertRightVariantAndNothingTechnical("pt-PT", screenTexts())
        rule.onNodeWithText("Falar pelo telemóvel").performClick()
        fake.phoneConnects()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Telemóvel ligado").fetchSemanticsNodes().isNotEmpty() }
        fake.phoneLeaves()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("O telemóvel desligou-se").fetchSemanticsNodes().isNotEmpty() }
        assertRightVariantAndNothingTechnical("pt-PT", screenTexts())
    }
}
