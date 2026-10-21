package org.rsmod.content.bosses.madangel

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * `::madangelhp <hitpoints>` sets the nearest angel's health, so its enrage threshold and a kill can
 * be reached without grinding the bar down.
 */
class MadAngelCommand
@Inject
constructor(
    private val npcRepo: NpcRepository,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onCommand("madangelhp") {
            desc = "Set the nearest Mad Angel's hitpoints"
            invalidArgs = "Use as ::madangelhp <hitpoints> (ex: ::madangelhp 350)"
            cheat { setHitpoints() }
        }
    }

    private fun Cheat.setHitpoints() {
        val hitpoints = args.getOrNull(0)?.toIntOrNull()
        if (hitpoints == null) {
            player.mes("Use as ::madangelhp <hitpoints> (ex: ::madangelhp 350)")
            return
        }
        val npc =
            nearestAngel(player)
                ?: run {
                    player.mes("No Mad Angel nearby.")
                    return
                }
        npc.hitpoints = hitpoints
        player.mes("Mad Angel hitpoints set to $hitpoints (max ${npc.baseHitpointsLvl}).")
    }

    private fun nearestAngel(player: Player): Npc? =
        npcRepo
            .findAll(ZoneKey.from(player.coords), ZONE_RADIUS)
            .filter { it.isVisible && it.coords.level == player.coords.level }
            .filter { it.isType(NPC) || it.isVisType(NPC) }
            .filter { it.coords.chebyshevDistance(player.coords) <= SEARCH_RADIUS }
            .minByOrNull { it.coords.chebyshevDistance(player.coords) }

    private companion object {
        private const val NPC = "npc.mad_angel"
        private const val SEARCH_RADIUS = 15
        private const val ZONE_TILE_SIZE = 8
        private const val ZONE_RADIUS = (SEARCH_RADIUS + ZONE_TILE_SIZE - 1) / ZONE_TILE_SIZE
    }
}
