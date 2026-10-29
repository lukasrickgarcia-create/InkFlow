package com.inkflow.ink.edit

import com.inkflow.core.model.Stroke
import com.inkflow.core.model.StrokeChange

/**
 * Estado em memória dos traços de UMA página aberta, com histórico de desfazer/refazer.
 *
 * Toda edição é uma [StrokeChange] (removidos + adicionados). Cada alteração confirmada é
 * entregue ao [sink], que grava no banco de forma incremental e atômica (um traço por vez,
 * em transação). Assim nunca é preciso regravar a página inteira.
 *
 * Uso exclusivo da thread principal.
 */
class PageDocument(
    val pageId: String,
    initial: List<Stroke>,
    private val sink: (pageId: String, change: StrokeChange) -> Unit,
) {
    private val list = ArrayList<Stroke>(initial.size + 64)
    private val byId = HashMap<String, Stroke>(initial.size * 2 + 64)
    private val undoStack = ArrayDeque<StrokeChange>()
    private val redoStack = ArrayDeque<StrokeChange>()
    private var seqCounter = 0L
    private var maxZ = 0L

    /** Chamado após qualquer mudança de conteúdo ou de histórico. */
    var listener: (() -> Unit)? = null

    init {
        for (s in initial.sortedWith(compareBy<Stroke>({ it.z }, { it.createdAt }))) {
            s.seq = 0L
            list.add(s)
            byId[s.id] = s
            if (s.z > maxZ) maxZ = s.z
        }
    }

    val strokes: List<Stroke> get() = list
    val currentSeq: Long get() = seqCounter
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun get(id: String): Stroke? = byId[id]
    fun snapshot(): List<Stroke> = ArrayList(list)
    fun nextZ(): Long = ++maxZ

    /** Aplica, grava e registra no histórico. */
    fun apply(change: StrokeChange) {
        if (change.isEmpty) return
        applyRaw(change)
        sink(pageId, change)
        push(change)
    }

    /** Aplica só na memória (ex.: borracha enquanto o gesto acontece). Não grava nem registra. */
    fun applyTransient(change: StrokeChange) {
        if (!change.isEmpty) applyRaw(change)
    }

    /** Registra e grava o resultado líquido de alterações já aplicadas com [applyTransient]. */
    fun commitTransient(net: StrokeChange) {
        if (net.isEmpty) return
        sink(pageId, net)
        push(net)
    }

    fun undo(): Boolean {
        val ch = undoStack.removeLastOrNull() ?: return false
        val inv = ch.inverse()
        applyRaw(inv)
        sink(pageId, inv)
        redoStack.addLast(ch)
        listener?.invoke()
        return true
    }

    fun redo(): Boolean {
        val ch = redoStack.removeLastOrNull() ?: return false
        applyRaw(ch)
        sink(pageId, ch)
        undoStack.addLast(ch)
        listener?.invoke()
        return true
    }

    private fun push(change: StrokeChange) {
        undoStack.addLast(change)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        listener?.invoke()
    }

    private fun applyRaw(change: StrokeChange) {
        if (change.removed.isNotEmpty()) {
            val ids = HashSet<String>(change.removed.size * 2)
            for (r in change.removed) if (byId.remove(r.id) != null) ids.add(r.id)
            if (ids.isNotEmpty()) list.removeAll { it.id in ids }
        }
        for (a in change.added) {
            byId.remove(a.id)?.let { old -> list.remove(old) }
            a.seq = ++seqCounter
            insertByZ(a)
            byId[a.id] = a
            if (a.z > maxZ) maxZ = a.z
        }
    }

    private fun insertByZ(s: Stroke) {
        // Caso comum (traço novo) vai para o fim: O(1).
        if (list.isEmpty() || list[list.size - 1].z <= s.z) {
            list.add(s)
            return
        }
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].z <= s.z) lo = mid + 1 else hi = mid
        }
        list.add(lo, s)
    }

    companion object {
        const val MAX_HISTORY = 300
    }
}
