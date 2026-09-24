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
                assertTrue(playOneMove(game))
                assertTrue(game.trayTotal <= game.capacity)
                assertTrue("level $lv too many taps", ++taps < 10_000)
            }
        }
    }

    /** 簡単な戦略: トレイから正解マスへ出せるなら出す。無ければ盤面のグループを正解マスかトレイへ。 */
    private fun playOneMove(game: Game): Boolean {
        val cells = 0 until game.n * game.n
        fun emptyCorrect(color: Int) = cells.firstOrNull { game.jewel[it] == -1 && game.level.target[it] == color }
        for (color in game.trayOrder.toList()) {
            val e = emptyCorrect(color) ?: continue
            if (game.moveFromTray(color, e).isNotEmpty()) return true
        }
        for (c in cells) {
            if (game.jewel[c] < 0 || game.isCorrect(c)) continue
            val e = emptyCorrect(game.jewel[c])
            if (e != null && game.moveToBoard(c, e).isNotEmpty()) return true
        }
        for (c in cells) {
            if (game.jewel[c] < 0 || game.isCorrect(c)) continue
            if (game.moveToTray(c).isNotEmpty()) return true
        }
        return false
    }

    @Test
    fun playerChoosesTrayOrBoard() {
        // 正解: 0 0 / 1 1   初期: 1 1 / 0 0
        val level = Level(2, intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 0, 0), intArrayOf(0, 1))
        val game = Game(level, capacity = 2)
        // 上段の1をトレイへ
        assertEquals(listOf(Transfer(1, 0, -1), Transfer(1, 1, -1)), game.moveToTray(0))
        // 下段の0を空いた上段へ直接
        assertEquals(setOf(0, 1), game.moveToBoard(2, 0).map { it.to }.toSet())
        // トレイの1を下段へ(自動では動かない)
        assertEquals(2, game.trayCount[1])
        assertEquals(2, game.moveFromTray(1, 3).size)
        assertTrue(game.isWon)
        assertEquals(3, game.moves)
    }

    @Test
    fun boardMoveCanUseAnyEmptyCell() {
        // 正解: 0 1 / 1 1   初期: 1 0 / 1 1。左上を空けて、右上の0を直接そこへ移す
        val level = Level(2, intArrayOf(0, 1, 1, 1), intArrayOf(1, 0, 1, 1), intArrayOf(0, 1))
        val game = Game(level, capacity = 1)
        assertEquals(1, game.moveToTray(0).size)   // 左上の1をトレイへ
        assertEquals(listOf(Transfer(0, 1, 0)), game.moveToBoard(1, 0)) // 右上の0を左上へ
        assertEquals(1, game.moveFromTray(1, 1).size)
        assertTrue(game.isWon)
    }

    @Test
    fun trayFullMovesOnlyWhatFits() {
        val level = Level(2, intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 0, 0), intArrayOf(0, 1))
        val game = Game(level, capacity = 1)
        assertEquals(1, game.moveToTray(0).size)
        assertEquals(1, game.jewel.count { it == 1 })
    }

    @Test
    fun fillPrefersConnectedCorrectCells() {
        // 3x3: 右列だけ正解が1。1のジュエル3個を右上に置くと、右列(正解マス)だけ埋まる
        val target = intArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 1)
        val start = intArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 1)
        val game = Game(Level(3, target, start, intArrayOf(0, 1)))
        val cells = listOf(2, 5, 8)
        for (c in cells) game.jewel[c] = -1
        assertEquals(cells, game.fillCells(1, 2, 5).sorted())
        // 正解でないマスから始めると、つながった空きマスを順に埋める
        game.jewel[0] = -1
        assertEquals(listOf(0), game.fillCells(1, 0, 1))
    }
}
