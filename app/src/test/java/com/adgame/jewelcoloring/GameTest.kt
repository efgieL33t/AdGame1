package com.adgame.jewelcoloring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {

    @Test
    fun piecesCoverBoardAndAreSingleColor() {
        for (lv in 1..20) {
            val level = LevelGenerator.generate(lv)
            val game = Game(level, seed = lv.toLong())
            val seen = BooleanArray(level.cells.size)
            for (p in game.pieces) {
                assertTrue(p.size in 1..12)
                val c = level.cells[p[0]]
                for (i in p) {
                    assertEquals(c, level.cells[i])
                    assertTrue(!seen[i])
                    seen[i] = true
                }
            }
            assertTrue(seen.all { it })
        }
    }

    @Test
    fun everyLevelIsClearable() {
        for (lv in 1..30) {
            val game = Game(LevelGenerator.generate(lv), LevelGenerator.capacityFor(lv), seed = lv * 31L + 7)
            while (!game.isWon && !game.isStuck()) {
                // トレイに多い色から取る単純な戦略
                val piece = game.pieces.indices
                    .filter { game.canTake(it) }
                    .maxBy { game.trayCount[game.level.cells[game.pieces[it][0]]] * 100 + game.pieces[it].size }
                game.take(piece)
                game.resolveClears()
                assertTrue(game.trayTotal <= game.capacity)
            }
            assertTrue("level $lv", game.isWon)
        }
    }

    @Test
    fun colorClearsWhenBoardHasNoMore() {
        val level = Level(2, intArrayOf(0, 0, 1, 1), intArrayOf(0, 1))
        val game = Game(level, minPiece = 2, maxPiece = 2)
        game.take(game.pieceAtCell(0, 0))
        assertEquals(listOf(0 to 2), game.resolveClears())
        game.take(game.pieceAtCell(0, 1))
        assertEquals(listOf(1 to 2), game.resolveClears())
        assertTrue(game.isWon)
    }
}
