package org.rsmod.api.music

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import jakarta.inject.Inject
import org.rsmod.api.random.GameRandom
import org.rsmod.api.table.MusicAreaGroupRow
import org.rsmod.api.table.MusicModernRow
import org.rsmod.api.table.MusicRow

public class MusicRepository @Inject constructor(private val random: GameRandom) {
    private lateinit var musicRows: Int2ObjectMap<Music>
    private lateinit var musicIds: Int2ObjectMap<Music>
    private lateinit var jukebox: List<Music>

    private lateinit var modernAreas: Int2ObjectMap<List<Music>>
    private var modernGroups: Int2ObjectMap<List<Music>> = Int2ObjectOpenHashMap()
    private var areaGroups: Int2IntMap = Int2IntOpenHashMap()
    private lateinit var classicAreas: Int2ObjectMap<Music>

    public fun forRow(row: MusicRow): Music? = musicRows[row.rowId]

    public fun forRowId(rowId: Int): Music? = musicRows[rowId]

    public fun forId(id: Int): Music? = musicIds[id]

    /**
     * The tracks in the order the music tab lists them. The client builds the jukebox from
     * `db_find(dbcol.music:hidden = 0)` and the comsub it sends when a track is clicked is the
     * index into that result, so this is every non-hidden row by ascending row id.
     */
    public fun jukeboxTracks(): List<Music> = jukebox

    public fun jukeboxTrack(index: Int): Music? = jukebox.getOrNull(index)

    public fun getModernArea(area: String): List<Music>? {
        return modernAreas[area.asRSCM(RSCMType.AREA)]
    }

    /**
     * Tracks that share a native `music_area_group` row, such as the six Sailing oceans. Moving
     * between areas in the same group keeps the current playlist instead of restarting it.
     */
    public fun getModernGroup(area: String): List<Music>? {
        val group = getModernGroupId(area) ?: return null
        return modernGroups[group]
    }

    public fun getModernGroupId(area: String): Int? {
        val areaId = area.asRSCM(RSCMType.AREA)
        if (!areaGroups.containsKey(areaId)) {
            return null
        }
        return areaGroups.get(areaId)
    }

    public fun getClassicArea(area: String): Music? {
        return classicAreas[area.asRSCM(RSCMType.AREA)]
    }

    public fun getAll(): Collection<Music> {
        return musicRows.values
    }

    public fun load() {
        val unlockVarps = unlockVarps()

        val musicRows = loadMusicRows(unlockVarps)
        this.musicRows = Int2ObjectOpenHashMap(musicRows)

        val musicSlots = mapMusicById(musicRows)
        this.musicIds = Int2ObjectOpenHashMap(musicSlots)

        this.jukebox = musicRows.values.filterNot(Music::hidden).sortedBy(Music::rowId)

        val modernAreas = loadModernAreas(musicRows)
        this.modernAreas = Int2ObjectOpenHashMap(modernAreas)
        loadModernGroups(modernAreas)

        val classicAreas = deriveClassicAreas(modernAreas, musicRows)
        this.classicAreas = Int2ObjectOpenHashMap(classicAreas)
    }

    private fun loadMusicRows(unlockVarps: List<String>): Map<Int, Music> {
        val rows = MusicRow.all()
        val mapped = HashMap<Int, Music>(rows.size)
        var currId = 1
        for (row in rows) {
            val variable = row.variable
            var unlockVarp: String? = null
            var unlockVarpIndex = 0
            var unlockBitpos = -1
            if (variable.isNotEmpty()) {
                unlockVarp = unlockVarps.getOrNull(variable[0] - 1)
                unlockVarpIndex = variable[0]
                unlockBitpos = variable[1]
            }
            mapped[row.rowId] =
                Music(
                    id = currId++,
                    rowId = row.rowId,
                    displayName = row.displayname,
                    unlockHint = row.unlockhint,
                    duration = row.duration,
                    midi = row.midi,
                    unlockVarp = unlockVarp,
                    unlockVarpIndex = unlockVarpIndex,
                    unlockBitpos = unlockBitpos,
                    hidden = row.hidden ?: false,
                    secondaryMidi = null,
                    automaticUnlock = row.automaticUnlock ?: false,
                    classicAreas = row.area,
                    classicAreaDefault = row.areaDefault,
                )
        }
        for (row in rows) {
            val music = mapped[row.rowId] ?: continue
            val secondaryRow = row.secondaryTrack ?: continue
            val secondary = mapped[secondaryRow.rowId] ?: continue
            // Self-referencing secondaries belong to the same midi; playing it twice overlaps.
            if (secondary.midi.id == music.midi.id) {
                continue
            }
            mapped[row.rowId] = music.copy(secondaryMidi = secondary.midi)
        }
        return mapped
    }

