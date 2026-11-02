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
import net.rsprot.protocol.game.outgoing.sound.MidiSongWithSecondary
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
    fun `sailing ocean and event music areas resolve at their polygons`() {
        val modern = MusicModernRow.all().associateBy { "area.${it.area}".asRSCM() }
        val expected =
            mapOf(
                "ardent_ocean" to (listOf(7074, 7073) to CoordGrid(2981, 2784)),
                "unquiet_ocean" to (listOf(7075, 7076) to CoordGrid(2840, 2453)),
                "shrouded_ocean" to (listOf(7077, 7078) to CoordGrid(2070, 2471)),
                "northern_ocean" to (listOf(7079, 7080) to CoordGrid(2466, 4075)),
                "western_ocean" to (listOf(7081, 7082) to CoordGrid(2057, 3587)),
                "sunset_ocean" to (listOf(7083, 7084) to CoordGrid(1310, 2327)),
                "all_the_trimmings" to (listOf(4969) to CoordGrid(3218, 3397)),
            )
        for ((area, expectedRow) in expected) {
            val (trackRows, coords) = expectedRow
            val row = checkNotNull(modern["area.$area".asRSCM()]) { "No modern row for $area" }
            assertEquals(trackRows, row.tracks.map { it.rowId }, "Wrong tracks for $area")
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(coords, present)
            val rows = present.toShortArray().map { modern[it.toInt()] }.filterNotNull()
            assertEquals(listOf(area), rows.map { it.area }, "Wrong music at $coords")
        }
    }

    @Test
    fun `sailing ocean music does not bleed onto their islands`() {
        val oceanAreas =
            setOf(
                "ardent_ocean",
                "unquiet_ocean",
                "shrouded_ocean",
                "northern_ocean",
                "western_ocean",
                "sunset_ocean",
            ).map { "area.$it".asRSCM() }.toSet()
        val landPoints =
            listOf(
                CoordGrid(2850, 2950),
                CoordGrid(2400, 3800),
                CoordGrid(1400, 2900),
                CoordGrid(2650, 2650),
            )
        for (coords in landPoints) {
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(coords, present)
            val ocean = present.toShortArray().filter { it.toInt() in oceanAreas }
            assertTrue(ocean.isEmpty(), "Ocean music at land $coords: $ocean")
        }
    }

    @Test
    fun `every native unlock track belongs to a modern music area`() {
        val assigned = MusicModernRow.all().flatMap { it.tracks }.map { it.rowId }.toSet()
        val missing =
            MusicRow.all().filter {
                it.area.isNotEmpty() && it.hidden != true && it.automaticUnlock != true &&
                    it.rowId !in assigned
            }
        assertTrue(missing.isEmpty(), "Unassigned tracks: ${missing.map { it.displayname }}")
    }

    @Test
    fun `sailing oceans share the native music area group`() {
        val repo = MusicRepository(DefaultGameRandom(1)).apply { load() }
        val ardentGroup = checkNotNull(repo.getModernGroupId("area.ardent_ocean"))
        val sunsetGroup = checkNotNull(repo.getModernGroupId("area.sunset_ocean"))
        assertEquals(ardentGroup, sunsetGroup)
        assertEquals(12, repo.getModernGroup("area.ardent_ocean")?.size)
        assertTrue(ardentGroup != repo.getModernGroupId("area.lumbridge"))
    }

    @Test
    fun `crossing between sailing oceans keeps the current track`() {
        val f = Fixture()
        f.repo.load()
        f.music.enterArea(f.player, "area.ardent_ocean")
        val current = f.player.vars["varp.music_current_track"]
        f.client.messages.clear()

        f.music.enterArea(f.player, "area.unquiet_ocean")

        assertEquals(current, f.player.vars["varp.music_current_track"])
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
        assertTrue(f.music.hasUnlocked(f.player, MusicRow.getRow(7075)))
        assertTrue(f.music.hasUnlocked(f.player, MusicRow.getRow(7076)))

        f.music.playNext(f.player)
        val next = f.player.vars["varp.music_current_track"]
        assertTrue(next != null && next in setOf(7073, 7074, 7075, 7076), "Next track was $next")
    }

    @Test
    fun `ocean and land music areas never overlap at shared boundaries`() {
        val modern = MusicModernRow.all().associateBy { "area.${it.area}".asRSCM() }
        val points =
            mapOf(
                CoordGrid(2690, 3098) to "yanille_chain",
                CoordGrid(2976, 3089) to "mudskipper_point",
                CoordGrid(3041, 3148) to "port_sarim",
                CoordGrid(2592, 2840) to "corsair_cove",
                CoordGrid(2820, 3423) to "catherby",
                CoordGrid(2962, 3966) to "wilderness_music",
                CoordGrid(2640, 3704) to "rellekka",
                CoordGrid(2506, 3570) to "barbarian_outpost",
                CoordGrid(1561, 2772) to "tranquillity",
                CoordGrid(2558, 2825) to "corsair_cove",
                CoordGrid(3161, 2388) to "the_great_conch",
                CoordGrid(1164, 2760) to "waterlogged",
            )
        for ((coords, expected) in points) {
            val present = ShortArrayList()
            areaIndex.putAreasIncludingParents(coords, present)
            val rows = present.toShortArray().map { modern[it.toInt()] }.filterNotNull()
            assertEquals(listOf(expected), rows.map { it.area }, "Wrong music at $coords")
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
    fun `skip in shuffle mode moves to another unlocked track with the live fade`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 3)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.music.selectMode(f.player, MusicPlayMode.Random)
        f.client.messages.clear()

        f.music.skipTrack(f.player)

        assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
        val song = f.client.messages.filterIsInstance<MidiSongV2>().single()
        assertEquals(MusicPlayer.MUSIC_SKIP_FADE, song.fadeOutSpeed)
        assertEquals(MusicPlayer.MUSIC_SKIP_FADE, song.fadeInSpeed)
    }

    @Test
    fun `skip keeps the current track when no other track is unlocked`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 1)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.music.selectMode(f.player, MusicPlayMode.Random)
        f.client.messages.clear()

        f.music.skipTrack(f.player)

        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
    }

    @Test
    fun `skip does nothing outside shuffle mode or while music is muted`() {
        for (mode in MusicPlayMode.entries) {
            val f = Fixture()
            f.set("varp.musicmulti_1", 3)
            assertTrue(f.music.playSelected(f.player, f.first))
            f.music.selectMode(f.player, mode)
            if (mode == MusicPlayMode.Random) {
                f.set("varbit.option_music_desktop", 0)
            }
            f.client.messages.clear()

            f.music.skipTrack(f.player)

            assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"], "$mode")
            assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty(), "$mode")
        }
    }

    @Test
    fun `selecting area mode leaves the selected playlist unless playlist changes keep the mode`() {
        for (keepPlaylist in listOf(false, true)) {
            val f = Fixture()
            f.music.selectMode(f.player, MusicPlayMode.Random)
            f.set("varbit.music_current_playlist", 2)
            f.set("varbit.dont_update_music_on_playlist_change", if (keepPlaylist) 1 else 0)

            f.music.selectMode(f.player, MusicPlayMode.Area)

            val expected = if (keepPlaylist) 2 else 0
            assertEquals(0, f.player.vars["varp.musicplay"])
            assertEquals(expected, f.player.vars["varbit.music_current_playlist"])
        }
    }

    @Test
    fun `selecting area mode while idle starts the current area's music`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.set("varp.musicmulti_1", 1)
        f.set("varp.musicplay", MusicPlayMode.Manual.varValue)
        f.music.enterArea(f.player, "area.lumbridge")
        assertEquals(0, f.player.vars["varbit.music_curr_id"])

        f.music.selectMode(f.player, MusicPlayMode.Area)
        f.music.playNextIfIdle(f.player)

        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `idle start keeps a playing track and respects mute`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 1)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.music.selectMode(f.player, MusicPlayMode.Area)
        f.music.playNextIfIdle(f.player)
        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])

        val muted = Fixture()
        muted.area("area.lumbridge", muted.first)
        muted.set("varp.musicmulti_1", 1)
        muted.set("varp.musicplay", MusicPlayMode.Manual.varValue)
        muted.music.enterArea(muted.player, "area.lumbridge")
        muted.set("varbit.option_master_volume_desktop", 0)
        muted.music.selectMode(muted.player, MusicPlayMode.Area)
        muted.client.messages.clear()

        muted.music.playNextIfIdle(muted.player)

        assertEquals(0, muted.player.vars["varbit.music_curr_id"])
        assertTrue(muted.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
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
        f.music.playNextIfIdle(f.player)
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
    fun `classic areas resolve the cache default track of their map area`() {
        val f = Fixture()
        f.repo.load()

        assertEquals("Harmony", f.repo.getClassicArea("area.lumbridge")?.displayName)
        assertEquals("Nightfall", f.repo.getClassicArea("area.falador_south")?.displayName)
        assertEquals("Crest of a Wave", f.repo.getClassicArea("area.port_sarim")?.displayName)
        assertEquals(
            "Wilderness",
            f.repo.getClassicArea("area.wilderness_music")?.displayName,
        )
    }

    @Test
    fun `classic area entry plays and unlocks the single area track`() {
        val f = Fixture()
        f.repo.load()
        val harmony = checkNotNull(f.repo.getClassicArea("area.lumbridge"))
        f.set("varbit.music_area_mode", MusicAreaMode.Classic.varValue)

        f.music.enterArea(f.player, "area.lumbridge")

        assertEquals(harmony.id, f.player.vars["varbit.music_curr_id"])
        assertEquals(harmony.rowId, f.player.vars["varp.music_current_track"])
        assertTrue(f.music.hasUnlocked(f.player, harmony))
        assertEquals(harmony.midi.id, f.client.messages.filterIsInstance<MidiSongV2>().single().id)
    }

    @Test
    fun `default track setting starts the first area track without shuffling`() {
        val f = Fixture()
        f.modernArea("area.lumbridge", f.first, f.second)
        f.set("varbit.settings_music_default_track_on_area_entry", 1)

        f.music.enterArea(f.player, "area.lumbridge")

        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
        assertEquals(0, f.player.vars["varp.music_playlist"])
    }

    @Test
    fun `single track areas restart their song when looping is enabled`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.music.enterArea(f.player, "area.lumbridge")
        f.client.messages.clear()

        f.music.trackEnded(f.player)

        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
        assertEquals(f.first.midi.id, f.client.messages.filterIsInstance<MidiSongV2>().single().id)
    }

    @Test
    fun `single track areas end silently when looping is disabled`() {
        val f = Fixture()
        f.area("area.lumbridge", f.first)
        f.set("varbit.music_enableloop", 0)
        f.music.enterArea(f.player, "area.lumbridge")
        f.client.messages.clear()

        f.music.trackEnded(f.player)

        assertEquals(0, f.player.vars["varbit.music_curr_id"])
        assertEquals(
            listOf(MidiSongV2("midi.stop_music".asRSCM(), 0, 20, 0, 0)),
            f.client.messages.filterIsInstance<MidiSongV2>(),
        )
        f.client.messages.clear()
        f.music.playNext(f.player)
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
        assertTrue(f.client.messages.filterIsInstance<MidiSongV2>().isEmpty())
    }

    @Test
    fun `shuffle still advances to another track when looping is disabled`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 3)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.music.selectMode(f.player, MusicPlayMode.Random)
        f.set("varbit.music_enableloop", 0)
        f.client.messages.clear()

        f.music.trackEnded(f.player)
        f.music.playNext(f.player)

        assertEquals(f.second.id, f.player.vars["varbit.music_curr_id"])
        val songs =
            f.client.messages
                .filterIsInstance<MidiSongV2>()
                .filter { it.id != "midi.stop_music".asRSCM() }
        assertEquals(f.second.midi.id, songs.single().id)
    }

    @Test
    fun `single mode keeps looping while the loop setting is disabled`() {
        val f = Fixture()
        f.set("varp.musicmulti_1", 3)
        assertTrue(f.music.playSelected(f.player, f.first))
        f.set("varbit.music_enableloop", 0)
        f.client.messages.clear()

        f.music.trackEnded(f.player)

        assertEquals(f.first.id, f.player.vars["varbit.music_curr_id"])
        assertEquals(f.first.midi.id, f.client.messages.filterIsInstance<MidiSongV2>().single().id)
    }

    @Test
    fun `tracks with a secondary track send both midi ids`() {
        val f = Fixture()
        f.repo.load()
        val music = checkNotNull(f.repo.forRow(MusicRow.all().first { it.displayname == "Master of Puppets" }))
        val secondary = checkNotNull(music.secondaryMidi)
        f.set("varp.musicmulti_1", 0xFFFF)
        f.set(music.unlockVarp!!, music.unlockBitflag)

        assertTrue(f.music.playSelected(f.player, music))

        val song = f.client.messages.filterIsInstance<MidiSongWithSecondary>().single()
        assertEquals(music.midi.id, song.primaryId)
        assertEquals(secondary.id, song.secondaryId)
    }

    @Test
    fun `automatically unlocked tracks are playable without setting a varp bit`() {
        val f = Fixture()
        f.repo.load()
        val music = checkNotNull(f.repo.forRow(MusicRow.all().first { it.displayname == "Newbie Melody" }))

        assertTrue(music.automaticUnlock)
        assertTrue(f.music.hasUnlocked(f.player, music))
        assertTrue(f.music.playSelected(f.player, music))
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
            set("varbit.option_master_volume_desktop", 100)
            set("varbit.option_music_desktop", 100)
            set("varbit.music_enableloop", 1)
        }

        fun repoField(name: String, value: Any) {
            MusicRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repo, value)
        }

        fun set(key: String, value: Int) = VarPlayerIntMapSetter.set(player, key, value)

        fun area(key: String, track: Music) {
            modernArea(key, track)
            @Suppress("UNCHECKED_CAST")
            val classic = MusicRepository::class.java.getDeclaredField("classicAreas").apply { isAccessible = true }.get(repo) as Int2ObjectOpenHashMap<Music>
            classic[key.asRSCM()] = track
        }

        fun modernArea(key: String, vararg tracks: Music) {
            @Suppress("UNCHECKED_CAST")
            val modern = MusicRepository::class.java.getDeclaredField("modernAreas").apply { isAccessible = true }.get(repo) as Int2ObjectOpenHashMap<List<Music>>
            modern[key.asRSCM()] = tracks.toList()
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
            capture(3218, 3397, "varrock", "adventure", "garden", "medieval", "spirit", level = 2),
        )
        private val areaIndex = AreaIndex()

        private val extraAreaPoints =
            listOf(
                CoordGrid(2981, 2784),
                CoordGrid(2840, 2453),
                CoordGrid(2070, 2471),
                CoordGrid(2466, 4075),
                CoordGrid(2057, 3587),
                CoordGrid(1310, 2327),
                CoordGrid(3218, 3397),
                CoordGrid(2850, 2950),
                CoordGrid(2400, 3800),
                CoordGrid(1400, 2900),
                CoordGrid(2800, 3400),
                CoordGrid(2650, 2650),
                CoordGrid(2690, 3098),
                CoordGrid(2976, 3089),
                CoordGrid(3041, 3148),
                CoordGrid(2592, 2840),
                CoordGrid(2820, 3423),
                CoordGrid(2962, 3966),
                CoordGrid(2640, 3704),
                CoordGrid(2506, 3570),
                CoordGrid(1561, 2772),
                CoordGrid(2558, 2825),
                CoordGrid(3161, 2388),
                CoordGrid(1164, 2760),
            )

        @JvmStatic @BeforeAll fun cache() {
            val cache = ServerCacheManager.init(241)
            try {
                val squares = (captures.map { it.coords } + extraAreaPoints)
                    .flatMap { coords ->
                        (-1..1).flatMap { dx -> (-1..1).map { dz -> MapSquareKey.from(coords.translate(dx, dz)) } }
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
