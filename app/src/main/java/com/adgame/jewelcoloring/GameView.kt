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

    private enum class State { PLAYING, WON, LOST }

    private class TrayBead(val color: Int, var x: Float, var y: Float, var delay: Float) {
        var tx = 0f
        var ty = 0f
        var startDist = 1f
        var arrived = false
    }

    private class Pop(val color: Int, val x: Float, val y: Float, var t: Float = 0f)
    private class Spark(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Int, var life: Float)

    private val prefs = context.getSharedPreferences("jewel_coloring", Context.MODE_PRIVATE)
    private var levelNo = prefs.getInt("level", 1)
    private var score = prefs.getInt("score", 0)
    private var levelStartScore = score

    private lateinit var level: Level
    private lateinit var game: Game
    private var state = State.PLAYING
    private var stateTime = 0f

    private val trayBeads = ArrayList<TrayBead>()
    private val pops = ArrayList<Pop>()
    private val sparks = ArrayList<Spark>()
    private val rng = Random(System.nanoTime())
    private var pendingResolve = false

    private var shakePiece = -1
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

    init {
        startLevel()
    }

    // ---------------------------------------------------------------- 進行

    private fun startLevel() {
        level = LevelGenerator.generate(levelNo)
        game = Game(level, capacity = LevelGenerator.capacityFor(levelNo), seed = levelNo * 31L + 7)
        trayBeads.clear()
        pops.clear()
        sparks.clear()
        pendingResolve = false
        shakePiece = -1
        message = ""
        state = State.PLAYING
        stateTime = 0f
        levelStartScore = score
        beadCache.clear()
        if (width > 0) layoutViews(width, height)
        invalidate()
    }

    private fun save() {
        prefs.edit().putInt("level", levelNo).putInt("score", score).apply()
    }

    private fun onTapBoard(x: Float, y: Float) {
        val cx = ((x - boardRect.left) / cell).toInt()
        val cy = ((y - boardRect.top) / cell).toInt()
        if (x < boardRect.left || y < boardRect.top) return
        val piece = game.pieceAtCell(cx, cy)
        if (piece < 0) return
        if (!game.canTake(piece)) {
            shakePiece = piece
            shakeTime = 0.4f
            showMessage("トレイに入りきりません")
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        val cells = game.take(piece)
        // タップした位置に近い順に流れていく
        val sorted = cells.sortedBy { c ->
            val px = boardRect.left + (c % game.n + 0.5f) * cell
            val py = boardRect.top + (c / game.n + 0.5f) * cell
            hypot(px - x, py - y)
        }
        val color = level.cells[cells[0]]
        sorted.forEachIndexed { i, c ->
            val px = boardRect.left + (c % game.n + 0.5f) * cell
            val py = boardRect.top + (c / game.n + 0.5f) * cell
            trayBeads.add(TrayBead(color, px, py, i * 0.03f))
        }
        relayoutTray()
        for (b in trayBeads) if (!b.arrived) b.startDist = max(1f, hypot(b.tx - b.x, b.ty - b.y))
        pendingResolve = true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** トレイ内の宝石を色ごとにまとめて並べ直す。 */
    private fun relayoutTray() {
        var index = 0
        for (color in game.trayOrder) {
            for (b in trayBeads) {
                if (b.color != color) continue
                val col = index % TRAY_COLS
                val row = index / TRAY_COLS
                b.tx = slotLeft + (col + 0.5f) * slot
                b.ty = slotTop + (row + 0.5f) * slot
                if (b.arrived && (b.tx != b.x || b.ty != b.y)) {
                    b.arrived = false
                    b.startDist = max(1f, hypot(b.tx - b.x, b.ty - b.y))
                }
                index++
            }
        }
    }

    private fun resolve() {
        pendingResolve = false
        val clears = game.resolveClears()
        if (clears.isEmpty()) {
            checkEnd()
            return
        }
        var gained = 0
        for ((color, count) in clears) {
            var left = count
            for (i in trayBeads.indices.reversed()) {
                if (left == 0) break
                val b = trayBeads[i]
                if (b.color != color) continue
                pops.add(Pop(color, b.x, b.y))
                repeat(3) { spawnSpark(b.x, b.y, level.palette[color]) }
                trayBeads.removeAt(i)
                left--
            }
            gained += count * 10
        }
        if (clears.size > 1) {
            gained += clears.size * 50
            showMessage("コンボ ×${clears.size}!")
        }
        score += gained
        relayoutTray()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun checkEnd() {
        if (game.isWon) {
            state = State.WON
            stateTime = 0f
            levelNo++
            save()
            for (i in 0 until 40) {
                spawnSpark(
                    boardRect.left + rng.nextFloat() * boardRect.width(),
                    boardRect.top + rng.nextFloat() * boardRect.height(),
                    LevelGenerator.PALETTE[rng.nextInt(LevelGenerator.PALETTE.size)],
                )
            }
        } else if (game.isStuck()) {
            state = State.LOST
            stateTime = 0f
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

    private val busy: Boolean get() = pendingResolve || pops.isNotEmpty() || trayBeads.any { !it.arrived }

    // ---------------------------------------------------------------- 更新

    private fun update(dt: Float) {
        time += dt
        stateTime += dt
        if (shakeTime > 0) shakeTime -= dt
        if (messageTime > 0) messageTime -= dt

        val k = 1f - exp(-dt * 11f)
        for (b in trayBeads) {
            if (b.arrived) continue
            if (b.delay > 0) {
                b.delay -= dt
                continue
            }
            b.x += (b.tx - b.x) * k
            b.y += (b.ty - b.y) * k
            if (hypot(b.tx - b.x, b.ty - b.y) < 0.8f * dp) {
                b.x = b.tx
                b.y = b.ty
                b.arrived = true
            }
        }
        val popIt = pops.iterator()
        while (popIt.hasNext()) {
            val p = popIt.next()
            p.t += dt
            if (p.t >= POP_TIME) popIt.remove()
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

        if (state == State.PLAYING) {
            if (pendingResolve && trayBeads.all { it.arrived }) {
                resolve()
            } else if (!pendingResolve && pops.isEmpty() && trayBeads.all { it.arrived }) {
                // 消去アニメーション後に、新たにそろった色が無いか確認する
                if (game.trayOrder.any { game.trayCount[it] >= game.setSize || game.remaining[it] == 0 }) {
                    pendingResolve = true
                } else if (game.isWon || game.isStuck()) {
                    checkEnd()
                }
            }
        }
    }

    private val needsFrame: Boolean
        get() = busy || sparks.isNotEmpty() || shakeTime > 0 || messageTime > 0 ||
            state != State.PLAYING || showHint

    private val showHint: Boolean get() = levelNo == 1 && game.takenPieces == 0 && state == State.PLAYING

    // ---------------------------------------------------------------- 入力

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y
        when (state) {
            State.PLAYING -> {
                if (restartRect.contains(x, y)) {
                    score = levelStartScore
                    startLevel()
                    return true
                }
                if (!busy && boardRect.contains(x, y)) onTapBoard(x, y)
            }
            State.WON, State.LOST -> {
                if (stateTime > 0.6f && overlayButton.contains(x, y)) {
                    if (state == State.LOST) score = levelStartScore
                    startLevel()
                }
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
        // 移動中の宝石の目的地を更新
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
        drawEffects(canvas)
        if (showHint) drawHint(canvas)
        if (messageTime > 0 && message.isNotEmpty()) drawMessage(canvas)
        if (state != State.PLAYING) drawOverlay(canvas)

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
        textPaint.color = 0xFF7A5C00.toInt()
        canvas.drawText("★ $score", restartRect.left - 14 * dp, titleY - 2 * dp, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // 進捗バー
        val total = level.cells.size
        val left = game.remaining.sum()
        val barTop = titleY + 12 * dp
        tmpRect.set(pad, barTop, width - pad, barTop + 6 * dp)
        paint.color = 0x33000000
        canvas.drawRoundRect(tmpRect, 3 * dp, 3 * dp, paint)
        tmpRect.right = tmpRect.left + tmpRect.width() * (total - left) / total
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

        val won = state == State.WON
        val reveal = if (won) (stateTime / 0.8f).coerceIn(0f, 1f) else 0f

        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            val alive = game.colorAt[i] >= 0
            val color = level.palette[level.cells[i]]
            var ox = 0f
            if (alive && game.pieceOf[i] == shakePiece && shakeTime > 0) ox = sin(shakeTime * 60f) * 3 * dp
            val l = boardRect.left + x * cell + ox
            val t = boardRect.top + y * cell
            if (alive || reveal > 0f) {
                // ピースの下地(ピースの境目は少し隙間を空ける)
                paint.color = shade(color, 0.55f)
                if (!alive) paint.alpha = (255 * reveal).toInt()
                val p = game.pieceOf[i]
                val gl = if (x > 0 && game.pieceOf[i - 1] != p) 1 * dp else 0f
                val gt = if (y > 0 && game.pieceOf[i - n] != p) 1 * dp else 0f
                canvas.drawRect(l + gl, t + gt, l + cell, t + cell, paint)
                paint.alpha = 255
                if (!alive) bmpPaint.alpha = (255 * reveal).toInt()
                drawBead(canvas, level.cells[i], l + cell / 2, t + cell / 2, cell * 0.92f)
                bmpPaint.alpha = 255
            } else {
                // 回収済みのマスはうっすら色だけ残す
                paint.color = color
                paint.alpha = 45
                canvas.drawCircle(l + cell / 2, t + cell / 2, cell * 0.22f, paint)
                paint.alpha = 255
            }
        }
    }

    private fun drawTray(canvas: Canvas) {
        paint.color = 0x33000000
        tmpRect.set(trayRect)
        tmpRect.offset(0f, 5 * dp)
        canvas.drawRoundRect(tmpRect, 18 * dp, 18 * dp, paint)
        paint.color = 0xFFFFF1E4.toInt()
        canvas.drawRoundRect(trayRect, 18 * dp, 18 * dp, paint)

        val full = game.trayTotal
        for (i in 0 until TRAY_COLS * TRAY_ROWS) {
            val col = i % TRAY_COLS
            val row = i / TRAY_COLS
            val cx = slotLeft + (col + 0.5f) * slot
            val cy = slotTop + (row + 0.5f) * slot
            val r = slot * 0.42f
            tmpRect.set(cx - r, cy - r, cx + r, cy + r)
            if (i >= game.capacity) {
                // ロックされた枠
                paint.color = 0xFFD9CFC6.toInt()
                canvas.drawRoundRect(tmpRect, r * 0.45f, r * 0.45f, paint)
                paint.color = 0xFFB5A89C.toInt()
                canvas.drawRect(cx - r * 0.3f, cy - r * 0.05f, cx + r * 0.3f, cy + r * 0.4f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.12f
                canvas.drawArc(cx - r * 0.2f, cy - r * 0.4f, cx + r * 0.2f, cy + r * 0.05f, 180f, 180f, false, paint)
                paint.style = Paint.Style.FILL
                continue
            }
            paint.color = if (i >= game.capacity - 5 && full >= game.capacity - 5) 0xFFF2A0A0.toInt() else 0xFFF2C79C.toInt()
            canvas.drawRoundRect(tmpRect, r * 0.45f, r * 0.45f, paint)
            tmpRect.inset(r * 0.12f, r * 0.12f)
            tmpRect.offset(0f, r * 0.06f)
            paint.color = 0x14000000
            canvas.drawRoundRect(tmpRect, r * 0.4f, r * 0.4f, paint)
        }
        for (b in trayBeads) if (b.arrived) drawBead(canvas, b.color, b.x, b.y, slot * 0.86f)

        textPaint.textSize = 12 * dp
        textPaint.color = 0xFF9A7B5C.toInt()
        canvas.drawText("$full / ${game.capacity}   同じ色${game.setSize}個で消える", trayRect.centerX(), trayRect.bottom + 18 * dp, textPaint)
    }

    private fun drawMovingBeads(canvas: Canvas) {
        for (b in trayBeads) {
            if (b.arrived) continue
            val frac = (hypot(b.tx - b.x, b.ty - b.y) / b.startDist).coerceIn(0f, 1f)
            val size = slot * 0.86f + (cell * 0.92f - slot * 0.86f) * frac
            // 飛んでいる途中は少し大きく見せる
            val lift = 1f + 0.25f * sin(frac * PI.toFloat())
            drawBead(canvas, b.color, b.x, b.y, size * lift)
        }
    }

    private fun drawEffects(canvas: Canvas) {
        for (p in pops) {
            val f = p.t / POP_TIME
            bmpPaint.alpha = (255 * (1f - f)).toInt().coerceIn(0, 255)
            drawBead(canvas, p.color, p.x, p.y, slot * 0.86f * (1f + f * 0.8f))
            bmpPaint.alpha = 255
        }
        for (s in sparks) {
            paint.color = s.color
            paint.alpha = (255 * (s.life / 1f)).toInt().coerceIn(0, 255)
            drawStar(canvas, s.x, s.y, 5 * dp)
        }
        paint.alpha = 255
    }

    private fun drawHint(canvas: Canvas) {
        val p = game.pieceAtCell(1, 1).takeIf { it >= 0 } ?: return
        val cells = game.pieces[p]
        var sx = 0f
        var sy = 0f
        for (c in cells) {
            sx += c % game.n
            sy += c / game.n
        }
        val cx = boardRect.left + (sx / cells.size + 0.5f) * cell
        val cy = boardRect.top + (sy / cells.size + 0.5f) * cell
        val pulse = (time % 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * dp
        paint.color = Color.WHITE
        paint.alpha = (255 * (1 - pulse)).toInt()
        canvas.drawCircle(cx, cy, cell * (0.8f + pulse * 1.6f), paint)
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
        val a = (stateTime / 0.4f).coerceIn(0f, 1f)
        // クリア時は完成した絵をしばらく見せてから表示
        val delay = if (state == State.WON) 1.0f else 0f
        val b = ((stateTime - delay) / 0.3f).coerceIn(0f, 1f)
        if (state == State.LOST) {
            paint.color = Color.BLACK
            paint.alpha = (140 * a).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
        if (b <= 0f) return
        paint.color = Color.BLACK
        paint.alpha = (110 * b).toInt()
        if (state == State.WON) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.alpha = 255

        canvas.save()
        val s = 0.8f + 0.2f * b
        canvas.scale(s, s, overlayCard.centerX(), overlayCard.centerY())
        paint.color = Color.WHITE
        canvas.drawRoundRect(overlayCard, 24 * dp, 24 * dp, paint)

        textPaint.color = 0xFF4A3B7A.toInt()
        textPaint.textSize = 30 * dp
        val title = if (state == State.WON) "完成!" else "トレイがいっぱい…"
        canvas.drawText(title, overlayCard.centerX(), overlayCard.top + 64 * dp, textPaint)
        textPaint.textSize = 16 * dp
        textPaint.color = 0xFF7A6A9A.toInt()
        val sub = if (state == State.WON) "LEVEL ${levelNo - 1} クリア  ★ $score" else "置ける場所がなくなりました"
        canvas.drawText(sub, overlayCard.centerX(), overlayCard.top + 104 * dp, textPaint)

        paint.shader = LinearGradient(0f, overlayButton.top, 0f, overlayButton.bottom, 0xFF8BE04A.toInt(), 0xFF3FA52A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(overlayButton, 28 * dp, 28 * dp, paint)
        paint.shader = null
        textPaint.color = Color.WHITE
        textPaint.textSize = 22 * dp
        val label = if (state == State.WON) "次のレベル" else "リトライ"
        canvas.drawText(label, overlayButton.centerX(), overlayButton.centerY() + 8 * dp, textPaint)
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
        private const val POP_TIME = 0.35f

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
