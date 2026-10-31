package org.rsmod.api.player.music

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.map.area.MapAreaDecoder
import dev.openrune.map.area.MapAreaDefinitions
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.MidiType
import dev.openrune.types.varp.VarpLifetime
import dev.openrune.types.varp.VarpTransmitLevel
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.shorts.ShortArrayList
import net.rsprot.protocol.game.outgoing.sound.MidiJingle
import net.rsprot.protocol.game.outgoing.sound.MidiSongV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.music.Music
import org.rsmod.api.music.MusicRepository
import org.rsmod.api.player.midiJingle
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.DefaultGameRandom
import org.rsmod.api.table.MusicModernRow
import org.rsmod.api.table.MusicRow
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class MusicPlayerRegressionTest {
    @Test
    fun `battle music unlocks its native bit and returns to area music after victory`() {
        val f = Fixture()
        f.repo.load()
        val row = MusicRow.getRow("dbrow.music_dt2_sucellus")
        f.music.enterArea(f.player, "area.port_sarim")
        val ambient = f.player.vars["varp.music_current_track"]
        f.client.messages.clear()

        f.music.startEncounter(f.player, row)

        assertTrue(f.music.hasUnlocked(f.player, row))
        assertEquals(row.rowId, f.player.vars["varp.music_current_track"])
        val song = f.client.messages.filterIsInstance<MidiSongV2>().single()
        assertEquals(row.midi.id, song.id)
        assertEquals(100, song.fadeOutSpeed)
        assertEquals(100, song.fadeInSpeed)
        f.music.finishEncounter(f.player, row)
        assertEquals(ambient, f.player.vars["varp.music_current_track"])
        assertTrue(f.music.hasUnlocked(f.player, row))
    }

    @Test
    fun `battle unlocks preserve manual and shuffle choices`() {
        for (mode in listOf(MusicPlayMode.Manual, MusicPlayMode.Random)) {
            val f = Fixture()
            f.repo.load()
            f.music.enterArea(f.player, "area.port_sarim")
            f.set("varp.musicplay", mode.varValue)
            val chosen = f.player.vars["varp.music_current_track"]
            f.client.messages.clear()
            val row = MusicRow.getRow("dbrow.music_dt2_vardorvis")

            f.music.startEncounter(f.player, row)
            f.music.finishEncounter(f.player, row)

            assertTrue(f.music.hasUnlocked(f.player, row))
            assertEquals(chosen, f.player.vars["varp.music_current_track"])
            assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
        }
    }

    @Test
    fun `finishing an old fight cannot replace music after leaving the arena`() {
        val f = Fixture()
        f.repo.load()
        val row = MusicRow.getRow("dbrow.music_dt2_whisperer")
        f.music.startEncounter(f.player, row)
        f.music.enterArea(f.player, "area.port_sarim")
        val ambient = f.player.vars["varp.music_current_track"]
        f.client.messages.clear()

        f.music.finishEncounter(f.player, row)

        assertEquals(ambient, f.player.vars["varp.music_current_track"])
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
    }

    @Test
    fun `packed areas retain captured music groups without overlapping at their entry points`() {
        val modern = MusicModernRow.all().associateBy { "area.${it.area}".asRSCM() }
        for (capture in captures) {
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(capture.coords, present)
            val rows = present.toShortArray().map { modern[it.toInt()] }.filterNotNull()
            assertEquals(listOf(capture.area), rows.map { it.area }, "Wrong music at ${capture.coords}")
            val expected = capture.tracks.map { "dbrow.music_$it".asRSCM() }.toSet()
            assertEquals(expected, rows.single().tracks.map { it.rowId }.toSet(), "Wrong unlock group at ${capture.coords}")
            for (dx in -1..1) for (dz in -1..1) {
                present.clear()
                val nearby = capture.coords.translate(dx, dz)
                areaIndex.putAreasIncludingParents(nearby, present)
                assertTrue(present.toShortArray().count { modern.containsKey(it.toInt()) } <= 1, "Overlapping music areas at $nearby")
            }
        }
    }

    @Test
    fun `every packed modern music area references usable native unlock tracks`() {
        val rows = MusicModernRow.all()
        assertTrue(rows.size >= 584, "The imported regional music table must not lose coverage")
        assertEquals(rows.size, rows.map { it.area }.distinct().size, "Duplicate automatic area handlers")
        val repo = MusicRepository(DefaultGameRandom(1)).apply { load() }
        for (row in rows) {
            val area = "area.${row.area}"
            val areaId = area.asRSCM()
            assertTrue(areaId in 0..Short.MAX_VALUE.toInt(), "$area would sign-extend in area triggers")
            assertTrue(row.tracks.isNotEmpty(), "$area has no music")
            assertEquals(row.tracks.size, repo.getModernArea(area)?.size, "$area silently lost tracks")
            for (track in row.tracks) {
                assertEquals(2, track.variable.size, "$area / ${track.displayname} has no native unlock variable")
                val (varpIndex, bit) = track.variable
                assertTrue(varpIndex in 1..27, "$area / ${track.displayname}: invalid varp index $varpIndex")
                assertTrue(bit in 0..31, "$area / ${track.displayname}: invalid unlock bit $bit")
                assertTrue("varp.musicmulti_$varpIndex".asRSCM() >= 0)
                assertTrue(track.midi.id >= 0, "$area / ${track.displayname} has no MIDI")
            }
        }
    }

    @Test
    fun `garden courtyard boundaries meet seasonal music without overlapping`() {
        val modern = MusicModernRow.all().associateBy { "area.${it.area}".asRSCM() }
        for (x in 2887..2936) for (z in 5447..5496) {
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(CoordGrid(x, z), present)
            assertTrue(present.toShortArray().count { modern.containsKey(it.toInt()) } <= 1,
                "Overlapping garden music at $x,$z")
        }
        val boundaries = listOf(
            Triple(2902, 5472, "garden_of_winter"),
            Triple(2903, 5472, "sorceress_garden_courtyard"),
            Triple(2920, 5472, "sorceress_garden_courtyard"),
            Triple(2921, 5472, "garden_of_spring"),
            Triple(2910, 5462, "garden_of_autumn"),
            Triple(2910, 5463, "sorceress_garden_courtyard"),
            Triple(2910, 5480, "sorceress_garden_courtyard"),
            Triple(2910, 5481, "garden_of_summer"),
        )
        for ((x, z, expected) in boundaries) {
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(CoordGrid(x, z), present)
            assertEquals(listOf(expected), present.toShortArray().map { modern[it.toInt()]?.area }.filterNotNull())
        }
    }

    @Test
    fun `selecting the current song never cuts off a playing jingle`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 1)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.player.midiJingle(90)
        assertTrue(f.music.playSelected(f.player, f.first))
        assertEquals(1, f.client.messages.filterIsInstance<MidiSongV2>().size)
        assertEquals(1, f.client.messages.filterIsInstance<MidiJingle>().size)
        assertEquals(2, f.player.vars["varp.musicplay"])
    }

    @Test
    fun `shuffle with no unlocked songs sends no locked song`() {
        val f = Fixture()
        f.music.selectMode(f.player, MusicPlayMode.Random)
        assertEquals(1, f.player.vars["varp.musicplay"])
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `custom playlist never falls back to an unlocked track outside it`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 1)
        f.set("varbit.music_current_playlist", 1)
        f.set("varbit.music_playlist_1_track_1", f.second.playlistSlotValue)
        f.set("varbit.music_last_id", f.first.id)
        f.music.selectMode(f.player, MusicPlayMode.Random)
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `shuffle repeats the only unlocked member of the selected playlist`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 3)
        assertTrue(f.music.addToPlaylist(f.player, 1, f.second))
        f.set("varbit.music_current_playlist", 1)
        f.music.selectMode(f.player, MusicPlayMode.Random)
        assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
        f.music.skipTrack(f.player)
        assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
        assertEquals(1, f.client.messages.filterIsInstance<MidiSongV2>().size)
    }

    @Test
    fun `manual shuffle setting selects native shuffle mode and rejects locked selection`() {
        val f = Fixture()
        assertFalse(f.music.playSelected(f.player, f.second))
        f.set("varp.musicmulti_1", 1)
        f.set("varbit.use_shuffle_mode_on_manual_music_selection", 1)
        assertTrue(f.music.playSelected(f.player, f.first))
        assertEquals(1, f.player.vars["varp.musicplay"])
        f.music.selectMode(f.player, MusicPlayMode.Area)
        assertEquals(0, f.player.vars["varp.musicplay"])
    }

    @Test
    fun `entering a classic area unlocks its song while retaining manual playback`() {
        val f = Fixture()
        val area = "area.lumbridge"
        f.repoField("classicAreas", Int2ObjectOpenHashMap<Music>().apply { put(area.asRSCM(), f.second) })
        f.set("varp.musicmulti_1", 1)
        f.set("varbit.music_area_mode", MusicAreaMode.Classic.varValue)
        f.music.playSelected(f.player, f.first)
        f.music.enterArea(f.player, area)
        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
        assertTrue(f.music.hasUnlocked(f.player, f.second))
        assertEquals(1, f.client.messages.filterIsInstance<MidiSongV2>().size)
    }

    @Test
    fun `adjacent classic and modern areas crossfade directly without stopping between them`() {
        for (mode in MusicAreaMode.entries) {
            val f = Fixture()
            f.area("area.lumbridge", f.first)
            f.area("area.varrock", f.second)
            f.set("varbit.music_area_mode", mode.varValue)
            f.music.enterArea(f.player, "area.lumbridge")
            assertEquals(MidiSongV2(f.first.midi.id, 0, 0, 0, 0), f.client.messages.filterIsInstance<MidiSongV2>().single())
            f.client.messages.clear()

            f.music.exitArea(f.player, "area.lumbridge")
            assertEquals(1, f.player.vars["varp.music_pending_area_exit"])
            assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
            f.music.enterArea(f.player, "area.varrock")
            assertFalse("varp.music_pending_area_exit" in f.player.vars)
            assertFalse(f.music.syncAreaExit(f.player))

            assertEquals(listOf(MidiSongV2(f.second.midi.id, 0, 100, 0, 100)), f.client.messages.filterIsInstance<MidiSongV2>(), "$mode boundary")
            assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
            assertEquals(f.second.rowId, f.player.vars["varp.music_current_track"])
        }
    }

    @Test
    fun `crossing into an area with the same song preserves playback progress and jingles`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.area("area.varrock", f.first)
        f.music.enterArea(f.player, "area.lumbridge")
        f.client.messages.clear()
        f.player.midiJingle(90)
        f.set("varbit.music_curr_clocks", 25)

        f.music.exitArea(f.player, "area.lumbridge")
        f.music.enterArea(f.player, "area.varrock")
        assertFalse(f.music.syncAreaExit(f.player))

        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
        assertEquals(1, f.client.messages.filterIsInstance<MidiJingle>().size)
        assertEquals(25, f.player.vars["varbit.music_curr_clocks"])
        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `area departures do not interrupt manual or shuffle selections`() {
        for (mode in listOf(MusicPlayMode.Manual, MusicPlayMode.Random)) {
            val f = Fixture()
            f.area("area.lumbridge", f.first)
            f.area("area.varrock", f.second)
            f.music.enterArea(f.player, "area.lumbridge")
            f.music.selectMode(f.player, mode)
            f.client.messages.clear()

            f.music.exitArea(f.player, "area.lumbridge")
            assertFalse(f.music.syncAreaExit(f.player))
            f.music.enterArea(f.player, "area.varrock")

            assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty(), "$mode boundary")
            assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
            assertTrue(f.music.hasUnlocked(f.player, f.second))
        }
    }

    @Test
    fun `silent area departure fades out once and cannot restart the remembered area song`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.music.enterArea(f.player, "area.lumbridge")
        f.client.messages.clear()

        f.music.exitArea(f.player, "area.lumbridge")
        assertEquals(1, f.player.vars["varp.music_pending_area_exit"])
        assertTrue(f.player.attr.toPersistentMap().isEmpty(), "Pending departure must not survive logout")
        val pending = checkNotNull(ServerCacheManager.getVarp("varp.music_pending_area_exit".asRSCM()))
        assertEquals(VarpLifetime.Temp, pending.scope)
        assertEquals(VarpTransmitLevel.Never, pending.transmit)
        assertFalse(f.music.syncAreaExit(f.player))
        assertFalse("varp.music_pending_area_exit" in f.player.vars)
        assertFalse(f.music.syncAreaExit(f.player))
        f.music.enable(f.player)

        assertEquals(listOf(MidiSongV2("midi.stop_music".asRSCM(), 0, 20, 0, 0)), f.client.messages.filterIsInstance<MidiSongV2>())
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
        assertEquals(-1, f.player.vars["varp.music_current_track"])
    }

    @Test
    fun `selecting a song after leaving an area cancels its deferred stop`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.music.enterArea(f.player, "area.lumbridge")
        f.set("varp.musicmulti_1", 3)
        f.music.exitArea(f.player, "area.lumbridge")
        f.client.messages.clear()

        assertTrue(f.music.playSelected(f.player, f.second))
        assertFalse(f.music.syncAreaExit(f.player))

        assertEquals(listOf(MidiSongV2(f.second.midi.id, 0, 100, 0, 100)), f.client.messages.filterIsInstance<MidiSongV2>())
        assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `unmuting before the next music sync still resolves a silent departure`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.music.enterArea(f.player, "area.lumbridge")
        f.music.exitArea(f.player, "area.lumbridge")
        f.client.messages.clear()

        f.music.enable(f.player)
        assertFalse(f.music.syncAreaExit(f.player))

        assertEquals(listOf(MidiSongV2("midi.stop_music".asRSCM(), 0, 20, 0, 0)), f.client.messages.filterIsInstance<MidiSongV2>())
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
    }

    private class Fixture {
        val client = RecordingClient()
        val player = Player().apply { this.client = this@Fixture.client }
        val first = track(1, 0)
        val second = track(2, 1)
        private val random = DefaultGameRandom(1)
        val repo = MusicRepository(random)
        val music = MusicPlayer(random, repo)

        init {
            val rows = Int2ObjectOpenHashMap<Music>().apply { put(first.id, first); put(second.id, second) }
            repoField("musicRows", rows)
            repoField("musicIds", rows)
            repoField("classicAreas", Int2ObjectOpenHashMap<Music>())
            repoField("modernAreas", Int2ObjectOpenHashMap<List<Music>>())
        }

        fun repoField(name: String, value: Any) {
            MusicRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repo, value)
        }

        fun set(key: String, value: Int) = VarPlayerIntMapSetter.set(player, key, value)

        fun area(key: String, track: Music) {
            @Suppress("UNCHECKED_CAST")
            val classic = MusicRepository::class.java.getDeclaredField("classicAreas").apply { isAccessible = true }.get(repo) as Int2ObjectOpenHashMap<Music>

            @Suppress("UNCHECKED_CAST")
            val modern = MusicRepository::class.java.getDeclaredField("modernAreas").apply { isAccessible = true }.get(repo) as Int2ObjectOpenHashMap<List<Music>>
            classic[key.asRSCM()] = track
            modern[key.asRSCM()] = listOf(track)
        }

        private fun track(id: Int, bit: Int) = Music(id, id, "Track $id", "in an area.", 100, MidiType(id), "varp.musicmulti_1", 1, bit, false, null)
    }

    private class RecordingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private data class Capture(val coords: CoordGrid, val area: String, val tracks: List<String>)
        private fun capture(x: Int, z: Int, area: String, vararg tracks: String, level: Int = 0) =
            Capture(CoordGrid(x, z, level), area, tracks.toList())

        private val captures = listOf(
            capture(3273, 3426, "varrock", "adventure", "garden", "medieval", "spirit"),
            capture(3253, 3448, "varrock_museum", "looking_back"),
            capture(3269, 3329, "varrock_outskirts", "doorways", "expanse", "greatness", "still_night"),
            capture(3003, 3228, "rimmington", "attention", "emperor"),
            capture(3069, 3278, "falador_south", "long_way_home", "miles_away", "nightfall"),
            capture(2955, 3479, "goblin_village", "goblin_village"),
            capture(3016, 3514, "knightmare", "knightmare"),
            capture(2578, 2837, "corsair_cove", "ontheshore", level = 1),
            capture(3362, 5138, "deaths_office", "restinpeace", level = 2),
            capture(2940, 3248, "the_maze", "melzars_maze"),
            capture(3060, 3263, "port_sarim", "sea_shanty2"),
            capture(3063, 3328, "falador_north", "lightness", "scape_soft"),
            capture(2912, 5474, "sorceress_garden_courtyard", "sorceresss_garden"),
            capture(2911, 5470, "sorceress_garden_courtyard", "sorceresss_garden"),
            capture(2894, 5475, "garden_of_winter", "garden_of_winter"),
            capture(2927, 5470, "garden_of_spring", "garden_of_spring"),
            capture(2908, 5454, "garden_of_autumn", "garden_of_autumn"),
            capture(2914, 5487, "garden_of_summer", "garden_of_summer"),
        )
        private val areaIndex = AreaIndex()

        @JvmStatic @BeforeAll fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                val squares = captures.flatMap { capture ->
                    (-1..1).flatMap { dx -> (-1..1).map { dz -> MapSquareKey.from(capture.coords.translate(dx, dz)) } }
                }.distinct()
                for (square in squares) {
                    val data = requireNotNull(cache.data(MAPS, square.id, 7)) { "Missing packed area map $square" }
                    MapAreaDefinitions.putAreas(areaIndex, square, MapAreaDecoder.decode(InlineByteBuf(data)))
                }
            } finally {
                cache.close()
            }
        }
    }
}
