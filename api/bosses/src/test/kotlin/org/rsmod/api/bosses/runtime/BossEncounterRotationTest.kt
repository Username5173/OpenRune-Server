package org.rsmod.api.bosses.runtime

import dev.openrune.types.NpcServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.rsmod.api.bosses.dsl.boss
import org.rsmod.api.bosses.dsl.resetAnim
import org.rsmod.api.bosses.dsl.rotation
import org.rsmod.api.bosses.spec.Condition
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.map.CoordGrid

class BossEncounterRotationTest {
    private val spec =
        boss("npc.boss") {
            stats(attackRate = 6)
            val melee = ability("melee", resetAnim())
            val smite = ability("smite", resetAnim())
            val sweep = ability("sweep", resetAnim())
            val blast = ability("blast", resetAnim())
            val enrage = ability("enrage", resetAnim())
            phase("combat") {
                weightedSelectorRandom { +random(melee, weight = 1) }
                forceWhen(Condition.HpBelow(350.0 / 755.0, inclusive = true), enrage, once = true)
            }
            phase("enraged") {
                rotationSelector {
                    +then(smite)
                    +then(sweep)
                    +then(blast)
                }
            }
        }

    @Test
    fun `enrage is only selected once hp reaches the inclusive threshold`() {
        val encounter = encounter(hp = 351)
        assertNull(encounter.selectPriorityAbility(tick = 10, target = null))
        encounter.npc.hitpoints = 350
        assertEquals("enrage", encounter.selectPriorityAbility(tick = 10, target = null))
    }

    @Test
    fun `a once forced ability is not selected again after it ran`() {
        val encounter = encounter(hp = 350)
        assertEquals("enrage", encounter.selectPriorityAbility(tick = 10, target = null))
        encounter.startAttack("enrage", tick = 10)
        assertNull(encounter.selectPriorityAbility(tick = 10, target = null))
    }

    @Test
    fun `the enraged phase starts on its first entry and cycles in order`() {
        val encounter = encounter(hp = 350)
        encounter.transitionTo("enraged", tick = 0)
        val selector = checkNotNull(encounter.currentPhase?.selector)
        val picked = (0 until 4).map { encounter.pick(selector, tick = 4 + it * 6) }
        assertEquals(listOf("smite", "sweep", "blast", "smite"), picked)
    }

    @Test
    fun `randomStart picks a random entry but keeps cycling from it`() {
        val encounter = encounter(hp = 350)
        val selector =
            rotation {
                randomStart = true
                +then("a")
                +then("b")
                +then("c")
            }
        val picked = (0 until 6).map { checkNotNull(encounter.pick(selector, tick = 0)) }
        assertEquals(picked.subList(0, 3), picked.subList(3, 6))
        assertEquals(setOf("a", "b", "c"), picked.subList(0, 3).toSet())
        for (i in 0 until 2) {
            val next = picked[i + 1]
            val expected =
                when (picked[i]) {
                    "a" -> "b"
                    "b" -> "c"
                    else -> "a"
                }
            assertEquals(expected, next)
        }
    }

    private fun encounter(hp: Int): BossEncounter {
        val type = NpcServerType(id = 1, name = "Boss", size = 3, hitpoints = 755)
        val npc = Npc(type, CoordGrid(0, 1, 1, 0, 0)).apply { hitpoints = hp }
        return BossEncounter(npc, spec, MapClock())
    }
}