    private fun mapMusicById(musicRows: Map<Int, Music>): Map<Int, Music> {
        return musicRows.values.associateBy(Music::id)
    }

    private fun loadModernAreas(musicRows: Map<Int, Music>): Map<Int, List<Music>> {
        val grouped = mutableMapOf<Int, MutableList<Music>>()

        MusicModernRow.all().forEach {
            val area = "area.${it.area}".asRSCM(RSCMType.AREA)
            val trackRows = it.tracks
            val musicList = ArrayList<Music>(trackRows.size)
            for (trackRow in trackRows) {
                val music = musicRows[trackRow.rowId] ?: continue
                musicList += music
            }
            val mappedList = grouped.computeIfAbsent(area) { mutableListOf() }
            mappedList += musicList
        }

        return grouped
    }

    private fun loadModernGroups(modernAreas: Map<Int, List<Music>>) {
        val groups = MusicAreaGroupRow.all()
        val areaGroups = Int2IntOpenHashMap(modernAreas.size)
        val grouped = mutableMapOf<Int, MutableList<Music>>()
        for ((areaId, tracks) in modernAreas) {
            val nativeAreas = tracks.flatMapTo(HashSet()) { it.classicAreas }
            val group = groups.firstOrNull { row -> row.area.any(nativeAreas::contains) }
            val groupId = group?.rowId ?: areaId
            areaGroups[areaId] = groupId
            val groupTracks = grouped.getOrPut(groupId) { mutableListOf() }
            for (track in tracks) {
                if (groupTracks.none { it.id == track.id }) {
                    groupTracks += track
                }
            }
        }
        this.areaGroups = areaGroups
        this.modernGroups = Int2ObjectOpenHashMap(grouped)
    }

    /**
     * Classic mode plays a single track per music area. The cache stores the classic area ids and
     * the default track of each one on `dbtable.music`; pick the area's own default track first,
     * then the default track of any classic area its tracks belong to, and fall back to the first
     * track for areas that only exist in modern mode.
     */
    private fun deriveClassicAreas(
        modernAreas: Map<Int, List<Music>>,
        musicRows: Map<Int, Music>,
    ): Map<Int, Music> {
        val classicDefaults =
            musicRows.values.mapNotNull { music ->
                music.classicAreaDefault?.let { area -> area to music }
            }.toMap()

        val derived = HashMap<Int, Music>(modernAreas.size)
        for ((area, tracks) in modernAreas) {
            val track =
                tracks.firstOrNull { it.classicAreaDefault != null }
                    ?: tracks
                        .asSequence()
                        .flatMap { it.classicAreas.asSequence() }
                        .mapNotNull(classicDefaults::get)
                        .firstOrNull()
                    ?: tracks.firstOrNull()
            if (track != null) {
                derived[area] = track
            }
        }
        return derived
    }

    private fun unlockVarps(): List<String> =
        listOf(
            "varp.musicmulti_1",
            "varp.musicmulti_2",
            "varp.musicmulti_3",
            "varp.musicmulti_4",
            "varp.musicmulti_5",
            "varp.musicmulti_6",
            "varp.musicmulti_7",
            "varp.musicmulti_8",
            "varp.musicmulti_9",
            "varp.musicmulti_10",
            "varp.musicmulti_11",
            "varp.musicmulti_12",
            "varp.musicmulti_13",
            "varp.musicmulti_14",
            "varp.musicmulti_15",
            "varp.musicmulti_16",
            "varp.musicmulti_17",
            "varp.musicmulti_18",
            "varp.musicmulti_19",
            "varp.musicmulti_20",
            "varp.musicmulti_21",
            "varp.musicmulti_22",
            "varp.musicmulti_23",
            "varp.musicmulti_24",
            "varp.musicmulti_25",
            "varp.musicmulti_26",
            "varp.musicmulti_27",
        )
}
