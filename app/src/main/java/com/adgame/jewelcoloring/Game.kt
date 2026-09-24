package com.adgame.jewelcoloring

import kotlin.random.Random

/** 1ステージ分の絵。cells は palette のインデックス(size x size、行優先)。 */
class Level(val size: Int, val cells: IntArray, val palette: IntArray)

/**
 * ゲームの状態(描画に依存しない純粋なロジック)。
 *
 * ルール:
 * - 絵は同じ色がつながった「ピース」に分かれている。ピースをタップすると宝石がトレイへ移動する。
 * - トレイで同じ色が [setSize] 個そろうと消える。盤面からその色が無くなった場合は残りも消える。
 * - トレイの容量は [capacity]。どのピースも入りきらなくなったら失敗。
 * - 盤面の宝石をすべて回収したらクリア。
 */
class Game(
    val level: Level,
    val capacity: Int = 65,
    val setSize: Int = 10,
    seed: Long = 0L,
    minPiece: Int = 5,
    maxPiece: Int = 12,
) {
    val n = level.size
    val colorCount = level.palette.size

    /** 盤面の色。回収済みのマスは -1。 */
    val colorAt: IntArray = level.cells.copyOf()
    val pieceOf = IntArray(n * n) { -1 }
    val pieces = ArrayList<IntArray>()
    private val pieceAlive = ArrayList<Boolean>()

    val remaining = IntArray(colorCount)
    val trayCount = IntArray(colorCount)

    /** トレイ内の色の並び順(先に入った色が左上)。 */
    val trayOrder = ArrayList<Int>()

    var takenPieces = 0
        private set

    init {
        for (c in colorAt) remaining[c]++
        buildPieces(Random(seed), minPiece, maxPiece)
    }

    private fun buildPieces(rng: Random, minPiece: Int, maxPiece: Int) {
        val queue = IntArray(n * n)
        for (start in 0 until n * n) {
            if (pieceOf[start] != -1) continue
            val id = pieces.size
            val color = colorAt[start]
            val limit = rng.nextInt(minPiece, maxPiece + 1)
            var head = 0
            var tail = 0
            queue[tail++] = start
            pieceOf[start] = id
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
                    if (pieceOf[ni] == -1 && colorAt[ni] == color) {
                        pieceOf[ni] = id
                        queue[tail++] = ni
                    }
                }
            }
            pieces.add(queue.copyOf(tail))
            pieceAlive.add(true)
        }
    }

    val trayTotal: Int get() = trayCount.sum()
    val freeSlots: Int get() = capacity - trayTotal
    val boardEmpty: Boolean get() = remaining.all { it == 0 }
    val isWon: Boolean get() = boardEmpty && trayTotal == 0

    fun isAlive(piece: Int) = pieceAlive[piece]

    fun pieceAtCell(x: Int, y: Int): Int {
        if (x !in 0 until n || y !in 0 until n) return -1
        val p = pieceOf[y * n + x]
        return if (p >= 0 && pieceAlive[p]) p else -1
    }

    fun canTake(piece: Int) = pieceAlive[piece] && pieces[piece].size <= freeSlots

    /** ピースを回収してトレイに入れる。回収したマスを返す。 */
    fun take(piece: Int): IntArray {
        require(canTake(piece))
        val cells = pieces[piece]
        val color = colorAt[cells[0]]
        for (c in cells) colorAt[c] = -1
        pieceAlive[piece] = false
        remaining[color] -= cells.size
        trayCount[color] += cells.size
        if (color !in trayOrder) trayOrder.add(color)
        takenPieces++
        return cells
    }

    /** トレイ内でそろった宝石を消す。消えた (色, 個数) の一覧を返す。 */
    fun resolveClears(): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        for (color in trayOrder.toList()) {
            var cleared = 0
            while (trayCount[color] >= setSize) {
                trayCount[color] -= setSize
                cleared += setSize
            }
            if (remaining[color] == 0 && trayCount[color] > 0) {
                cleared += trayCount[color]
                trayCount[color] = 0
            }
            if (cleared > 0) result.add(color to cleared)
            if (trayCount[color] == 0) trayOrder.remove(color)
        }
        return result
    }

    /** 盤面が残っているのに、どのピースもトレイに入らない状態。 */
    fun isStuck(): Boolean {
        if (boardEmpty) return false
        val free = freeSlots
        for (i in pieces.indices) if (pieceAlive[i] && pieces[i].size <= free) return false
        return true
    }

    companion object {
        private val DX = intArrayOf(1, 0, -1, 0)
        private val DY = intArrayOf(0, 1, 0, -1)
    }
}
