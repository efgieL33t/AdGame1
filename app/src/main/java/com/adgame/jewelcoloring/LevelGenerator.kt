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
        return Level(n, cells, scramble(cells, n, level, rng), palette.toIntArray())
    }

    /**
     * 正解の絵をもとに初期配置を作る。
     * 上下左右・斜めに対称な「軌道」(同じ色になるマスの組)単位で色を入れ替えるので、
     * 色ごとの個数は変わらず、見た目も対称な模様になる。外枠は正解のまま。
     */
    private fun scramble(target: IntArray, n: Int, level: Int, rng: Random): IntArray {
        val half = n / 2
        // 入れ替える範囲(レベルが上がるほど広い)
        val mask = Array(half) { IntArray(half) }
        val coverage = min(0.6f + level * 0.04f, 0.95f)
        val inner = (1 until half).sumOf { a -> half - a }
        var tries = 0
        while (tries++ < 200) {
            val covered = (1 until half).sumOf { a -> (a until half).count { b -> mask[a][b] == 1 } }
            if (covered >= inner * coverage) break
            drawShape(mask, 1, rng.nextInt(1, max(2, half / 2) + 1), rng)
        }

        // 軌道の代表 (a, b), a <= b。大きさ(4 か 8)ごとに入れ替える
        val orbits4 = ArrayList<IntArray>()
        val orbits8 = ArrayList<IntArray>()
        for (a in 1 until half) for (b in a until half) {
            if (mask[a][b] != 1) continue
            (if (a == b) orbits4 else orbits8).add(intArrayOf(a, b))
        }
        val colorOf = { o: IntArray -> target[o[0] * n + o[1]] }
        val angle = rng.nextFloat() * 2f
        val keyOf = { o: IntArray -> (half - 1 - o[0]) + (o[1] - o[0]) * angle / half }
        val assigned = HashMap<Long, Int>()
        for (orbits in listOf(orbits4, orbits8)) {
            if (orbits.size < 2) continue
            val slots = orbits.map { it to keyOf(it) + rng.nextFloat() * 0.3f }.sortedBy { it.second }.map { it.first }
            // 正解の絵で中心寄りにある色ほど外側へ来るように、色の順番を逆にして流し込む
            val meanKey = HashMap<Int, Float>()
            orbits.groupBy(colorOf).forEach { (c, os) -> meanKey[c] = os.map(keyOf).average().toFloat() }
            val colors = orbits.map(colorOf).sortedByDescending { meanKey[it] }
            slots.forEachIndexed { i, o -> assigned[o[0] * 1000L + o[1]] = colors[i] }
        }

        return IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            val fx = if (x < half) x else n - 1 - x
            val fy = if (y < half) y else n - 1 - y
            val a = min(fx, fy)
            val b = max(fx, fy)
            assigned[a * 1000L + b] ?: target[i]
        }
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
