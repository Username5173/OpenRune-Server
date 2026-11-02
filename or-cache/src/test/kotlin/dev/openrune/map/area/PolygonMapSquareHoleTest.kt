package dev.openrune.map.area

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.game.area.polygon.PolygonMapSquareBuilder
import org.rsmod.map.square.MapSquareGrid

class PolygonMapSquareHoleTest {
    @Test
    fun `hole clears covered tiles`() {
        val builder = PolygonMapSquareBuilder()
        builder.polygon(AREA, listOf(0)) {
            vertex(0, 0)
            vertex(4, 0)
            vertex(4, 4)
            vertex(0, 4)
        }
        builder.hole(AREA, listOf(0)) {
            vertex(1, 1)
            vertex(3, 1)
            vertex(3, 3)
            vertex(1, 3)
        }

        val square = builder.build()

        assertTrue(square.containsArea(AREA, MapSquareGrid(0, 0)))
        assertTrue(square.containsArea(AREA, MapSquareGrid(4, 4)))
        assertFalse(square.containsArea(AREA, MapSquareGrid(2, 2)))
    }

    @Test
    fun `hole clears only its levels`() {
        val builder = PolygonMapSquareBuilder()
        builder.polygon(AREA, listOf(0, 1)) {
            vertex(0, 0)
            vertex(4, 0)
            vertex(4, 4)
            vertex(0, 4)
        }
        builder.hole(AREA, listOf(0)) {
            vertex(1, 1)
            vertex(3, 1)
            vertex(3, 3)
            vertex(1, 3)
        }

        val square = builder.build()

        assertFalse(square.containsArea(AREA, MapSquareGrid(2, 2, 0)))
        assertTrue(square.containsArea(AREA, MapSquareGrid(2, 2, 1)))
    }

    @Test
    fun `hole tolerates duplicate vertices`() {
        val builder = PolygonMapSquareBuilder()
        builder.polygon(AREA, listOf(0)) {
            vertex(0, 0)
            vertex(4, 0)
            vertex(4, 4)
            vertex(0, 4)
        }
        builder.hole(AREA, listOf(0)) {
            vertex(1, 1)
            vertex(1, 1)
            vertex(3, 1)
            vertex(3, 3)
            vertex(1, 3)
        }

        val square = builder.build()

        assertFalse(square.containsArea(AREA, MapSquareGrid(2, 2)))
    }

    private companion object {
        private const val AREA: Short = 1
    }
}
