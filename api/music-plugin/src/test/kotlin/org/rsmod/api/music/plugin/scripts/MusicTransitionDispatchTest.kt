package org.rsmod.api.music.plugin.scripts

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import kotlinx.coroutines.runBlocking
import net.rsprot.protocol.game.outgoing.sound.MidiSongV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.music.MusicRepository
import org.rsmod.api.player.events.EngineQueueEvents
import org.rsmod.api.player.events.PlayerTimerEvent
import org.rsmod.api.player.music.MusicPlayer
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.DefaultGameRandom
import org.rsmod.api.random.GameRandom
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class MusicTransitionDispatchTest {
    @Test
    fun `login and adjacent area hooks send one overlapping transition without a stop`() = runBlocking {
        val f = Fixture()
        f.timer()
        assertTrue(f.songs().isEmpty(), "The login timer runs before the first area hook")
        f.enter(PORT_SARIM)
        val first = f.songs().single()
        assertEquals(listOf(0, 0, 0, 0), first.fadeFields())

        f.client.messages.clear()
        f.queueExit(PORT_SARIM)
        f.queueEnter(VARROCK)
        f.drain()
        f.timer()

        val transition = f.songs().single()
        assertEquals(f.currentMidi(), transition.id)
        assertTrue(transition.id != first.id)
        assertEquals(listOf(0, 100, 0, 100), transition.fadeFields())
        assertEquals(VARROCK.asRSCM() + 1, f.player.vars["varbit.music_curr_area"])
    }

    @Test
    fun `leaving an area while muted resolves silence and unmuting cannot revive its track`() = runBlocking {
        val f = Fixture()
        f.enter(PORT_SARIM)
        f.client.messages.clear()
        f.set("varbit.option_music_desktop", 0)
        f.queueExit(PORT_SARIM)
        f.drain()
        assertTrue(f.songs().isEmpty(), "An exit must allow a following area hook to replace the track")

        f.timer()
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
        val stopped = f.songs().single()
        assertEquals("midi.stop_music".asRSCM(), stopped.id)
        assertTrue(stopped.fadeOutSpeed > 0)
        f.timer()
        assertEquals(1, f.songs().size, "Silence must not resend a stop every tick")

        f.set("varbit.option_music_desktop", 100)
        f.timer("timer.music_resume")
        f.timer()
        assertEquals(1, f.songs().size, "The registered resume hook must preserve the silent area")
        assertEquals(0, f.player.vars["varbit.music_curr_id"])
    }

    @Test
    fun `a queued incoming area protects the old track even at its duration boundary`() = runBlocking {
        val f = Fixture()
        f.enter(PORT_SARIM)
        val oldTrack = f.player.vars["varbit.music_curr_id"]
        val duration = f.player.vars["varbit.music_curr_duration"]
        f.client.messages.clear()
        f.queueExit(PORT_SARIM)
        f.queueEnter(VARROCK)
        // Another protected area hook can delay the remaining queue until a later cycle.
        f.drain(limit = 1)
        f.set("varbit.music_curr_clocks", duration)
        f.timer()
        f.timer("timer.music_resume")
        f.timer()
        assertTrue(f.songs().isEmpty(), "Neither a stop nor a track-end restart may precede the pending entry")
        assertEquals(oldTrack, f.player.vars["varbit.music_curr_id"])
        assertEquals(duration, f.player.vars["varbit.music_curr_clocks"])

        f.drain()
        f.timer()
        val transition = f.songs().single()
        assertEquals(f.currentMidi(), transition.id)
        assertEquals(listOf(0, 100, 0, 100), transition.fadeFields())
    }

    @Test
    fun `same-area exit and reentry do not restart the track through the timer`() = runBlocking {
        val f = Fixture()
        assertEquals(1, checkNotNull(f.repo.getModernArea(PORT_SARIM)).size)
        f.enter(PORT_SARIM)
        val oldTrack = f.player.vars["varbit.music_curr_id"]
        f.client.messages.clear()
        f.queueExit(PORT_SARIM)
        f.queueEnter(PORT_SARIM)
        f.drain()
        f.timer()
        assertTrue(f.songs().isEmpty())
        assertEquals(oldTrack, f.player.vars["varbit.music_curr_id"])
    }

    @OptIn(InternalApi::class)
    private class Fixture {
        val client = RecordingClient()
        val player = Player().apply { this.client = this@Fixture.client }
        private val random = DefaultGameRandom(1)
        val repo = MusicRepository(random).apply { load() }
        private val music = MusicPlayer::class.java
            .getDeclaredConstructor(GameRandom::class.java, MusicRepository::class.java)
            .apply { isAccessible = true }
            .newInstance(random, repo)
        private val events = EventBus()
        private val queueCache = EngineQueueCache()
        private val access = ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

        init {
            val context = ScriptContext(events, CheatCommandMap(), queueCache)
            with(MusicAreaScript(music)) { context.startup() }
            with(MusicTimerScript(music)) { context.startup() }
            set("varbit.option_master_volume_desktop", 100)
            set("varbit.option_music_desktop", 100)
            set("varbit.music_area_mode", 0)
            assertTrue(events.publish(SessionStateEvent.Login(player)))
        }

        fun set(key: String, value: Int) = VarPlayerIntMapSetter.set(player, key, value)

        fun songs(): List<MidiSongV2> = client.messages.filterIsInstance<MidiSongV2>()

        fun currentMidi(): Int = checkNotNull(repo.forId(player.vars["varbit.music_curr_id"])).midi.id

        fun queueEnter(area: String) = player.engineQueueArea(area.asRSCM().toShort())

        fun queueExit(area: String) = player.engineQueueAreaExit(area.asRSCM().toShort())

        suspend fun enter(area: String) {
            queueEnter(area)
            drain()
        }

        suspend fun drain(limit: Int = Int.MAX_VALUE) {
            val iterator = checkNotNull(player.engineQueueList.iterator())
            var processed = 0
            try {
                while (iterator.hasNext() && processed++ < limit) {
                    val queue = iterator.next()
                    iterator.remove()
                    assertTrue(queueCache.hasLabelScript(queue), "Missing native area binding: $queue")
                    val event = EngineQueueEvents.Labelled(EventBus.composeLongKey(queue.label, queue.type))
                    assertTrue(events.publish(access, event))
                }
            } finally {
                iterator.cleanUp()
            }
        }

        fun timer(type: String = "timer.music_sync") {
            assertTrue(events.publish(PlayerTimerEvent.Soft(player, type.asRSCM())))
        }
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

    private fun MidiSongV2.fadeFields(): List<Int> =
        listOf(fadeOutDelay, fadeOutSpeed, fadeInDelay, fadeInSpeed)

    companion object {
        private const val PORT_SARIM = "area.port_sarim"
        private const val VARROCK = "area.varrock"

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            ServerCacheManager.init(241).close()
        }
    }
}
