package dev.openrune.map.area

import dev.openrune.map.util.InlineByteBuf
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap
import it.unimi.dsi.fastutil.shorts.ShortArraySet
import it.unimi.dsi.fastutil.shorts.ShortSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MapAreaCodecTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 255, 256])
    fun `zone count preserves all four planes and following sections`(count: Int) {
        val zones = Byte2ObjectOpenHashMap<ShortSet>()
        repeat(count) { zones[it.toByte()] = ShortArraySet(shortArrayOf(33000.toShort())) }
        val coords = Short2ObjectOpenHashMap<ShortSet>()
        coords[1234.toShort()] = ShortArraySet(shortArrayOf(12))
        val includes = Short2ObjectOpenHashMap<ShortSet>()
        includes[5.toShort()] = ShortArraySet(shortArrayOf(6))
        val excludes = Short2ObjectOpenHashMap<ShortSet>()
        excludes[5.toShort()] = ShortArraySet(shortArrayOf(7))
        val expected =
            MapAreaDefinition(ShortArraySet(shortArrayOf(5)), zones, coords, includes, excludes)
        val buffer = Unpooled.buffer()
        try {
            MapAreaEncoder.encode(expected, buffer)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            assertEquals(expected, MapAreaDecoder.decode(InlineByteBuf(bytes)))
        } finally {
            buffer.release()
        }
    }
}
