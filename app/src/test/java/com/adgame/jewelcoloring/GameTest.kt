package com.adgame.jewelcoloring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {

    @Test
    fun startHasSameColorCountsAsTarget() {
        for (lv in 1..30) {
            val level = LevelGenerator.generate(lv)
            val a = IntArray(level.palette.size)
            val b = IntArray(level.palette.size)
            for (c in level.target) a[c]++
            for (c in level.start) b[c]++
            assertTrue("level $lv", a.contentEquals(b))
            val wrong = level.target.indices.count { level.target[it] != level.start[it] }
            assertTrue("level $lv wrong=$wrong", wrong >= level.target.size / 5)
        }
    }

    @Test
    fun everyLevelIsClearableByTapping() {
        for (lv in 1..30) {
            val game = Game(LevelGenerator.generate(lv))
            var taps = 0
            while (!game.isWon) {
                // 動かせるグループを順に探してタップする
                val cell = (0 until game.n * game.n).first { c ->
                    game.jewel[c] >= 0 && !game.isCorrect(c) && wouldMove(game, c)
                }
                assertTrue(game.move(cell).isNotEmpty())
                game.autoFill()
                assertTrue(game.trayTotal <= game.capacity)
                assertTrue("level $lv too many taps", ++taps < 10_000)
            }
        }
    }

    private fun wouldMove(game: Game, c: Int): Boolean {
        val color = game.jewel[c]
        return game.freeSlots > 0 ||
            (0 until game.n * game.n).any { game.jewel[it] == -1 && game.level.target[it] == color }
    }

    @Test
    fun groupMovesDirectlyThenViaTray() {
        // 正解: 0 0 / 1 1   初期: 1 1 / 0 0
        val level = Level(2, intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 0, 0), intArrayOf(0, 1))
        val game = Game(level, capacity = 2)
        // 空きが無いので上段の1は2つともトレイへ
        val first = game.move(0)
        assertEquals(listOf(Transfer(1, 0, -1), Transfer(1, 1, -1)), first)
        assertTrue(game.autoFill().isEmpty())
        // 下段の0は空いた上段へ直接、その後トレイの1が下段へ自動で入る
        val second = game.move(2)
        assertEquals(setOf(0, 1), second.map { it.to }.toSet())
        assertEquals(2, game.autoFill().size)
        assertTrue(game.isWon)
        assertEquals(2, game.moves)
    }

    @Test
    fun trayFullMovesOnlyWhatFits() {
        val level = Level(2, intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 0, 0), intArrayOf(0, 1))
        val game = Game(level, capacity = 1)
        assertEquals(1, game.move(0).size)
        assertEquals(1, game.jewel.count { it == 1 })
    }
}
