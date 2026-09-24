package com.adgame.jewelcoloring

/**
 * 1ステージ分のデータ(size x size、行優先)。
 * target: 各マスの正解の色 / start: 最初に置かれているジュエルの色(色ごとの個数は target と同じ)。
 */
class Level(val size: Int, val target: IntArray, val start: IntArray, val palette: IntArray)

/** ジュエル1個の移動。from / to はマス番号、-1 はトレイ。 */
data class Transfer(val color: Int, val from: Int, val to: Int)

/**
 * ゲームの状態(描画に依存しない純粋なロジック)。
 *
 * ルール:
 * - 各マスには正解の色があり、ジュエルを正しい色のマスへ並べ替えるのが目的。
 * - 間違った位置のジュエルをタップすると、つながっている同色の(間違った位置の)ジュエルがまとめて動く。
 *   その色の正解マスが空いていれば直接そこへ、空きが無い分は下のトレイへ移動する。
 * - トレイにあるジュエルは、同じ色の正解マスが空くと自動でそこへ入る。
 * - 全マスが正しい色で埋まればクリア。
 */
class Game(val level: Level, val capacity: Int = 65) {
    val n = level.size
    val colorCount = level.palette.size

    /** 盤面のジュエル。空きマスは -1。 */
    val jewel: IntArray = level.start.copyOf()
    val trayCount = IntArray(colorCount)

    /** トレイ内の色の並び順(先に入った色が左上)。 */
    val trayOrder = ArrayList<Int>()

    var moves = 0
        private set

    val trayTotal: Int get() = trayCount.sum()
    val freeSlots: Int get() = capacity - trayTotal
    val correctCount: Int get() = (0 until n * n).count { jewel[it] == level.target[it] }
    val isWon: Boolean get() = correctCount == n * n

    fun isCorrect(cell: Int) = jewel[cell] == level.target[cell]

    /** cell とつながっている、同じ色で間違った位置のジュエル(近い順)。 */
    fun groupAt(cell: Int): IntArray {
        val color = jewel[cell]
        if (color < 0 || isCorrect(cell)) return IntArray(0)
        val seen = BooleanArray(n * n)
        val queue = IntArray(n * n)
        var head = 0
        var tail = 0
        queue[tail++] = cell
        seen[cell] = true
        while (head < tail) {
            val cur = queue[head++]
            val x = cur % n
            val y = cur / n
            for (d in 0 until 4) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx !in 0 until n || ny !in 0 until n) continue
                val ni = ny * n + nx
                if (!seen[ni] && jewel[ni] == color && !isCorrect(ni)) {
                    seen[ni] = true
                    queue[tail++] = ni
                }
            }
        }
        return queue.copyOf(tail)
    }

    /**
     * cell のジュエルのグループを動かす。
     * 盤面の空いている正解マスを優先し、残りはトレイへ。どちらも無くなったら残りはその場に留まる。
     */
    fun move(cell: Int): List<Transfer> {
        val group = groupAt(cell)
        if (group.isEmpty()) return emptyList()
        val color = jewel[cell]
        val tx = cell % n
        val ty = cell / n
        val empties = (0 until n * n)
            .filter { jewel[it] == -1 && level.target[it] == color }
            .sortedBy { abs(it % n - tx) + abs(it / n - ty) }
        var free = freeSlots
        var e = 0
        val result = ArrayList<Transfer>()
        for (g in group) {
            val to = when {
                e < empties.size -> empties[e++]
                free > 0 -> { free--; -1 }
                else -> break
            }
            jewel[g] = -1
            if (to >= 0) {
                jewel[to] = color
            } else {
                trayCount[color]++
                if (color !in trayOrder) trayOrder.add(color)
            }
            result.add(Transfer(color, g, to))
        }
        if (result.isNotEmpty()) moves++
        return result
    }

    /** 空いたマスに、トレイから同じ色のジュエルを入れる。 */
    fun autoFill(): List<Transfer> {
        val result = ArrayList<Transfer>()
        for (i in 0 until n * n) {
            if (jewel[i] != -1) continue
            val t = level.target[i]
            if (trayCount[t] > 0) {
                trayCount[t]--
                jewel[i] = t
                result.add(Transfer(t, -1, i))
            }
        }
        trayOrder.removeAll { trayCount[it] == 0 }
        return result
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    companion object {
        private val DX = intArrayOf(1, 0, -1, 0)
        private val DY = intArrayOf(0, 1, 0, -1)
    }
}
