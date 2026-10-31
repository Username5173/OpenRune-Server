package org.rsmod.api.music.plugin.scripts

import jakarta.inject.Inject
import org.rsmod.api.player.music.MusicPlayMode
import org.rsmod.api.player.music.MusicPlayer
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.enumVarp
import org.rsmod.api.script.onArea
import org.rsmod.api.script.onAreaExit
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.table.MusicModernRow
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

public class MusicAreaScript @Inject constructor(private val musicPlayer: MusicPlayer) :
    PluginScript() {
    private var Player.playMode by enumVarp<MusicPlayMode>("varp.musicplay")
    private val Player.usePreviousModeOnLogin by
        boolVarBit("varbit.use_previous_music_mode_on_login")

    override fun ScriptContext.startup() {
        val scriptAreas = loadScriptAreas()
        for (area in scriptAreas) {
            onArea(area) { playAreaMusic(area) }
            onAreaExit(area) { stopAreaMusic(area) }
        }
        onPlayerLogin { player.setDefaultMode() }
    }

    private fun ProtectedAccess.playAreaMusic(area: String) {
        musicPlayer.enterArea(player, area)
    }

    private fun ProtectedAccess.stopAreaMusic(area: String) {
        musicPlayer.exitArea(player, area)
    }

    private fun Player.setDefaultMode() {
        if (!usePreviousModeOnLogin) {
            playMode = MusicPlayMode.Area
        }
    }

    private fun loadScriptAreas(): List<String> {
        val areas = mutableListOf<String>()

        MusicModernRow.all().forEach {
            if (it.autoScript) {
                areas += "area.${it.area}"
            }
        }

        return areas
    }
}
