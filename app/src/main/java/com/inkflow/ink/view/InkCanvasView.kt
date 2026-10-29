package com.inkflow.ink.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.inkflow.core.model.EraserMode
import com.inkflow.core.model.InkKind
import com.inkflow.core.model.PageSize
import com.inkflow.core.model.PageTemplate
import com.inkflow.core.model.Stroke
import com.inkflow.core.model.StrokeChange
import com.inkflow.core.model.Tool
import com.inkflow.core.util.FloatList
import com.inkflow.ink.edit.PageDocument
import com.inkflow.ink.geometry.Geometry
import com.inkflow.ink.input.OneEuroFilter
import com.inkflow.ink.render.StrokePainter
import com.inkflow.ink.render.TemplateRenderer
import com.inkflow.pdf.PdfSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Superfície de escrita. View nativa (não Compose) para ter controle total sobre eventos de
 * toque/stylus e sobre o custo de cada quadro.
 *
 * ESTRATÉGIA DE RENDERIZAÇÃO
 *  - Camada estática (papel, modelo, PDF e traços já confirmados) é renderizada numa thread
 *    própria, num bitmap do tamanho da tela, só da região visível ("viewport cache").
 *    Dois bitmaps (front/back) se alternam: a thread desenha no back e a UI troca quando pronto.
 *  - Ao confirmar um traço de tinta, ele é "assado" direto no bitmap da frente (custo de um
 *    único traço) — a página inteira NÃO é redesenhada a cada traço.
 *  - Durante zoom/rolagem a UI só transforma o bitmap existente (barato) e pede uma nova
 *    renderização vetorial nítida ao final.
 *  - O traço em andamento é desenhado por cima, incrementalmente, a cada evento.
 *  - O PDF é renderizado numa camada separada e só é refeito quando zoom/posição mudam.
 *
 * ENTRADA
 *  - requestUnbufferedDispatch(): recebe eventos na taxa do digitalizador, sem esperar o vsync.
 *  - Usa os pontos históricos de cada MotionEvent (nenhuma amostra é descartada).
 *  - Filtro 1€ para suavizar sem atraso perceptível + predição curta desenhada só no quadro atual.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSelectionChanged(count: Int) {}
        fun onStylusDetected() {}
        fun onUndoGesture() {}
        fun onRedoGesture() {}
        fun onZoomChanged(relativeZoom: Float) {}
    }

    var listener: Listener? = null

    var toolConfig: ToolConfig = ToolConfig()
        set(value) {
            val oldTool = field.tool
            field = value
            if (oldTool == Tool.LASSO && value.tool != Tool.LASSO) clearSelection()
            invalidate()
        }

    var inputConfig: InputConfig = InputConfig()
        set(value) {
            field = value
            applyFilterParams()
        }

    /** Cor ao redor da folha (acompanha o tema). */
    var surroundColor: Int = 0xFFE7E5E0.toInt()
        set(value) {
            if (field != value) {
                field = value
                requestRender()
                invalidate()
            }
        }

    // ---------------------------------------------------------------- página
    private var document: PageDocument? = null
    val currentDocument: PageDocument? get() = document
    private var template = PageTemplate.BLANK
    private var pdf: PdfSource? = null
    private var pdfIndex = -1
    private var pageW = PageSize.A4_W
    private var pageH = PageSize.A4_H
    private var pageEpoch = 0

    // ---------------------------------------------------------------- transformação
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var baseScale = 1f
    private val density = resources.displayMetrics.density
    private val xdpi = resources.displayMetrics.xdpi
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val margin get() = 16f * density

    // ---------------------------------------------------------------- renderização
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InkFlow-Render").apply { priority = Thread.NORM_PRIORITY }
    }
    private val renderPainter = StrokePainter() // só na thread de renderização
    private val renderTemplate = TemplateRenderer() // só na thread de renderização
    private val renderPaper = Paint().apply { color = Color.WHITE }
    private val renderShadow = Paint().apply { color = 0x1F000000 }
    private var pdfLayer: Bitmap? = null // só na thread de renderização
    private var pdfLayerKey: String? = null // só na thread de renderização

    private val uiPainter = StrokePainter()
    private val uiTemplate = TemplateRenderer()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var frontValid = false
    private var frontScale = 0f
    private var frontTx = 0f
    private var frontTy = 0f
    private var frontSeq = -1L
    private var renderInFlight = false
    private var renderPending = false
    private var lastGestureRender = 0L
    private var released = false
    private val hiddenIds = HashSet<String>()

    private val drawMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xAA5A6270.toInt()
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x225A6270
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF3B6FD8.toInt()
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0xFF3B6FD8.toInt()
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val selectionFill = Paint().apply {
        style = Paint.Style.FILL
        color = 0x143B6FD8
    }

    // ---------------------------------------------------------------- estado de entrada
    private enum class Mode { IDLE, DRAW, ERASE, LASSO, MOVE_SELECTION, PAN, GESTURE, WAIT_UP }
    private enum class PKind { STYLUS, ERASER_TIP, FINGER, PALM }
    private enum class Role { DRAW, NAVIGATE, NONE }

    private var mode = Mode.IDLE
    private var activeId = -1
    private var tentative = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val ignored = HashSet<Int>()
    private var sessionStylus = false

    // gesto de dois dedos
    private var gA = -1
    private var gB = -1
    private var gLastDist = 0f
    private var gStartDist = 0f
    private var gLastCx = 0f
    private var gLastCy = 0f
    private var gStartCx = 0f
    private var gStartCy = 0f
    private var gMaxPointers = 0
    private var gMoved = false

    // traço ao vivo
    private var liveKind = InkKind.PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 1.6f
    private var liveUsePressure = false
    private var liveSpeedWidth = false
    private val livePoints = FloatList(512)
    private val livePath = Path()
    private val liveBounds = RectF()
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private var lastWidthFactor = 1f
    private var lastFilteredSx = 0f
    private var lastFilteredSy = 0f
    private var lastFilteredT = 0L
    private val hX = FloatArray(3)
    private val hY = FloatArray(3)
    private val hT = LongArray(3)
    private var hCount = 0
    private var hasPrediction = false
    private var predX = 0f
    private var predY = 0f

    // borracha
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private var eraseRemoved = LinkedHashMap<String, Stroke>()
    private var eraseAdded = LinkedHashMap<String, Stroke>()

    // laço e seleção
    private val lassoPts = FloatList(256)
    private var selection: List<Stroke> = emptyList()
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var moveDx = 0f
    private var moveDy = 0f

    val selectionCount: Int get() = selection.size

    init {
        contentDescription = "Área de escrita"
        applyFilterParams()
    }

    // ================================================================ API pública

    fun setPage(doc: PageDocument, tpl: PageTemplate, pdfSource: PdfSource?, pdfPageIndex: Int, widthPt: Float, heightPt: Float) {
        cancelAll()
        selection = emptyList()
        hiddenIds.clear()
        document = doc
        template = tpl
        pdf = pdfSource
        pdfIndex = pdfPageIndex
        pageW = if (widthPt > 1f) widthPt else PageSize.A4_W
        pageH = if (heightPt > 1f) heightPt else PageSize.A4_H
        pageEpoch++
        frontValid = false
        if (width > 0 && height > 0) fitWidth()
        requestRender()
        invalidate()
        listener?.onSelectionChanged(0)
    }

    fun updateTemplate(tpl: PageTemplate) {
        if (tpl == template) return
        template = tpl
        requestRender()
    }

    fun undo(): Boolean {
        val d = document ?: return false
        cancelActive()
        val ok = d.undo()
        if (ok) afterHistoryChange()
        return ok
    }

    fun redo(): Boolean {
        val d = document ?: return false
        cancelActive()
        val ok = d.redo()
        if (ok) afterHistoryChange()
        return ok
    }

    fun deleteSelection() {
        val d = document ?: return
        if (selection.isEmpty()) return
        d.apply(StrokeChange(selection, emptyList()))
        setSelection(emptyList())
        requestRender()
    }

    fun recolorSelection(color: Int) {
        val d = document ?: return
        if (selection.isEmpty()) return
        val re = selection.map { it.recolored(color) }
        d.apply(StrokeChange(selection, re))
        setSelection(re)
        requestRender()
    }

    fun duplicateSelection() {
        val d = document ?: return
        if (selection.isEmpty()) return
        val copies = selection.map { it.duplicate(18f, 18f, d.nextZ()) }
        d.apply(StrokeChange(emptyList(), copies))
        setSelection(copies)
        requestRender()
    }

    fun clearSelection() {
        if (selection.isNotEmpty()) setSelection(emptyList())
    }

    fun zoomBy(factor: Float) {
        zoomAround(width / 2f, height / 2f, factor)
        clampTransform()
        requestRender()
        invalidate()
    }

    fun fitWidth() {
        if (width <= 0) return
        baseScale = ((width - 2 * margin) / pageW).coerceAtLeast(0.05f)
        scale = baseScale
        tx = margin
        ty = margin
        clampTransform()
        listener?.onZoomChanged(1f)
    }

    /** Libera a thread de renderização. Chamar ao sair do editor. */
    fun release() {
        released = true
        renderExecutor.shutdown()
        document = null
        pdf = null
    }

    // ================================================================ tamanho e transformação

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        front = null
        back = null
        frontValid = false
        fitWidth()
        requestRender()
    }

    private fun zoomAround(fx: Float, fy: Float, factor: Float) {
        val newScale = (scale * factor).coerceIn(baseScale * 0.5f, baseScale * 10f)
        val real = newScale / scale
        tx = fx - (fx - tx) * real
        ty = fy - (fy - ty) * real
        scale = newScale
        listener?.onZoomChanged(scale / baseScale)
    }

    private fun clampTransform() {
        val pw = pageW * scale
        val ph = pageH * scale
        val m = margin
        tx = if (pw + 2 * m <= width) (width - pw) / 2f else tx.coerceIn(width - pw - m, m)
        ty = if (ph + 2 * m <= height) ty.coerceIn(m, height - ph - m) else ty.coerceIn(height - ph - m, m)
    }

    private fun toPageX(sx: Float) = (sx - tx) / scale
    private fun toPageY(sy: Float) = (sy - ty) / scale

    // ================================================================ renderização em segundo plano

    private fun requestRender() {
        if (released) return
        if (renderInFlight) {
            renderPending = true
            return
        }
        startRender()
    }

    private fun throttledRender() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGestureRender > 140) {
            lastGestureRender = now
            requestRender()
        }
    }

    private fun ensureBitmaps(w: Int, h: Int) {
        val f = front
        if (f == null || f.width != w || f.height != h) {
            front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frontValid = false
        }
        val b = back
        if (b == null || b.width != w || b.height != h) back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun startRender() {
        val doc = document ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || released) return
        ensureBitmaps(w, h)
        val target = back ?: return
        val strokes = doc.snapshot()
        val seqAtSnapshot = doc.currentSeq
        val hidden: Set<String> = if (hiddenIds.isEmpty()) emptySet() else HashSet(hiddenIds)
        val s = scale
        val x = tx
        val y = ty
        val epoch = pageEpoch
        val tpl = template
        val pw = pageW
        val ph = pageH
        val pdfSrc = pdf
        val pIdx = pdfIndex
        val surround = surroundColor
        renderInFlight = true
        renderPending = false
        try {
            renderExecutor.execute {
                try {
                    renderInto(target, strokes, hidden, s, x, y, tpl, pw, ph, pdfSrc, pIdx, surround)
                } catch (t: Throwable) {
                    Log.e(TAG, "Falha ao renderizar página", t)
                }
                post {
                    renderInFlight = false
                    if (!released && epoch == pageEpoch && back === target) {
                        val old = front
                        front = target
                        back = old
                        frontScale = s
                        frontTx = x
                        frontTy = y
                        frontSeq = seqAtSnapshot
                        frontValid = true
                        invalidate()
                    }
                    if (renderPending && !released) startRender()
                }
            }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            renderInFlight = false
        }
    }

    /** Executa na thread de renderização. */
    private fun renderInto(
        target: Bitmap, strokes: List<Stroke>, hidden: Set<String>,
        s: Float, x: Float, y: Float, tpl: PageTemplate, pw: Float, ph: Float,
        pdfSrc: PdfSource?, pIdx: Int, surround: Int,
    ) {
        target.eraseColor(surround)
        val c = Canvas(target)
        val right = x + pw * s
        val bottom = y + ph * s
        c.drawRect(x + 1f, y + 3f, right + 1f, bottom + 3f, renderShadow)
        c.drawRect(x, y, right, bottom, renderPaper)

        if (pdfSrc != null && pIdx >= 0) {
            var layer = pdfLayer
            if (layer == null || layer.width != target.width || layer.height != target.height) {
                layer = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                pdfLayer = layer
                pdfLayerKey = null
            }
            val key = "${pdfSrc.id}#$pIdx@$s,$x,$y"
            if (key != pdfLayerKey) {
                layer.eraseColor(Color.TRANSPARENT)
                val m = Matrix()
                m.setScale(s, s)
                m.postTranslate(x, y)
                pdfLayerKey = if (pdfSrc.render(pIdx, layer, m)) key else null
            }
            c.drawBitmap(layer, 0f, 0f, null)
        }

        c.save()
        c.translate(x, y)
        c.scale(s, s)
        c.clipRect(0f, 0f, pw, ph)
        renderTemplate.draw(c, tpl, pw, ph)
        val visible = RectF(-x / s, -y / s, (target.width - x) / s, (target.height - y) / s)
        renderPainter.drawAll(c, strokes, visible, hidden)
        c.restore()
    }

    /** Desenha um traço recém-confirmado direto no bitmap da frente, sem refazer a página. */
    private fun bakeOrRender(s: Stroke) {
        val f = front
        val canBake = f != null && frontValid && !renderInFlight && s.kind != InkKind.HIGHLIGHTER &&
            frontScale == scale && frontTx == tx && frontTy == ty && s.seq == frontSeq + 1 && hiddenIds.isEmpty()
        if (canBake && f != null) {
            val c = Canvas(f)
            c.translate(tx, ty)
            c.scale(scale, scale)
            c.clipRect(0f, 0f, pageW, pageH)
            uiPainter.draw(c, s)
            frontSeq = s.seq
        } else {
            requestRender()
        }
    }

    private fun afterHistoryChange() {
        val d = document
        if (selection.isNotEmpty() && d != null) setSelection(selection.mapNotNull { d.get(it.id) })
        requestRender()
        invalidate()
    }

    // ================================================================ desenho

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(surroundColor)
        val f = front
        if (f != null && frontValid) {
            if (frontScale == scale && frontTx == tx && frontTy == ty) {
                canvas.drawBitmap(f, 0f, 0f, null)
            } else {
                drawMatrix.reset()
                drawMatrix.postTranslate(-frontTx, -frontTy)
                drawMatrix.postScale(scale / frontScale, scale / frontScale)
                drawMatrix.postTranslate(tx, ty)
                canvas.drawBitmap(f, drawMatrix, bitmapPaint)
            }
        } else {
            canvas.drawRect(tx, ty, tx + pageW * scale, ty + pageH * scale, paperPaint)
        }

        val doc = document
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, pageW, pageH)
        if (doc != null) {
            if (!frontValid) {
                // Primeiro quadro, antes da renderização em segundo plano terminar.
                uiTemplate.draw(canvas, template, pageW, pageH)
                uiPainter.drawAll(canvas, doc.strokes, null, hiddenIds)
            } else {
                val strokes = doc.strokes
                for (i in strokes.indices) {
                    val s = strokes[i]
                    if (s.seq > frontSeq && (hiddenIds.isEmpty() || s.id !in hiddenIds)) uiPainter.draw(canvas, s)
                }
            }
        }
        if (mode == Mode.MOVE_SELECTION && selection.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx, moveDy)
            for (s in selection) uiPainter.draw(canvas, s)
            canvas.restore()
        }
        if (mode == Mode.DRAW) drawLive(canvas)
        canvas.restore()

        if (mode == Mode.DRAW && hasPrediction) {
            livePaint.color = liveColor
            livePaint.strokeWidth = liveWidth * lastWidthFactor * scale
            canvas.drawLine(lastFilteredSx, lastFilteredSy, predX, predY, livePaint)
        }
        if (mode == Mode.ERASE) {
            val r = eraserRadiusPx()
            canvas.drawCircle(lastX, lastY, r, cursorFill)
            canvas.drawCircle(lastX, lastY, r, cursorPaint)
        }
        if (mode == Mode.LASSO) drawLasso(canvas)
        if (selection.isNotEmpty()) {
            val r = selectionScreenRect()
            canvas.drawRect(r, selectionFill)
            canvas.drawRect(r, selectionStroke)
        }
    }

    private fun drawLive(canvas: Canvas) {
        val n = livePoints.size / Stroke.STRIDE
        if (n == 0) return
        val lx = livePoints[(n - 1) * 3]
        val ly = livePoints[(n - 1) * 3 + 1]
        if (!liveUsePressure && !liveSpeedWidth) {
            livePaint.color = liveColor
            livePaint.strokeWidth = liveWidth
            canvas.drawPath(livePath, livePaint)
            if (n >= 2) {
                val px = livePoints[(n - 2) * 3]
                val py = livePoints[(n - 2) * 3 + 1]
                canvas.drawLine((px + lx) / 2f, (py + ly) / 2f, lx, ly, livePaint)
            } else {
                canvas.drawLine(lx, ly, lx + 0.01f, ly, livePaint)
            }
            return
        }
        val alpha = (liveColor ushr 24) and 0xFF
        val translucent = alpha < 255
        if (translucent) {
            val pad = liveWidth * 2f
            canvas.saveLayerAlpha(
                RectF(liveBounds.left - pad, liveBounds.top - pad, liveBounds.right + pad, liveBounds.bottom + pad),
                alpha,
            )
        }
        livePaint.color = liveColor or 0xFF000000.toInt()
        if (n == 1) {
            livePaint.strokeWidth = liveWidth * livePoints[2]
            canvas.drawLine(lx, ly, lx + 0.01f, ly, livePaint)
        } else {
            for (i in 1 until n) {
                livePaint.strokeWidth = liveWidth * (livePoints[(i - 1) * 3 + 2] + livePoints[i * 3 + 2]) * 0.5f
                canvas.drawLine(
                    livePoints[(i - 1) * 3], livePoints[(i - 1) * 3 + 1],
                    livePoints[i * 3], livePoints[i * 3 + 1], livePaint,
                )
            }
        }
        if (translucent) canvas.restore()
    }

    private fun drawLasso(canvas: Canvas) {
        val n = lassoPts.size / 2
        if (n < 2) return
        val p = Path()
        p.moveTo(lassoPts[0] * scale + tx, lassoPts[1] * scale + ty)
        for (i in 1 until n) p.lineTo(lassoPts[i * 2] * scale + tx, lassoPts[i * 2 + 1] * scale + ty)
        canvas.drawPath(p, lassoPaint)
    }

    private fun selectionBounds(): RectF {
        val r = RectF(selection[0].bounds)
        for (i in 1 until selection.size) r.union(selection[i].bounds)
        return r
    }

    private fun selectionScreenRect(): RectF {
        val b = selectionBounds()
        val pad = 6f * density
        val dx = if (mode == Mode.MOVE_SELECTION) moveDx else 0f
        val dy = if (mode == Mode.MOVE_SELECTION) moveDy else 0f
        return RectF(
            (b.left + dx) * scale + tx - pad, (b.top + dy) * scale + ty - pad,
            (b.right + dx) * scale + tx + pad, (b.bottom + dy) * scale + ty + pad,
        )
    }

    private fun eraserRadiusPx(): Float = toolConfig.eraserSizeDp * density / 2f

    // ================================================================ entrada

    private fun applyFilterParams() {
        val mc = OneEuroFilter.minCutoffFor(inputConfig.smoothing)
        fx.minCutoff = mc
        fy.minCutoff = mc
    }

    private fun palmThresholdPx(): Float =
        if (inputConfig.palmThresholdPx > 0f) inputConfig.palmThresholdPx else 11f / 25.4f * xdpi

    private fun noteStylus() {
        if (!sessionStylus) {
            sessionStylus = true
            listener?.onStylusDetected()
        }
    }

    private fun classify(e: MotionEvent, i: Int): PKind {
        when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS -> {
                noteStylus()
                return if ((e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) PKind.ERASER_TIP else PKind.STYLUS
            }
            MotionEvent.TOOL_TYPE_ERASER -> {
                noteStylus()
                return PKind.ERASER_TIP
            }
        }
        // Telas capacitivas comuns reportam a área de contato; uma palma é bem maior que a ponta da caneta.
        if (inputConfig.palmRejection && e.getTouchMajor(i) > palmThresholdPx()) return PKind.PALM
        return PKind.FINGER
    }

    private fun fingerRole(): Role {
        if (toolConfig.tool == Tool.HAND) return Role.NAVIGATE
        val stylusKnown = sessionStylus || inputConfig.stylusDetected
        return when (inputConfig.touchMode) {
            TouchMode.AUTO -> if (stylusKnown) Role.NAVIGATE else Role.DRAW
            TouchMode.FINGER_DRAWS -> Role.DRAW
            TouchMode.FINGER_NAVIGATES -> Role.NAVIGATE
            TouchMode.PEN_ONLY -> Role.NONE
        }
    }

    private fun countUsable(e: MotionEvent): Int {
        var c = 0
        for (k in 0 until e.pointerCount) if (e.getPointerId(k) !in ignored) c++
        return c
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (document == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignored.clear()
                mode = Mode.IDLE
                downTime = e.eventTime
                if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                onPointerDown(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e, e.actionIndex, last = false)
            MotionEvent.ACTION_UP -> {
                onPointerUp(e, e.actionIndex, last = true)
                mode = Mode.IDLE
                ignored.clear()
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun onPointerDown(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val kind = classify(e, i)
        if (kind == PKind.PALM) {
            ignored.add(id)
            return
        }
        when (mode) {
            Mode.IDLE -> {
                if (toolConfig.tool == Tool.HAND) {
                    if (countUsable(e) >= 2) startGesture(e) else startPan(e, i)
                    return
                }
                when (kind) {
                    PKind.STYLUS -> startTool(e, i, eraserTip = false, tentative = false)
                    PKind.ERASER_TIP -> startTool(e, i, eraserTip = true, tentative = false)
                    PKind.FINGER -> when (fingerRole()) {
                        Role.DRAW -> startTool(e, i, eraserTip = false, tentative = true)
                        Role.NAVIGATE -> if (countUsable(e) >= 2) startGesture(e) else startPan(e, i)
                        Role.NONE -> if (countUsable(e) >= 2) startGesture(e)
                    }
                    PKind.PALM -> Unit
                }
            }
            Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION -> {
                // Segundo dedo logo no início = intenção de zoom: descarta o traço curto e vira gesto.
                // Depois disso, um segundo contato é tratado como palma/apoio e ignorado.
                val young = e.eventTime - downTime < TENTATIVE_MS && hypot(lastX - downX, lastY - downY) < touchSlop * 2f
                if (kind == PKind.FINGER && tentative && young) {
                    cancelActive()
                    startGesture(e)
                } else {
                    ignored.add(id)
                }
            }
            Mode.PAN -> when (kind) {
                PKind.FINGER -> startGesture(e)
                PKind.STYLUS, PKind.ERASER_TIP -> {
                    if (toolConfig.tool == Tool.HAND) {
                        ignored.add(id)
                    } else {
                        requestRender()
                        mode = Mode.IDLE
                        startTool(e, i, eraserTip = kind == PKind.ERASER_TIP, tentative = false)
                    }
                }
                PKind.PALM -> ignored.add(id)
            }
            Mode.GESTURE -> gMaxPointers = max(gMaxPointers, countUsable(e))
            Mode.WAIT_UP -> ignored.add(id)
        }
    }

    private fun startPan(e: MotionEvent, i: Int) {
        mode = Mode.PAN
        activeId = e.getPointerId(i)
        lastX = e.getX(i)
        lastY = e.getY(i)
    }

    private fun startGesture(e: MotionEvent) {
        var a = -1
        var b = -1
        for (k in 0 until e.pointerCount) {
            val pid = e.getPointerId(k)
            if (pid in ignored) continue
            if (a < 0) a = pid else if (b < 0) b = pid
        }
        if (a < 0 || b < 0) {
            mode = Mode.WAIT_UP
            return
        }
        val ia = e.findPointerIndex(a)
        val ib = e.findPointerIndex(b)
        gA = a
        gB = b
        gLastCx = (e.getX(ia) + e.getX(ib)) / 2f
        gLastCy = (e.getY(ia) + e.getY(ib)) / 2f
        gStartCx = gLastCx
        gStartCy = gLastCy
        gLastDist = hypot(e.getX(ia) - e.getX(ib), e.getY(ia) - e.getY(ib))
        gStartDist = gLastDist
        gMaxPointers = countUsable(e)
        gMoved = false
        activeId = -1
        mode = Mode.GESTURE
    }

    private fun startTool(e: MotionEvent, i: Int, eraserTip: Boolean, tentative: Boolean) {
        activeId = e.getPointerId(i)
        this.tentative = tentative
        downTime = e.eventTime
        val x = e.getX(i)
        val y = e.getY(i)
        downX = x
        downY = y
        lastX = x
        lastY = y
        val isStylus = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
        when (if (eraserTip) Tool.ERASER else toolConfig.tool) {
            Tool.PEN, Tool.PENCIL, Tool.HIGHLIGHTER -> beginStroke(x, y, e.getPressure(i), e.eventTime, isStylus)
            Tool.ERASER -> beginErase(x, y)
            Tool.LASSO -> beginLasso(x, y)
            Tool.HAND -> startPan(e, i)
        }
        invalidate()
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION -> {
                val i = e.findPointerIndex(activeId)
                if (i < 0) return
                val hs = e.historySize
                for (h in 0 until hs) {
                    processPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h), e.getHistoricalEventTime(h))
                }
                processPoint(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
                lastX = e.getX(i)
                lastY = e.getY(i)
                if (mode == Mode.DRAW) updatePrediction()
                invalidate()
            }
            Mode.PAN -> {
                val i = e.findPointerIndex(activeId)
                if (i < 0) return
                tx += e.getX(i) - lastX
                ty += e.getY(i) - lastY
                lastX = e.getX(i)
                lastY = e.getY(i)
                clampTransform()
                invalidate()
                throttledRender()
            }
            Mode.GESTURE -> {
                val ia = e.findPointerIndex(gA)
                val ib = e.findPointerIndex(gB)
                if (ia < 0 || ib < 0) return
                val ax = e.getX(ia)
                val ay = e.getY(ia)
                val bx = e.getX(ib)
                val by = e.getY(ib)
                val cx = (ax + bx) / 2f
                val cy = (ay + by) / 2f
                val dist = hypot(ax - bx, ay - by)
                if (gLastDist > 1f && dist > 1f) zoomAround(cx, cy, dist / gLastDist)
                tx += cx - gLastCx
                ty += cy - gLastCy
                gLastCx = cx
                gLastCy = cy
                gLastDist = dist
                if (!gMoved && (hypot(cx - gStartCx, cy - gStartCy) > touchSlop || abs(dist - gStartDist) > touchSlop)) gMoved = true
                clampTransform()
                invalidate()
                throttledRender()
            }
            else -> Unit
        }
    }

    private fun processPoint(x: Float, y: Float, pressure: Float, t: Long) {
        when (mode) {
            Mode.DRAW -> addInputPoint(x, y, pressure, t, force = false)
            Mode.ERASE -> eraseAlong(x, y)
            Mode.LASSO -> {
                val px = toPageX(x)
                val py = toPageY(y)
                val n = lassoPts.size / 2
                if (n == 0) {
                    lassoPts.add2(px, py)
                } else {
                    val dx = (px - lassoPts[(n - 1) * 2]) * scale
                    val dy = (py - lassoPts[(n - 1) * 2 + 1]) * scale
                    if (dx * dx + dy * dy > 9f) lassoPts.add2(px, py)
                }
            }
            Mode.MOVE_SELECTION -> {
                moveDx = (x - moveStartX) / scale
                moveDy = (y - moveStartY) / scale
            }
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent, i: Int, last: Boolean) {
        val id = e.getPointerId(i)
        if (ignored.remove(id)) return
        val canceled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
        // Se só sobraram contatos ignorados (palma apoiada), o próximo toque da caneta deve
        // começar um traço normalmente — não esperar a palma sair da tela.
        val next = if (last || countUsable(e) - 1 <= 0) Mode.IDLE else Mode.WAIT_UP
        when (mode) {
            Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION -> if (id == activeId) {
                // FLAG_CANCELED (Android 13+): o sistema concluiu que o toque era acidental (ex.: palma).
                if (canceled) cancelActive() else finishActive(e, i)
                mode = next
            }
            Mode.PAN -> if (id == activeId) {
                activeId = -1
                requestRender()
                mode = next
            }
            Mode.GESTURE -> if (id == gA || id == gB) {
                endGesture(e)
                mode = next
            }
            Mode.WAIT_UP -> mode = next
            else -> Unit
        }
    }

    private fun endGesture(e: MotionEvent) {
        val quick = e.eventTime - downTime < TAP_MS
        if (inputConfig.tapGestures && quick && !gMoved) {
            if (gMaxPointers >= 3) {
                if (redo()) listener?.onRedoGesture()
            } else if (gMaxPointers == 2) {
                if (undo()) listener?.onUndoGesture()
            }
        }
        requestRender()
    }

    private fun finishActive(e: MotionEvent, i: Int) {
        val x = e.getX(i)
        val y = e.getY(i)
        when (mode) {
            Mode.DRAW -> endStroke(commit = true, rawX = x, rawY = y)
            Mode.ERASE -> {
                eraseAlong(x, y)
                endErase(commit = true)
            }
            Mode.LASSO -> endLasso()
            Mode.MOVE_SELECTION -> {
                moveDx = (x - moveStartX) / scale
                moveDy = (y - moveStartY) / scale
                endMove(commit = true)
            }
            else -> Unit
        }
        activeId = -1
        invalidate()
    }

    private fun cancelActive() {
        when (mode) {
            Mode.DRAW -> endStroke(commit = false, rawX = 0f, rawY = 0f)
            Mode.ERASE -> endErase(commit = false)
            Mode.LASSO -> lassoPts.clear()
            Mode.MOVE_SELECTION -> endMove(commit = false)
            else -> Unit
        }
        if (mode != Mode.GESTURE && mode != Mode.PAN) mode = Mode.IDLE
        activeId = -1
        hasPrediction = false
        invalidate()
    }

    private fun cancelAll() {
        val wasNavigating = mode == Mode.GESTURE || mode == Mode.PAN
        cancelActive()
        mode = Mode.IDLE
        ignored.clear()
        if (wasNavigating) requestRender()
    }

    // ---------------------------------------------------------------- traço

    private fun beginStroke(sx: Float, sy: Float, pressure: Float, t: Long, isStylus: Boolean) {
        val kind = toolConfig.inkKind() ?: return
        liveKind = kind
        liveColor = toolConfig.colorFor(kind)
        liveWidth = toolConfig.widthFor(kind)
        liveUsePressure = kind != InkKind.HIGHLIGHTER && when (inputConfig.pressureMode) {
            PressureMode.ON -> true
            PressureMode.OFF -> false
            PressureMode.AUTO -> isStylus
        }
        liveSpeedWidth = kind == InkKind.PENCIL && !liveUsePressure
        livePoints.clear()
        livePath.reset()
        fx.reset()
        fy.reset()
        lastWidthFactor = 1f
        hCount = 0
        hasPrediction = false
        mode = Mode.DRAW
        addInputPoint(sx, sy, pressure, t, force = true)
    }

    private fun addInputPoint(sx: Float, sy: Float, pressure: Float, t: Long, force: Boolean) {
        pushHistory(sx, sy, t)
        val fxv = fx.filter(sx.toDouble(), t).toFloat()
        val fyv = fy.filter(sy.toDouble(), t).toFloat()
        val hasPrev = livePoints.size > 0
        var dist = 0f
        if (hasPrev) {
            val dx = fxv - lastFilteredSx
            val dy = fyv - lastFilteredSy
            dist = hypot(dx, dy)
            if (!force && dist < MIN_STEP_PX) return
        }
        var wf = 1f
        if (liveUsePressure) {
            wf = 0.35f + 0.95f * pressure.coerceIn(0f, 1f)
        } else if (liveSpeedWidth && hasPrev) {
            val dt = max(1L, t - lastFilteredT).toFloat()
            val v = dist / dt // px/ms
            wf = (1.15f - 0.22f * v).coerceIn(0.55f, 1.15f)
        }
        if (hasPrev) wf = lastWidthFactor + (wf - lastWidthFactor) * 0.35f
        lastWidthFactor = wf
        lastFilteredSx = fxv
        lastFilteredSy = fyv
        lastFilteredT = t
        appendLive(toPageX(fxv), toPageY(fyv), wf)
    }

    private fun appendLive(px: Float, py: Float, wf: Float) {
        val n = livePoints.size / Stroke.STRIDE
        livePoints.add3(px, py, wf)
        if (n == 0) {
            livePath.moveTo(px, py)
            liveBounds.set(px, py, px, py)
        } else {
            val ppx = livePoints[(n - 1) * 3]
            val ppy = livePoints[(n - 1) * 3 + 1]
            livePath.quadTo(ppx, ppy, (ppx + px) / 2f, (ppy + py) / 2f)
            liveBounds.union(px, py)
        }
    }

    private fun pushHistory(x: Float, y: Float, t: Long) {
        if (hCount < 3) {
            hX[hCount] = x; hY[hCount] = y; hT[hCount] = t
            hCount++
        } else {
            hX[0] = hX[1]; hY[0] = hY[1]; hT[0] = hT[1]
            hX[1] = hX[2]; hY[1] = hY[2]; hT[1] = hT[2]
            hX[2] = x; hY[2] = y; hT[2] = t
        }
    }

    /** Extrapolação linear curta (~1 quadro a 60 Hz) para compensar parte da latência do sistema. */
    private fun updatePrediction() {
        hasPrediction = false
        if (!inputConfig.prediction || hCount < 3 || livePoints.size == 0) return
        val dt = (hT[2] - hT[0]).toFloat()
        if (dt <= 0f || dt > 60f) return
        val vx = (hX[2] - hX[0]) / dt
        val vy = (hY[2] - hY[0]) / dt
        var px = hX[2] + vx * PREDICT_MS
        var py = hY[2] + vy * PREDICT_MS
        val maxLen = 28f * density
        val len = hypot(px - hX[2], py - hY[2])
        if (len > maxLen) {
            px = hX[2] + (px - hX[2]) * maxLen / len
            py = hY[2] + (py - hY[2]) * maxLen / len
        }
        predX = px
        predY = py
        hasPrediction = true
    }

    private fun endStroke(commit: Boolean, rawX: Float, rawY: Float) {
        val doc = document
        if (commit && doc != null && livePoints.size > 0) {
            // Garante que o traço termine onde a caneta saiu (o filtro tende a ficar levemente atrás).
            val n = livePoints.size / 3
            val px = toPageX(rawX)
            val py = toPageY(rawY)
            val lx = livePoints[(n - 1) * 3]
            val ly = livePoints[(n - 1) * 3 + 1]
            if (hypot(px - lx, py - ly) * scale > 0.5f) appendLive(px, py, lastWidthFactor)
            val stroke = Stroke(
                id = Stroke.newId(),
                kind = liveKind,
                color = liveColor,
                width = liveWidth,
                points = livePoints.toArray(),
                z = doc.nextZ(),
                createdAt = System.currentTimeMillis(),
            )
            doc.apply(StrokeChange(emptyList(), listOf(stroke)))
            bakeOrRender(stroke)
        }
        livePoints.clear()
        livePath.reset()
        hasPrediction = false
        invalidate()
    }

    // ---------------------------------------------------------------- borracha

    private fun beginErase(sx: Float, sy: Float) {
        mode = Mode.ERASE
        eraseRemoved = LinkedHashMap()
        eraseAdded = LinkedHashMap()
        eraseLastX = sx
        eraseLastY = sy
        eraseAt(sx, sy)
    }

    private fun eraseAlong(sx: Float, sy: Float) {
        val r = eraserRadiusPx()
        val step = max(2f, r * 0.5f)
        val d = hypot(sx - eraseLastX, sy - eraseLastY)
        val k = max(1, ceil(d / step).toInt())
        for (j in 1..k) {
            val t = j.toFloat() / k
            eraseAt(eraseLastX + (sx - eraseLastX) * t, eraseLastY + (sy - eraseLastY) * t)
        }
        eraseLastX = sx
        eraseLastY = sy
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val doc = document ?: return
        val cx = toPageX(sx)
        val cy = toPageY(sy)
        val r = eraserRadiusPx() / scale
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val victims = ArrayList<Stroke>()
        for (s in doc.strokes) {
            if (!RectF.intersects(s.bounds, box)) continue
            if (Geometry.strokeHitsCircle(s.points, s.width * s.maxWidthFactor / 2f, cx, cy, r)) victims.add(s)
        }
        if (victims.isEmpty()) return
        if (toolConfig.eraserMode == EraserMode.STROKE) {
            doc.applyTransient(StrokeChange(victims, emptyList()))
            for (v in victims) trackRemoved(v)
        } else {
            for (s in victims) {
                val pieces = Geometry.splitOutsideCircle(s.points, cx, cy, r + s.width * 0.3f, max(0.5f, r / 2f)) ?: continue
                val newStrokes = pieces.map { s.withPoints(it) }
                doc.applyTransient(StrokeChange(listOf(s), newStrokes))
                trackRemoved(s)
                for (ns in newStrokes) eraseAdded[ns.id] = ns
            }
        }
        requestRender()
    }

    private fun trackRemoved(s: Stroke) {
        // Pedaço criado nesta mesma passada da borracha: só some do conjunto de adicionados.
        if (eraseAdded.remove(s.id) == null) eraseRemoved[s.id] = s
    }

    private fun endErase(commit: Boolean) {
        val doc = document ?: return
        val net = StrokeChange(eraseRemoved.values.toList(), eraseAdded.values.toList())
        if (commit) {
            doc.commitTransient(net)
        } else {
            doc.applyTransient(net.inverse())
        }
        eraseRemoved = LinkedHashMap()
        eraseAdded = LinkedHashMap()
        requestRender()
    }

    // ---------------------------------------------------------------- laço

    private fun beginLasso(sx: Float, sy: Float) {
        if (selection.isNotEmpty() && selectionScreenRect().contains(sx, sy)) {
            mode = Mode.MOVE_SELECTION
            moveStartX = sx
            moveStartY = sy
            moveDx = 0f
            moveDy = 0f
            hiddenIds.clear()
            for (s in selection) hiddenIds.add(s.id)
            requestRender()
            return
        }
        clearSelection()
        mode = Mode.LASSO
        lassoPts.clear()
        lassoPts.add2(toPageX(sx), toPageY(sy))
    }

    private fun endLasso() {
        val doc = document
        val poly = lassoPts.toArray()
        lassoPts.clear()
        if (doc == null || poly.size < 6) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < poly.size) {
            minX = minOf(minX, poly[i]); maxX = maxOf(maxX, poly[i])
            minY = minOf(minY, poly[i + 1]); maxY = maxOf(maxY, poly[i + 1])
            i += 2
        }
        val box = RectF(minX, minY, maxX, maxY)
        val sel = doc.strokes.filter { RectF.intersects(it.bounds, box) && Geometry.fractionInside(it.points, poly) >= 0.5f }
        setSelection(sel)
    }

    private fun endMove(commit: Boolean) {
        val doc = document
        hiddenIds.clear()
        if (commit && doc != null && (abs(moveDx) > 0.01f || abs(moveDy) > 0.01f)) {
            val moved = selection.map { it.translated(moveDx, moveDy) }
            doc.apply(StrokeChange(selection, moved))
            selection = moved
        }
        moveDx = 0f
        moveDy = 0f
        requestRender()
    }

    private fun setSelection(list: List<Stroke>) {
        selection = list
        listener?.onSelectionChanged(list.size)
        invalidate()
    }

    companion object {
        private const val TAG = "InkCanvasView"
        private const val TENTATIVE_MS = 160L
        private const val TAP_MS = 280L
        private const val PREDICT_MS = 16f
        private const val MIN_STEP_PX = 0.75f
    }
}
