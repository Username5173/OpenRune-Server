package org.rsmod.api.music

import dev.openrune.types.aconverted.MidiType

public data class Music(
    val id: Int,
    val rowId: Int,
    val displayName: String,
    val unlockHint: String,
    val duration: Int,
    val midi: MidiType,
    val unlockVarp: String?,
    /** 1-based index into the `varp.musicmulti_*` unlock varps, or `0` when [canUnlock] is false. */
    val unlockVarpIndex: Int,
    val unlockBitpos: Int,
    val hidden: Boolean,
    val secondaryMidi: MidiType? = null,
    val automaticUnlock: Boolean = false,
    /** Classic music-area ids this track unlocks in, from `dbcol.music:area`. */
    val classicAreas: List<Int> = emptyList(),
    /** The classic music-area this track is the default of, from `dbcol.music:area_default`. */
    val classicAreaDefault: Int? = null,
) {
    val unlockBitflag: Int
        get() = 1 shl unlockBitpos

    val canUnlock: Boolean
        get() = unlockVarp != null

    /**
     * The value the music tab stores in a `varbit.music_playlist_*_track_*` slot for this track:
     * the client resolves it back to the row through `variable = (value / 100, value % 100)`.
     */
    val playlistSlotValue: Int
        get() = unlockVarpIndex * 100 + unlockBitpos
}

public data class MusicVariable(val varpIndex: Int, val bitpos: Int)
