package org.rsmod.api.player

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import net.rsprot.protocol.game.outgoing.sound.MidiJingle
import net.rsprot.protocol.game.outgoing.sound.MidiSongV2
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.game.entity.Player

public var Player.musicClocks: Int by intVarBit("varbit.music_curr_clocks")

/**
 * Plays the jingle stored as cache index-11 group [jingleTrack].
 *
 * The client treats the [MidiJingle] id as a raw group id of the jingles archive (index 11, groups
 * 0-310/830-832/864); a missing group is a silent no-op. Note that `jingle.*` gameval ids are
 * Jagex's internal jingle DB ordering, NOT track ids - resolving one via RSCM and sending it plays
 * nothing.
 *
 * @see [MidiJingle]
 */
public fun Player.midiJingle(jingleTrack: Int, lengthInMillis: Int = 0) {
    musicClocks = 0 // Client restarts music when a jingle is played.
    client.write(MidiJingle(jingleTrack, lengthInMillis))
}

@Deprecated(
    "Jingle gameval ids are not index-11 track ids; the client cannot play them. " +
        "Use the Int overload with the jingle's cache track id (wiki `Cache ID`).",
    ReplaceWith("midiJingle(jingleTrack)"),
)
public fun Player.midiJingle(jingle: String) {
    musicClocks = 0 // Client restarts music when a jingle is played.
    client.write(MidiJingle(jingle.asRSCM(RSCMType.JINGLE)))
}

public fun Player.midiSong(
    midi: String,
    fadeOutDelay: Int = 0,
    fadeOutSpeed: Int = 0,
    fadeInDelay: Int = 0,
    fadeInSpeed: Int = 0,
) {
    client.write(
        MidiSongV2(midi.asRSCM(RSCMType.MIDI), fadeOutDelay, fadeOutSpeed, fadeInDelay, fadeInSpeed)
    )
}

public fun Player.midiSong(
    midi: Int,
    fadeOutDelay: Int = 0,
    fadeOutSpeed: Int = 0,
    fadeInDelay: Int = 0,
    fadeInSpeed: Int = 0,
) {
    client.write(MidiSongV2(midi, fadeOutDelay, fadeOutSpeed, fadeInDelay, fadeInSpeed))
}
