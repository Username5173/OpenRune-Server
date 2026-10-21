package org.rsmod.content.bosses.madangel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerfectSmitePrayerTest {
    @Test
    fun `a prayer held since before the cast is only reduced`() {
        assertFalse(MadAngelFight.perfectSmitePrayer(true, true, activatedAt = -1, elapsed = 6))
    }

    @Test
    fun `switching the prayer on for the strike tick is perfect`() {
        assertTrue(MadAngelFight.perfectSmitePrayer(true, false, activatedAt = 6, elapsed = 6))
    }

    @Test
    fun `switching the prayer on a tick early is perfect`() {
        assertTrue(MadAngelFight.perfectSmitePrayer(true, true, activatedAt = 5, elapsed = 6))
    }

    @Test
    fun `switching the prayer on two ticks early only reduces`() {
        assertFalse(MadAngelFight.perfectSmitePrayer(true, true, activatedAt = 4, elapsed = 6))
    }

    @Test
    fun `flicking the prayer off for the strike tick is perfect`() {
        assertTrue(MadAngelFight.perfectSmitePrayer(false, true, activatedAt = 5, elapsed = 6))
    }

    @Test
    fun `no prayer takes the full hit`() {
        assertFalse(MadAngelFight.perfectSmitePrayer(false, false, activatedAt = -1, elapsed = 6))
    }

    @Test
    fun `a held activation does not cover a later enraged strike`() {
        assertFalse(MadAngelFight.perfectSmitePrayer(true, true, activatedAt = 5, elapsed = 10))
    }

    @Test
    fun `a fresh activation covers a later enraged strike`() {
        assertTrue(MadAngelFight.perfectSmitePrayer(true, true, activatedAt = 9, elapsed = 10))
    }
}
