package com.inkflow.ui.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Área de teste: registra exatamente o que o Android entrega para cada contato.
 * Nada é estimado ou inventado — se um eixo não aparece, é porque o sistema não o informou.
 */
class PenDiagnosticsView(context: Context) : View(context) {

    class ToolStats {
        var events = 0L
        var contacts = 0
        var pMin = Float.MAX_VALUE
        var pMax = -Float.MAX_VALUE
        val pressureBuckets = HashSet<Int>()
        var majorMin = Float.MAX_VALUE
        var majorMax = -Float.MAX_VALUE
        var tiltMax = 0f
        var orientationMin = Float.MAX_VALUE
        var orientationMax = -Float.MAX_VALUE
        var samples = 0L
        var movingMs = 0L
    }

    enum class Calibration { NONE, PEN, PALM }

    val stats = LinkedHashMap<Int, ToolStats>()
    var maxPointers = 0
        private set
    var buttonStates = 0
        private set
    var hoverEvents = 0
        private set
    var canceledEvents = 0
        private set
    val deviceNames = LinkedHashSet<String>()

    var calibration = Calibration.NONE
    val penMajors = ArrayList<Float>()
    val palmMajors = ArrayList<Float>()

    /** Chamado (no máximo ~7x/s) para a interface atualizar os números. */
    var onUpdate: (() -> Unit)? = null
    private var lastNotify = 0L

    private val traces = ArrayList<Pair<Path, Paint>>()
    private val active = HashMap<Int, Path>()
    private val lastTime = HashMap<Int, Long>()
    private val density = resources.displayMetrics.density

    private fun paintFor(toolType: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
        color = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> 0xFF2F6FDB.toInt()
            MotionEvent.TOOL_TYPE_ERASER -> 0xFF8E24AA.toInt()
            MotionEvent.TOOL_TYPE_FINGER -> 0xFFE07B00.toInt()
            MotionEvent.TOOL_TYPE_MOUSE -> 0xFF2E7D32.toInt()
            else -> 0xFF757575.toInt()
        }
    }

    fun clear() {
        stats.clear()
        traces.clear()
        active.clear()
        lastTime.clear()
        maxPointers = 0
        buttonStates = 0
        hoverEvents = 0
        canceledEvents = 0
        deviceNames.clear()
        invalidate()
        onUpdate?.invoke()
    }

    private fun record(e: MotionEvent, i: Int, historical: Int) {
        val tool = e.getToolType(i)
        val s = stats.getOrPut(tool) { ToolStats() }
        val p = if (historical >= 0) e.getHistoricalPressure(i, historical) else e.getPressure(i)
        val major = if (historical >= 0) e.getHistoricalTouchMajor(i, historical) else e.getTouchMajor(i)
        val tilt = if (historical >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, historical) else e.getAxisValue(MotionEvent.AXIS_TILT, i)
        val orientation = if (historical >= 0) e.getHistoricalOrientation(i, historical) else e.getOrientation(i)
        s.events++
        s.pMin = min(s.pMin, p)
        s.pMax = max(s.pMax, p)
        s.pressureBuckets.add((p * 100).toInt())
        s.majorMin = min(s.majorMin, major)
        s.majorMax = max(s.majorMax, major)
        s.tiltMax = max(s.tiltMax, abs(tilt))
        s.orientationMin = min(s.orientationMin, orientation)
        s.orientationMax = max(s.orientationMax, orientation)
        when (calibration) {
            Calibration.PEN -> penMajors.add(major)
            Calibration.PALM -> palmMajors.add(major)
            Calibration.NONE -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        e.device?.name?.let { deviceNames.add(it) }
        maxPointers = max(maxPointers, e.pointerCount)
        buttonStates = buttonStates or e.buttonState
        if (Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0) canceledEvents++

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val path = Path().apply { moveTo(e.getX(i), e.getY(i)) }
                active[id] = path
                traces.add(path to paintFor(e.getToolType(i)))
                stats.getOrPut(e.getToolType(i)) { ToolStats() }.contacts++
                lastTime[id] = e.eventTime
                record(e, i, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val path = active[id] ?: continue
                    val s = stats.getOrPut(e.getToolType(i)) { ToolStats() }
                    for (h in 0 until e.historySize) {
                        path.lineTo(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        record(e, i, h)
                        s.samples++
                    }
                    path.lineTo(e.getX(i), e.getY(i))
                    record(e, i, -1)
                    s.samples++
                    val prev = lastTime[id] ?: e.eventTime
                    s.movingMs += (e.eventTime - prev)
                    lastTime[id] = e.eventTime
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                active.remove(id)
                lastTime.remove(id)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active.clear()
                lastTime.clear()
            }
        }
        if (traces.size > 400) traces.subList(0, traces.size - 400).clear()
        invalidate()
        notifyThrottled(e.eventTime)
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        hoverEvents++
        event.device?.name?.let { deviceNames.add(it) }
        notifyThrottled(event.eventTime)
        return true
    }

    private fun notifyThrottled(t: Long) {
        if (t - lastNotify > 140) {
            lastNotify = t
            onUpdate?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFFFFFFF.toInt())
        for ((p, paint) in traces) canvas.drawPath(p, paint)
    }

    companion object {
        fun toolName(t: Int): String = when (t) {
            MotionEvent.TOOL_TYPE_STYLUS -> "Caneta ativa (STYLUS)"
            MotionEvent.TOOL_TYPE_ERASER -> "Ponta borracha (ERASER)"
            MotionEvent.TOOL_TYPE_FINGER -> "Dedo / caneta capacitiva (FINGER)"
            MotionEvent.TOOL_TYPE_MOUSE -> "Mouse"
            else -> "Desconhecido ($t)"
        }
    }
}
