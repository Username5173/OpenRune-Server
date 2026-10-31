package org.rsmod.content.interfaces.settings.scripts

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.testing.GameTestState

class MusicFilterDropdownTest {
    @Test
    fun GameTestState.`display music tracks stores the filter and rebuilds the list`() =
        runGameTest {
            val setting = Settings.getSetting(SettingUtils.MUSIC_TRACK_FILTER_SETTING_ID)
            client.clearOutgoing()

            SettingUtils.setDropdown(player, 1, setting)

            assertEquals(1, player.vars["varbit.settings_music_player_hide_tracks"])
            val rebuild =
                client.filterIsInstance<RunClientScript>().single { it.id == 9289 }
            assertEquals(
                listOf(
                    "component.music:inner",
                    "component.music:jukebox",
                    "component.music:scrollable",
                    "component.music:scrollbar",
                    "component.music:count",
                    "component.music:overlay",
                ).map { it.asRSCM(RSCMType.COMPONENT) },
                rebuild.values,
            )
        }
}
