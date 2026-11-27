package com.oryksa.sdk.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fire TV 26/11/2026: the OK that opened the assistant panel was released on the panel's focused button and
// clicked it ("Ajuda" sent by itself). A TV button clicks only on its own key down + key up.
class TvPressTrackerTest {
    private val ok = 23L // KEYCODE_DPAD_CENTER

    @Test
    fun downThenUpOnTheSameButtonIsAClick() {
        val p = OryksaTvPressTracker()
        assertTrue(p.onDown(ok))
        assertTrue(p.onUp(ok))
    }

    @Test
    fun aReleaseWithoutItsPressIsNotAClick() {
        // The key went down on the button that opened the dialog; only its release reaches this button.
        assertFalse(OryksaTvPressTracker().onUp(ok))
    }

    @Test
    fun losingTheFocusForgetsThePress() {
        val p = OryksaTvPressTracker()
        p.onDown(ok)
        p.reset()
        assertFalse(p.onUp(ok))
    }

    @Test
    fun anotherKeysReleaseIsNotAClickAndOneClickPerPress() {
        val p = OryksaTvPressTracker()
        p.onDown(ok)
        assertFalse(p.onUp(66L)) // ENTER released, not the key that went down
        p.onDown(ok)
        p.onDown(ok) // key repeat
        assertTrue(p.onUp(ok))
        assertFalse(p.onUp(ok))
    }
}
