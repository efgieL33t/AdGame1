package com.adgame.jewelcoloring

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** レベル番号から上下左右・斜め対称のモザイク柄を生成する。 */
object LevelGenerator {

    val PALETTE = intArrayOf(
        0xFFE8324F.toInt(), // 赤
        0xFFF59AB0.toInt(), // ピンク
        0xFFA6D62B.toInt(), // 黄緑
        0xFF1E7A3A.toInt(), // 深緑
        0xFF3FD6A0.toInt(), // ミント
        0xFF2C3450.toInt(), // 紺
        0xFFFFC928.toInt(), // 黄
        0xFF3B8BF0.toInt(), // 青
        0xFF9B59D6.toInt(), // 紫
        0xFFFF8A2A.toInt(), // オレンジ
        0xFF33D1E6.toInt(), // 水色
    )

    fun sizeFor(level: Int) = when {
        level <= 1 -> 14
        level <= 3 -> 16
        level <= 5 -> 18
        level <= 8 -> 20
        level <= 12 -> 22
        else -> 24
    }

    fun colorsFor(level: Int) = min(4 + (level + 1) / 2, 9)

    /** トレイの使える枠数(残りはロック表示)。 */
    fun capacityFor(level: Int) = when {
        level <= 2 -> 65
        level <= 4 -> 55
        level <= 7 -> 50
        level <= 11 -> 45
        else -> 40
    }

    fun generate(level: Int): Level {
        val rng = Random(level * 7919L + 17)
        val n = sizeFor(level)
        val half = n / 2
        val numColors = colorsFor(level)
        val chosen = PALETTE.indices.shuffled(rng).take(numColors)

        // 0 = 外枠の色, 1 = 背景色, 2以降 = 模様の色
        val q = Array(half) { IntArray(half) { 1 } }
        val shapes = 4 + min(level, 8)
        repeat(shapes) {
            drawShape(q, rng.nextInt(2, numColors), rng.nextInt(1, max(2, half / 2) + 1), rng)
        }
        // 上書きで消えた色は小さな模様として追加する
        for (c in 2 until numColors) {
            if ((0 until half).none { y -> (y until half).any { x -> q[y][x] == c } }) drawShape(q, c, rng.nextInt(1, 3), rng)
        }
        // 斜め対称
        for (y in 0 until half) for (x in 0 until y) q[y][x] = q[x][y]

        val raw = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val border = x == 0 || y == 0 || x == n - 1 || y == n - 1
            val fx = if (x < half) x else n - 1 - x
            val fy = if (y < half) y else n - 1 - y
            raw[y * n + x] = if (border) 0 else q[fy][fx]
        }

        // 使われている色だけにパレットを詰める
        val remap = IntArray(numColors) { -1 }
        val palette = ArrayList<Int>()
        val cells = IntArray(n * n) { i ->
            val k = raw[i]
            if (remap[k] == -1) {
                remap[k] = palette.size
                palette.add(PALETTE[chosen[k]])
            }
            remap[k]
        }
        return Level(n, cells, palette.toIntArray())
    }

    private fun drawShape(q: Array<IntArray>, c: Int, r: Int, rng: Random) {
        val half = q.size
        val type = rng.nextInt(4)
        // 斜め対称にしたとき残る側(x >= y)に中心を置く
        var cx = rng.nextInt(half)
        var cy = rng.nextInt(half)
        if (cx < cy) cx = cy.also { cy = cx }
        for (y in 0 until half) for (x in 0 until half) {
            val dx = abs(x - cx)
            val dy = abs(y - cy)
            val inside = when (type) {
                0 -> dx <= r && dy <= r / 2 + 1
                1 -> dx + dy <= r
                2 -> dx * dx + dy * dy <= r * r + r
                else -> dx + dy == r || dx + dy == r + 1
            }
            if (inside) q[y][x] = c
        }
    }
}
