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
 * - 間違った位置のジュエルを選ぶと、つながっている同色の(間違った位置の)ジュエルがまとめて選ばれる。
 *   行き先として盤面の空きマスか、下のトレイ(保留場所)をプレイヤーが選ぶ。
 * - トレイのジュエルは色ごとに選び、盤面の空きマスへ出せる。
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
     * 選んだジュエル(同じ色)を下のトレイへ入れる。先頭から入るだけ入れ、残りはその場に残る。
     * cells は groupAt の結果や、前回入りきらなかった残り。
     */
    fun moveToTray(cells: IntArray): List<Transfer> {
        val color = selectionColor(cells) ?: return emptyList()
        val result = ArrayList<Transfer>()
        for (g in cells.take(freeSlots)) {
            jewel[g] = -1
            trayCount[color]++
            result.add(Transfer(color, g, -1))
        }
        if (result.isNotEmpty()) {
            if (color !in trayOrder) trayOrder.add(color)
            moves++
        }
        return result
    }

    /** 選んだジュエルを、dest から続く空きマスへ移す。先頭から入るだけ入れ、残りはその場に残る。 */
    fun moveToBoard(cells: IntArray, dest: Int): List<Transfer> {
        val color = selectionColor(cells) ?: return emptyList()
        val targets = fillCells(color, dest, cells.size)
        val result = ArrayList<Transfer>()
        for ((k, to) in targets.withIndex()) {
            val from = cells[k]
            jewel[from] = -1
            jewel[to] = color
            result.add(Transfer(color, from, to))
        }
        if (result.isNotEmpty()) moves++
        return result
    }

    /** 選択が有効(空でなく、すべて同じ色の間違った位置のジュエル)ならその色。 */
    private fun selectionColor(cells: IntArray): Int? {
        if (cells.isEmpty()) return null
        val color = jewel[cells[0]]
        if (color < 0) return null
        return if (cells.all { jewel[it] == color && !isCorrect(it) }) color else null
    }

    /** トレイにある color のジュエルを、dest から続く空きマスへ出す。 */
    fun moveFromTray(color: Int, dest: Int): List<Transfer> {
        if (trayCount[color] == 0) return emptyList()
        val cells = fillCells(color, dest, trayCount[color])
        for (to in cells) jewel[to] = color
        trayCount[color] -= cells.size
        if (trayCount[color] == 0) trayOrder.remove(color)
        if (cells.isNotEmpty()) moves++
        return cells.map { Transfer(color, -1, it) }
    }

    /**
     * dest から始めて埋める空きマス(最大 limit 個、近い順)。
     * dest がその色の正解マスなら、つながった正解マスだけを埋める。そうでなければ空きマスを順に埋める。
     */
    fun fillCells(color: Int, dest: Int, limit: Int): List<Int> {
        if (jewel[dest] != -1 || limit <= 0) return emptyList()
        val onlyCorrect = level.target[dest] == color
        val seen = BooleanArray(n * n)
        val queue = IntArray(n * n)
        var head = 0
        var tail = 0
        queue[tail++] = dest
        seen[dest] = true
        while (head < tail && tail < limit) {
            val cur = queue[head++]
            val x = cur % n
            val y = cur / n
            for (d in 0 until 4) {
                if (tail >= limit) break
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx !in 0 until n || ny !in 0 until n) continue
                val ni = ny * n + nx
                if (seen[ni] || jewel[ni] != -1) continue
                if (onlyCorrect && level.target[ni] != color) continue
                seen[ni] = true
                queue[tail++] = ni
            }
        }
        return queue.take(tail)
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    companion object {
        private val DX = intArrayOf(1, 0, -1, 0)
        private val DY = intArrayOf(0, 1, 0, -1)
    }
}
