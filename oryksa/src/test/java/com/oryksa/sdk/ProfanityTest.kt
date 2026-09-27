package com.oryksa.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The swear word list of the server (profanity_br.json is a copy of GET /widget/profanity.json?lang=br). */
class ProfanityTest {
    private val spec = JSONObject(javaClass.classLoader!!.getResourceAsStream("profanity_br.json")!!.readBytes().toString(Charsets.UTF_8))

    init { OryksaProfanity.use("br", spec.getString("pattern"), spec.getString("flags")) }

    @Test fun swearWordsShowAsAsterisks() {
        assertEquals("filha da ****", OryksaProfanity.mask("filha da puta", "br"))
        assertEquals("vai tomar ****", OryksaProfanity.mask("vai tomar no cu", "br")) // the list masks the whole expression "no cu"
        assertEquals("Que ***** é essa?", OryksaProfanity.mask("Que PORRA é essa?", "br"))
    }

    @Test fun normalWordsStay() {
        for (t in listOf("Quero uma vela de lavanda.", "O curso de computador custa quanto?", "Cuidado com a entrega", "Olá, boa tarde!")) {
            assertEquals(t, OryksaProfanity.mask(t, "br"))
        }
    }

    @Test fun withoutTheListNothingChanges() = assertEquals("filha da puta", OryksaProfanity.mask("filha da puta", "es"))
}
