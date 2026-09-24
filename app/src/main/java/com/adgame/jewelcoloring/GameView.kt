package com.adgame.jewelcoloring

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private enum class State { PLAYING, WON }

    /** 移動中/トレイ内のジュエル。dest はマス番号、-1 はトレイ。 */
    private class Bead(val color: Int, var x: Float, var y: Float, var delay: Float, val dest: Int) {
        var tx = 0f
        var ty = 0f
        var startDist = 1f
        var fromSize = 0f
        var arrived = false
    }

    private class Spark(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Int, var life: Float)

    private val prefs = context.getSharedPreferences("jewel_coloring", Context.MODE_PRIVATE)
    private var levelNo = prefs.getInt("level", 1)

    private lateinit var level: Level
    private lateinit var game: Game
    private lateinit var incoming: BooleanArray
    private var state = State.PLAYING
    private var stateTime = 0f

    private val trayBeads = ArrayList<Bead>()
    private val flyers = ArrayList<Bead>()
    private val sparks = ArrayList<Spark>()
    private val rng = Random(System.nanoTime())

    /** 選んでいる盤面のグループ / トレイの色(-1 = なし)。 */
    private var selGroup = IntArray(0)
    private var selTrayColor = -1

    private var shakeGroup = IntArray(0)
    private var shakeTime = 0f
    private var message = ""
    private var messageTime = 0f
    private var time = 0f
    private var lastFrame = 0L

    // レイアウト
    private val dp = resources.displayMetrics.density
    private var insetTop = 0f
    private var insetBottom = 0f
    private val boardRect = RectF()
    private var cell = 1f
    private val trayRect = RectF()
    private var slot = 1f
    private var slotLeft = 0f
    private var slotTop = 0f
    private val restartRect = RectF()
    private val overlayCard = RectF()
    private val overlayButton = RectF()
    private var titleY = 0f

    // 描画
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val beadCache = HashMap<Int, Bitmap>()
    private var beadBitmapSize = 0
    private val tmpRect = RectF()
    private var bgShader: Shader? = null

    private val boardBead: Float get() = cell * 0.86f
    private val trayBead: Float get() = slot * 0.86f

    init {
        startLevel()
    }

    // ---------------------------------------------------------------- 進行

    private fun startLevel() {
        level = LevelGenerator.generate(levelNo)
        game = Game(level, capacity = TRAY_COLS * TRAY_ROWS)
        incoming = BooleanArray(game.n * game.n)
        trayBeads.clear()
        flyers.clear()
        sparks.clear()
        shakeGroup = IntArray(0)
        clearSelection()
        message = ""
        state = State.PLAYING
        stateTime = 0f
        beadCache.clear()
        if (width > 0) layoutViews(width, height)
        invalidate()
    }

    private fun cellX(c: Int) = boardRect.left + (c % game.n + 0.5f) * cell
    private fun cellY(c: Int) = boardRect.top + (c / game.n + 0.5f) * cell

    private fun clearSelection() {
        selGroup = IntArray(0)
        selTrayColor = -1
    }

    private fun onTapBoard(x: Float, y: Float) {
        val cx = ((x - boardRect.left) / cell).toInt().coerceIn(0, game.n - 1)
        val cy = ((y - boardRect.top) / cell).toInt().coerceIn(0, game.n - 1)
        val c = cy * game.n + cx
        if (incoming[c]) return

        if (game.jewel[c] >= 0) {
            // ジュエルを選ぶ(同じグループをもう一度タップで解除)
            val group = game.groupAt(c)
            val wasSelected = c in selGroup
            clearSelection()
            if (group.isNotEmpty() && !wasSelected) selGroup = group
            if (group.isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }

        // 空きマス: 選んでいるジュエルをここへ移す
        val moves = when {
            selGroup.isNotEmpty() -> game.moveToBoard(selGroup, c)
            selTrayColor >= 0 -> game.moveFromTray(selTrayColor, c)
            else -> return
        }
        if (moves.isEmpty()) return
        val step = min(0.025f, 0.6f / moves.size)
        moves.forEachIndexed { i, m ->
            val b: Bead
            if (m.from >= 0) {
                b = Bead(m.color, cellX(m.from), cellY(m.from), i * step, m.to)
                b.fromSize = boardBead
            } else {
                val src = trayBeads.lastOrNull { it.color == m.color && it.arrived }
                    ?: trayBeads.last { it.color == m.color }
                trayBeads.remove(src)
                b = Bead(m.color, src.x, src.y, i * step, m.to)
                b.fromSize = trayBead
            }
            b.tx = cellX(m.to)
            b.ty = cellY(m.to)
            b.startDist = max(1f, hypot(b.tx - b.x, b.ty - b.y))
            incoming[m.to] = true
            flyers.add(b)
        }
        keepLeftover(moves)
        relayoutTray()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 入りきらなかった分は、選んだ(浮いた)状態のまま残して続けて置けるようにする。 */
    private fun keepLeftover(moves: List<Transfer>) {
        if (selGroup.isNotEmpty()) {
            val moved = moves.mapTo(HashSet()) { it.from }
            selGroup = selGroup.filter { it !in moved }.toIntArray()
        } else if (selTrayColor >= 0 && game.trayCount[selTrayColor] == 0) {
            selTrayColor = -1
        }
    }

    private fun onTapTray(x: Float, y: Float) {
        if (selGroup.isNotEmpty()) {
            // 選んでいる盤面のジュエルをトレイへ
            val moves = game.moveToTray(selGroup)
            if (moves.isEmpty()) {
                shakeGroup = selGroup
                shakeTime = 0.4f
                showMessage("トレイがいっぱいです")
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                return
            }
            val step = min(0.025f, 0.6f / moves.size)
            moves.forEachIndexed { i, m ->
                val b = Bead(m.color, cellX(m.from), cellY(m.from), i * step, -1)
                b.fromSize = boardBead
                trayBeads.add(b)
            }
            keepLeftover(moves)
            relayoutTray()
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        // トレイのジュエルを色ごとに選ぶ
        val half = slot / 2
        val hit = trayBeads.firstOrNull { it.arrived && x in it.x - half..it.x + half && y in it.y - half..it.y + half }
        selTrayColor = if (hit == null || hit.color == selTrayColor) -1 else hit.color
        if (hit != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** トレイ内のジュエルを色ごとにまとめて並べ直す。 */
    private fun relayoutTray() {
        var index = 0
        for (color in game.trayOrder) {
            for (b in trayBeads) {
                if (b.color != color) continue
                val col = index % TRAY_COLS
                val row = index / TRAY_COLS
                val nx = slotLeft + (col + 0.5f) * slot
                val ny = slotTop + (row + 0.5f) * slot
                if (nx != b.tx || ny != b.ty || !b.arrived) {
                    if (b.arrived) b.fromSize = trayBead
                    b.tx = nx
                    b.ty = ny
                    b.arrived = false
                    b.startDist = max(1f, hypot(b.tx - b.x, b.ty - b.y))
                }
                index++
            }
        }
    }

    private fun showMessage(text: String) {
        message = text
        messageTime = 1.4f
    }

    private fun spawnSpark(x: Float, y: Float, color: Int) {
        val a = rng.nextFloat() * 2f * PI.toFloat()
        val s = (60 + rng.nextFloat() * 160) * dp
        sparks.add(Spark(x, y, cos(a) * s, sin(a) * s - 80 * dp, color, 0.6f + rng.nextFloat() * 0.4f))
    }

    // ---------------------------------------------------------------- 更新

    private fun moveBead(b: Bead, k: Float, dt: Float): Boolean {
        if (b.arrived) return true
        if (b.delay > 0) {
            b.delay -= dt
            return false
        }
        b.x += (b.tx - b.x) * k
        b.y += (b.ty - b.y) * k
        if (hypot(b.tx - b.x, b.ty - b.y) < 0.8f * dp) {
            b.x = b.tx
            b.y = b.ty
            b.arrived = true
        }
        return b.arrived
    }

    private fun update(dt: Float) {
        time += dt
        stateTime += dt
        if (shakeTime > 0) shakeTime -= dt
        if (messageTime > 0) messageTime -= dt

        val k = 1f - exp(-dt * 11f)
        for (b in trayBeads) moveBead(b, k, dt)
        val it = flyers.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (moveBead(b, k, dt)) {
                incoming[b.dest] = false
                it.remove()
                if (rng.nextInt(4) == 0) spawnSpark(b.x, b.y, level.palette[b.color])
            }
        }
        val sparkIt = sparks.iterator()
        while (sparkIt.hasNext()) {
            val s = sparkIt.next()
            s.life -= dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.vy += 500 * dp * dt
            if (s.life <= 0) sparkIt.remove()
        }

        if (state == State.PLAYING && flyers.isEmpty() && game.isWon) {
            state = State.WON
            stateTime = 0f
            levelNo++
            prefs.edit().putInt("level", levelNo).apply()
            repeat(50) {
                spawnSpark(
                    boardRect.left + rng.nextFloat() * boardRect.width(),
                    boardRect.top + rng.nextFloat() * boardRect.height(),
                    LevelGenerator.PALETTE[rng.nextInt(LevelGenerator.PALETTE.size)],
                )
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val animating: Boolean get() = flyers.isNotEmpty() || trayBeads.any { !it.arrived }

    private val showHint: Boolean get() = levelNo == 1 && game.moves == 0 && state == State.PLAYING

    private val needsFrame: Boolean
        get() = animating || sparks.isNotEmpty() || shakeTime > 0 || messageTime > 0 ||
            state != State.PLAYING || showHint || selectedColor >= 0

    // ---------------------------------------------------------------- 入力

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y
        when (state) {
            State.PLAYING -> {
                if (restartRect.contains(x, y)) {
                    startLevel()
                    return true
                }
                when {
                    boardRect.contains(x, y) -> onTapBoard(x, y)
                    trayRect.contains(x, y) -> onTapTray(x, y)
                    else -> clearSelection()
                }
            }
            State.WON -> {
                if (stateTime > 1.2f && overlayButton.contains(x, y)) startLevel()
            }
        }
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- レイアウト

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = bars.top.toFloat()
            insetBottom = bars.bottom.toFloat()
        } else {
            insetTop = insets.systemWindowInsetTop.toFloat()
            insetBottom = insets.systemWindowInsetBottom.toFloat()
        }
        if (width > 0) layoutViews(width, height)
        invalidate()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutViews(w, h)
    }

    private fun layoutViews(w: Int, h: Int) {
        val pad = 16 * dp
        val headerH = 64 * dp
        val top = insetTop + pad
        val bottom = h - insetBottom - pad

        restartRect.set(w - pad - 44 * dp, top + 6 * dp, w - pad, top + 50 * dp)
        titleY = top + headerH * 0.62f

        val trayW = min(w - 2 * pad, 560 * dp)
        val inner = 12 * dp
        slot = (trayW - 2 * inner) / TRAY_COLS
        val trayH = slot * TRAY_ROWS + 2 * inner

        val gap = 20 * dp
        val avail = bottom - (top + headerH) - trayH - gap * 2
        val boardSize = min(w - 2 * pad, avail).coerceAtLeast(100 * dp)
        cell = boardSize / game.n
        val boardLeft = (w - boardSize) / 2
        val boardTop = top + headerH + gap / 2 + max(0f, (avail - boardSize) / 2)
        boardRect.set(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize)

        val trayLeft = (w - trayW) / 2
        val trayTop = boardRect.bottom + gap * 1.5f
        trayRect.set(trayLeft, trayTop, trayLeft + trayW, trayTop + trayH)
        slotLeft = trayLeft + inner
        slotTop = trayTop + inner

        val cw = min(w - 2 * pad, 340 * dp)
        overlayCard.set((w - cw) / 2, h / 2f - 130 * dp, (w + cw) / 2, h / 2f + 130 * dp)
        overlayButton.set(
            overlayCard.centerX() - 100 * dp, overlayCard.bottom - 80 * dp,
            overlayCard.centerX() + 100 * dp, overlayCard.bottom - 24 * dp,
        )

        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFE9DDFB.toInt(), 0xFFD9D4F7.toInt(), Shader.TileMode.CLAMP)

        val size = max(cell, slot).toInt().coerceAtLeast(8)
        if (size != beadBitmapSize) {
            beadBitmapSize = size
            beadCache.clear()
        }
        // 画面サイズが変わったら移動中のものは即座に到着させる
        for (b in flyers) incoming[b.dest] = false
        flyers.clear()
        for (b in trayBeads) b.arrived = false
        relayoutTray()
        for (b in trayBeads) {
            b.x = b.tx
            b.y = b.ty
            b.arrived = true
        }
    }

    // ---------------------------------------------------------------- 描画

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        update(dt)

        paint.shader = bgShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        drawHeader(canvas)
        drawBoard(canvas)
        drawTray(canvas)
        drawMovingBeads(canvas)
        drawSparks(canvas)
        if (showHint) drawHint(canvas)
        if (messageTime > 0 && message.isNotEmpty()) drawMessage(canvas)
        if (state == State.WON) drawOverlay(canvas)

        if (needsFrame) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawHeader(canvas: Canvas) {
        val pad = 16 * dp
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = 0xFF4A3B7A.toInt()
        textPaint.textSize = 26 * dp
        canvas.drawText("LEVEL $levelNo", pad, titleY, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18 * dp
        textPaint.color = 0xFF6A5A8A.toInt()
        canvas.drawText("手数 ${game.moves}", restartRect.left - 14 * dp, titleY - 2 * dp, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // 進捗バー(正しい位置にあるジュエルの割合)
        val total = game.n * game.n
        val done = game.correctCount
        val barTop = titleY + 12 * dp
        tmpRect.set(pad, barTop, width - pad, barTop + 6 * dp)
        paint.color = 0x33000000
        canvas.drawRoundRect(tmpRect, 3 * dp, 3 * dp, paint)
        tmpRect.right = tmpRect.left + tmpRect.width() * done / total
        paint.color = 0xFF8E5CF0.toInt()
        canvas.drawRoundRect(tmpRect, 3 * dp, 3 * dp, paint)

        // リスタートボタン
        paint.color = Color.WHITE
        canvas.drawOval(restartRect, paint)
        textPaint.color = 0xFF6A4BC4.toInt()
        textPaint.textSize = 26 * dp
        canvas.drawText("↻", restartRect.centerX(), restartRect.centerY() + 9 * dp, textPaint)
    }

    private fun drawBoard(canvas: Canvas) {
        val n = game.n
        paint.color = 0xFF3A3F4B.toInt()
        tmpRect.set(boardRect.left - 3 * dp, boardRect.top - 3 * dp, boardRect.right + 3 * dp, boardRect.bottom + 3 * dp)
        canvas.drawRoundRect(tmpRect, 6 * dp, 6 * dp, paint)

        // 下地: 各マスの正解の色
        for (i in 0 until n * n) {
            val l = boardRect.left + (i % n) * cell
            val t = boardRect.top + (i / n) * cell
            paint.color = shade(level.palette[level.target[i]], 0.78f)
            canvas.drawRect(l, t, l + cell, t + cell, paint)
        }

        val shaking = shakeTime > 0
        val ox = if (shaking) sin(shakeTime * 60f) * 3 * dp else 0f
        val selColor = selectedColor
        val selected = BooleanArray(n * n)
        for (c in selGroup) selected[c] = true
        val pulse = 0.5f + 0.5f * sin(time * 6f)
        for (i in 0 until n * n) {
            val cx = cellX(i)
            val cy = cellY(i)
            val j = game.jewel[i]
            if (j < 0 || incoming[i]) {
                // 空きマス(選んでいる色の正解マスは点滅させる)
                paint.color = shade(level.palette[level.target[i]], 0.45f)
                canvas.drawCircle(cx, cy, cell * 0.3f, paint)
                if (selColor >= 0 && !incoming[i] && level.target[i] == selColor) {
                    paint.color = Color.WHITE
                    paint.alpha = (90 + 120 * pulse).toInt()
                    canvas.drawCircle(cx, cy, cell * 0.18f, paint)
                    paint.alpha = 255
                }
                continue
            }
            val dx = if (shaking && i in shakeGroup) ox else 0f
            if (selected[i]) {
                paint.color = Color.WHITE
                paint.alpha = (140 + 100 * pulse).toInt()
                canvas.drawCircle(cx, cy - cell * 0.08f, cell * 0.5f, paint)
                paint.alpha = 255
                drawBead(canvas, j, cx + dx, cy - cell * 0.08f, boardBead * 1.08f)
            } else {
                drawBead(canvas, j, cx + dx, cy, boardBead)
            }
        }
    }

    private val selectedColor: Int
        get() = when {
            selGroup.isNotEmpty() -> game.jewel[selGroup[0]]
            else -> selTrayColor
        }

    private fun drawTray(canvas: Canvas) {
        paint.color = 0x33000000
        tmpRect.set(trayRect)
        tmpRect.offset(0f, 5 * dp)
        canvas.drawRoundRect(tmpRect, 18 * dp, 18 * dp, paint)
        paint.color = 0xFFFFF1E4.toInt()
        canvas.drawRoundRect(trayRect, 18 * dp, 18 * dp, paint)
        if (selGroup.isNotEmpty()) {
            // 盤面のジュエルを選んでいる間は、トレイにも置けることを示す
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * dp
            paint.color = 0xFF8E5CF0.toInt()
            paint.alpha = (120 + 120 * (0.5f + 0.5f * sin(time * 6f))).toInt()
            canvas.drawRoundRect(trayRect, 18 * dp, 18 * dp, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        val full = game.trayTotal
        for (i in 0 until game.capacity) {
            val col = i % TRAY_COLS
            val row = i / TRAY_COLS
            val cx = slotLeft + (col + 0.5f) * slot
            val cy = slotTop + (row + 0.5f) * slot
            val r = slot * 0.42f
            tmpRect.set(cx - r, cy - r, cx + r, cy + r)
            paint.color = if (full >= game.capacity - 5) 0xFFF2B0A0.toInt() else 0xFFF2C79C.toInt()
            canvas.drawRoundRect(tmpRect, r * 0.45f, r * 0.45f, paint)
            tmpRect.inset(r * 0.12f, r * 0.12f)
            tmpRect.offset(0f, r * 0.06f)
            paint.color = 0x14000000
            canvas.drawRoundRect(tmpRect, r * 0.4f, r * 0.4f, paint)
        }
        for (b in trayBeads) {
            if (!b.arrived) continue
            if (b.color == selTrayColor) {
                paint.color = Color.WHITE
                paint.alpha = (140 + 100 * (0.5f + 0.5f * sin(time * 6f))).toInt()
                canvas.drawCircle(b.x, b.y - slot * 0.08f, slot * 0.5f, paint)
                paint.alpha = 255
                drawBead(canvas, b.color, b.x, b.y - slot * 0.08f, trayBead * 1.08f)
            } else {
                drawBead(canvas, b.color, b.x, b.y, trayBead)
            }
        }

        textPaint.textSize = 12 * dp
        textPaint.color = 0xFF9A7B5C.toInt()
        canvas.drawText(
            "トレイ $full / ${game.capacity}   選んで → 空きマスかトレイをタップ",
            trayRect.centerX(), trayRect.bottom + 18 * dp, textPaint,
        )
    }

    private fun drawMovingBeads(canvas: Canvas) {
        for (list in listOf(trayBeads, flyers)) {
            for (b in list) {
                if (b.arrived) continue
                val frac = (hypot(b.tx - b.x, b.ty - b.y) / b.startDist).coerceIn(0f, 1f)
                val toSize = if (b.dest >= 0) boardBead else trayBead
                val size = toSize + (b.fromSize - toSize) * frac
                // 飛んでいる途中は少し大きく見せる
                val lift = 1f + 0.25f * sin(frac * PI.toFloat())
                drawBead(canvas, b.color, b.x, b.y, size * lift)
            }
        }
    }

    private fun drawSparks(canvas: Canvas) {
        for (s in sparks) {
            paint.color = s.color
            paint.alpha = (255 * s.life).toInt().coerceIn(0, 255)
            drawStar(canvas, s.x, s.y, 5 * dp)
        }
        paint.alpha = 255
    }

    private fun drawHint(canvas: Canvas) {
        // 1: 間違ったジュエルを指す / 2: 選んだら、その色の空きマスかトレイを指す
        val sc = selectedColor
        val cx: Float
        val cy: Float
        if (sc < 0) {
            val c = (0 until game.n * game.n).firstOrNull { game.jewel[it] >= 0 && !game.isCorrect(it) } ?: return
            cx = cellX(c)
            cy = cellY(c)
        } else {
            val e = (0 until game.n * game.n).firstOrNull { game.jewel[it] == -1 && level.target[it] == sc }
            if (e != null) {
                cx = cellX(e)
                cy = cellY(e)
            } else {
                cx = slotLeft + slot / 2
                cy = slotTop + slot / 2
            }
        }
        val pulse = time % 1f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * dp
        paint.color = Color.WHITE
        paint.alpha = (255 * (1 - pulse)).toInt()
        canvas.drawCircle(cx, cy, cell * (0.6f + pulse * 1.6f), paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        textPaint.textSize = 42 * dp
        val bob = sin(time * 6f) * 6 * dp
        canvas.drawText("👆", cx + 14 * dp, cy + 46 * dp + bob, textPaint)
    }

    private fun drawMessage(canvas: Canvas) {
        textPaint.textSize = 18 * dp
        val w = textPaint.measureText(message) + 32 * dp
        val cy = boardRect.bottom + (trayRect.top - boardRect.bottom) / 2
        tmpRect.set(width / 2f - w / 2, cy - 18 * dp, width / 2f + w / 2, cy + 18 * dp)
        paint.color = 0xDD2B2140.toInt()
        canvas.drawRoundRect(tmpRect, 18 * dp, 18 * dp, paint)
        textPaint.color = Color.WHITE
        canvas.drawText(message, width / 2f, cy + 6 * dp, textPaint)
    }

    private fun drawOverlay(canvas: Canvas) {
        // 完成した絵を少し見せてから表示
        val b = ((stateTime - 0.9f) / 0.3f).coerceIn(0f, 1f)
        if (b <= 0f) return
        paint.color = Color.BLACK
        paint.alpha = (110 * b).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.alpha = 255

        canvas.save()
        val s = 0.8f + 0.2f * b
        canvas.scale(s, s, overlayCard.centerX(), overlayCard.centerY())
        paint.color = Color.WHITE
        canvas.drawRoundRect(overlayCard, 24 * dp, 24 * dp, paint)

        textPaint.color = 0xFF4A3B7A.toInt()
        textPaint.textSize = 30 * dp
        canvas.drawText("完成!", overlayCard.centerX(), overlayCard.top + 64 * dp, textPaint)
        textPaint.textSize = 16 * dp
        textPaint.color = 0xFF7A6A9A.toInt()
        canvas.drawText("LEVEL ${levelNo - 1} クリア  手数 ${game.moves}", overlayCard.centerX(), overlayCard.top + 104 * dp, textPaint)

        paint.shader = LinearGradient(0f, overlayButton.top, 0f, overlayButton.bottom, 0xFF8BE04A.toInt(), 0xFF3FA52A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(overlayButton, 28 * dp, 28 * dp, paint)
        paint.shader = null
        textPaint.color = Color.WHITE
        textPaint.textSize = 22 * dp
        canvas.drawText("次のレベル", overlayButton.centerX(), overlayButton.centerY() + 8 * dp, textPaint)
        canvas.restore()
    }

    private fun drawBead(canvas: Canvas, colorIndex: Int, cx: Float, cy: Float, size: Float) {
        val bmp = beadCache.getOrPut(colorIndex) { makeBead(level.palette[colorIndex], beadBitmapSize.coerceAtLeast(8)) }
        val h = size / 2
        tmpRect.set(cx - h, cy - h, cx + h, cy + h)
        canvas.drawBitmap(bmp, null, tmpRect, bmpPaint)
    }

    private val starPath = Path()
    private fun drawStar(canvas: Canvas, x: Float, y: Float, r: Float) {
        starPath.reset()
        for (i in 0 until 8) {
            val rr = if (i % 2 == 0) r else r * 0.4f
            val a = i * PI.toFloat() / 4
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }

    private fun makeBead(color: Int, size: Int): Bitmap {
        val s = size * 2 // 拡大描画でもぼやけないよう2倍で作る
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = s / 2f
        val r = s / 2f * 0.94f

        p.color = shade(color, 0.5f)
        c.drawCircle(cx, cx + s * 0.03f, r, p)

        p.shader = RadialGradient(
            cx - r * 0.3f, cx - r * 0.35f, r * 1.4f,
            intArrayOf(tint(color, 0.45f), color, shade(color, 0.75f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cx - s * 0.01f, r * 0.9f, p)
        p.shader = null

        // カット面(八角形)
        val oct = Path()
        val ro = r * 0.55f
        for (i in 0 until 8) {
            val a = PI.toFloat() / 8 + i * PI.toFloat() / 4
            val px = cx + cos(a) * ro
            val py = cx - s * 0.02f + sin(a) * ro
            if (i == 0) oct.moveTo(px, py) else oct.lineTo(px, py)
        }
        oct.close()
        p.color = tint(color, 0.18f)
        p.alpha = 150
        c.drawPath(oct, p)

        // ハイライト
        p.color = Color.WHITE
        p.alpha = 190
        c.drawOval(cx - r * 0.55f, cx - r * 0.62f, cx - r * 0.1f, cx - r * 0.35f, p)
        return bmp
    }

    companion object {
        private const val TRAY_COLS = 13
        private const val TRAY_ROWS = 5

        fun shade(c: Int, f: Float) = Color.argb(
            Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt(),
        )

        fun tint(c: Int, f: Float) = Color.argb(
            Color.alpha(c),
            (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
        )
    }
}
