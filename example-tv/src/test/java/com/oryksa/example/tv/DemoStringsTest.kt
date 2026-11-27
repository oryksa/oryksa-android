package com.oryksa.example.tv

import com.oryksa.sdk.tv.OryksaTvFakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The owner's rule (26/11/2026): the whole screen follows the chosen language, and the variants are never mixed
// ("telemóvel" is Portugal, "celular" is Brazil).
class DemoStringsTest {
    private fun scan(table: String, where: String, texts: Collection<String>, problems: MutableList<String>) {
        for (word in DEMO_FORBIDDEN_WORDS[table].orEmpty()) {
            val re = Regex("(^|[^\\p{L}])" + Regex.escape(word) + "($|[^\\p{L}])", RegexOption.IGNORE_CASE)
            texts.filter { re.containsMatchIn(it) }.forEach { problems.add("$where [$table] \"$word\" in: $it") }
        }
    }

    @Test
    fun noWordOfTheWrongVariantInTheDemoOrTheSimulatedBackend() {
        val problems = mutableListOf<String>()
        DEMO_TABLES.forEach { (table, texts) -> scan(table, "demo", texts.values, problems) }
        for (locale in listOf("pt-BR", "pt-PT", "es-MX", "es-ES")) {
            val table = OryksaTvFakeBackend.resolveLocale(locale)
            scan(table, "simulated backend", OryksaTvFakeBackend.sampleTextsFor(table).values, problems)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyTableHasEveryKeyAndEveryDemoLanguageHasATable() {
        val keys = DEMO_TABLES.getValue("en-US").keys
        DEMO_TABLES.forEach { (table, texts) -> assertEquals(table, keys, texts.keys) }
        DEMO_LOCALES.forEach { demoStrings(it) }
    }

    @Test
    fun theFirstLanguageIsTheDevicesWhenItIsOneOfTheEight() {
        assertEquals("pt-BR", demoInitialLocale("pt-BR"))
        assertEquals("pt-PT", demoInitialLocale("pt-AO"))
        assertEquals("es-MX", demoInitialLocale("es-AR"))
        assertEquals("es-ES", demoInitialLocale("es-ES"))
        assertEquals("en-US", demoInitialLocale("it-IT"))
        assertEquals("en-US", demoInitialLocale("ja"))
    }
}
