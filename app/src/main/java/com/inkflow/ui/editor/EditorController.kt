package com.inkflow.ui.editor

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkflow.AppContainer
import com.inkflow.core.model.PageTemplate
import com.inkflow.core.model.Tool
import com.inkflow.data.db.NotebookEntity
import com.inkflow.data.db.PageEntity
import com.inkflow.ink.edit.PageDocument
import com.inkflow.ink.view.InkCanvasView
import com.inkflow.ink.view.ToolConfig
import com.inkflow.pdf.PdfSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado do editor de um caderno. Liga a interface Compose, a superfície de escrita
 * (InkCanvasView) e o repositório. Vive enquanto o editor estiver aberto.
 */
class EditorController(private val c: AppContainer, val notebookId: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var notebook by mutableStateOf<NotebookEntity?>(null)
        private set
    var pages by mutableStateOf<List<PageEntity>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var currentPage by mutableStateOf<PageEntity?>(null)
        private set
    var document by mutableStateOf<PageDocument?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var selectionCount by mutableIntStateOf(0)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var loading by mutableStateOf(true)
        private set
    var tools by mutableStateOf(c.settings.current.tools)
        private set

    /** Mensagem curta para a interface mostrar (snackbar). */
    var notice by mutableStateOf<String?>(null)

    var pdf: PdfSource? = null
        private set
    private var view: InkCanvasView? = null
    private var loadJob: Job? = null
    private var initialized = false
    private var pendingOpen: Int? = null

    val viewListener = object : InkCanvasView.Listener {
        override fun onSelectionChanged(count: Int) {
            selectionCount = count
        }

        override fun onStylusDetected() {
            if (!c.settings.current.input.stylusDetected) {
                c.settings.updateInput { it.copy(stylusDetected = true) }
                notice = "Caneta ativa detectada. No modo Automático, o dedo passa a navegar."
            }
        }

        override fun onUndoGesture() {
            notice = "Desfeito"
        }

        override fun onRedoGesture() {
            notice = "Refeito"
        }

        override fun onZoomChanged(relativeZoom: Float) {
            zoom = relativeZoom
        }
    }

    init {
        scope.launch {
            val nb = c.repository.getNotebook(notebookId)
            if (nb == null) {
                notice = "Caderno não encontrado."
                loading = false
                return@launch
            }
            notebook = nb
            val pdfName = nb.pdfFile
            if (pdfName != null) {
                pdf = withContext(Dispatchers.IO) { runCatching { PdfSource(c.files.pdf(pdfName)) }.getOrNull() }
                if (pdf == null) notice = "Não foi possível abrir o PDF original. As anotações continuam salvas."
            }
            launch { c.repository.observeNotebook(notebookId).collect { if (it != null) notebook = it } }
            c.repository.observePages(notebookId).collect { list -> onPagesChanged(list, nb.lastPageIndex) }
        }
    }

    private fun onPagesChanged(list: List<PageEntity>, initialIndex: Int) {
        pages = list
        if (list.isEmpty()) return
        if (!initialized) {
            initialized = true
            openPage(initialIndex.coerceIn(0, list.lastIndex))
            return
        }
        val cur = currentPage
        if (cur != null) {
            val idx = list.indexOfFirst { it.id == cur.id }
            if (idx >= 0) {
                currentIndex = idx
                val updated = list[idx]
                currentPage = updated
                view?.updateTemplate(PageTemplate.of(updated.template))
            } else {
                // A página aberta foi excluída.
                document = null
                currentPage = null
                openPage(currentIndex.coerceIn(0, list.lastIndex))
            }
        }
        pendingOpen?.let { target ->
            if (target in list.indices) {
                pendingOpen = null
                openPage(target)
            }
        }
    }

    // ------------------------------------------------------------ superfície de escrita

    fun attachView(v: InkCanvasView) {
        view = v
        v.listener = viewListener
        v.toolConfig = tools
        bindView()
    }

    fun detachView(v: InkCanvasView) {
        if (view === v) view = null
        v.release()
    }

    private fun bindView() {
        val v = view ?: return
        val d = document ?: return
        val p = currentPage ?: return
        if (v.currentDocument === d) return
        val source = if (p.pdfPageIndex >= 0) pdf else null
        v.setPage(d, PageTemplate.of(p.template), source, p.pdfPageIndex, p.widthPt, p.heightPt)
        selectionCount = 0
    }

    // ------------------------------------------------------------ páginas

    fun openPage(index: Int) {
        val p = pages.getOrNull(index) ?: return
        if (currentPage?.id == p.id && document != null) return
        currentPage?.let { c.thumbnails.schedule(it.id, 300) }
        currentIndex = index
        currentPage = p
        loading = true
        loadJob?.cancel()
        loadJob = scope.launch {
            val strokes = c.repository.loadStrokes(p.id)
            val doc = PageDocument(p.id, strokes) { pageId, change ->
                c.repository.persistChange(pageId, change)
                c.thumbnails.schedule(pageId)
            }
            doc.listener = {
                canUndo = doc.canUndo
                canRedo = doc.canRedo
            }
            document = doc
            canUndo = false
            canRedo = false
            loading = false
            bindView()
            c.repository.markOpened(notebookId, index)
        }
    }

    fun nextPage() = openPage(currentIndex + 1)
    fun previousPage() = openPage(currentIndex - 1)

    private fun defaultTemplate(): PageTemplate {
        val cur = PageTemplate.of(currentPage?.template)
        if (cur != PageTemplate.PDF) return cur
        val nbDefault = PageTemplate.of(notebook?.defaultTemplate)
        return if (nbDefault == PageTemplate.PDF) PageTemplate.BLANK else nbDefault
    }

    /** Abre a página nova assim que a lista refletir a inserção (a atualização do banco é assíncrona). */
    private fun openWhenReady(pos: Int, expectedSize: Int) {
        if (pages.size >= expectedSize) openPage(pos) else pendingOpen = pos
    }

    fun addPageAfterCurrent() {
        val expected = pages.size + 1
        scope.launch { openWhenReady(c.repository.addPage(notebookId, currentIndex + 1, defaultTemplate()), expected) }
    }

    fun addPageAtEnd() {
        val expected = pages.size + 1
        scope.launch { openWhenReady(c.repository.addPage(notebookId, pages.size, defaultTemplate()), expected) }
    }

    fun duplicatePage(pageId: String) {
        val expected = pages.size + 1
        scope.launch {
            val pos = c.repository.duplicatePage(pageId)
            if (pos >= 0) openWhenReady(pos, expected)
        }
    }

    fun deletePage(pageId: String) {
        scope.launch {
            if (!c.repository.deletePage(pageId)) notice = "O caderno precisa ter ao menos uma página."
        }
    }

    fun movePage(from: Int, to: Int) {
        scope.launch { c.repository.movePage(notebookId, from, to) }
    }

    fun setTemplate(t: PageTemplate) {
        val p = currentPage ?: return
        if (p.pdfPageIndex >= 0) {
            notice = "Páginas de PDF usam o próprio documento como fundo."
            return
        }
        scope.launch { c.repository.setPageTemplate(p.id, t) }
    }

    // ------------------------------------------------------------ ferramentas

    fun updateTools(t: ToolConfig) {
        tools = t
        view?.toolConfig = t
        c.settings.updateTools { t }
    }

    fun selectTool(tool: Tool) = updateTools(tools.copy(tool = tool))

    fun undo() {
        view?.undo()
    }

    fun redo() {
        view?.redo()
    }

    fun deleteSelection() {
        view?.deleteSelection()
    }

    fun duplicateSelection() {
        view?.duplicateSelection()
    }

    fun recolorSelection(color: Int) {
        view?.recolorSelection(color)
    }

    fun clearSelection() {
        view?.clearSelection()
    }

    fun zoomBy(f: Float) {
        view?.zoomBy(f)
    }

    fun fitWidth() {
        view?.let {
            it.fitWidth()
            it.zoomBy(1f)
        }
    }

    /** Atalhos de teclado físico. */
    fun handleKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        if (ctrl) {
            return when (e.keyCode) {
                KeyEvent.KEYCODE_Z -> { if (e.isShiftPressed) redo() else undo(); true }
                KeyEvent.KEYCODE_Y -> { redo(); true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { zoomBy(1.25f); true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { zoomBy(0.8f); true }
                KeyEvent.KEYCODE_0 -> { fitWidth(); true }
                else -> false
            }
        }
        if (e.isAltPressed || e.isMetaPressed) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_P -> { selectTool(Tool.PEN); true }
            KeyEvent.KEYCODE_L -> { selectTool(Tool.PENCIL); true }
            KeyEvent.KEYCODE_M -> { selectTool(Tool.HIGHLIGHTER); true }
            KeyEvent.KEYCODE_E -> { selectTool(Tool.ERASER); true }
            KeyEvent.KEYCODE_S -> { selectTool(Tool.LASSO); true }
            KeyEvent.KEYCODE_H -> { selectTool(Tool.HAND); true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { nextPage(); true }
            KeyEvent.KEYCODE_PAGE_UP -> { previousPage(); true }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (selectionCount > 0) { deleteSelection(); true } else false
            KeyEvent.KEYCODE_ESCAPE -> if (selectionCount > 0) { clearSelection(); true } else false
            else -> false
        }
    }

    fun dispose() {
        currentPage?.let { c.thumbnails.schedule(it.id, 200) }
        scope.cancel()
        val p = pdf
        pdf = null
        // Fecha o PDF fora da thread principal: a renderização pode estar segurando o lock.
        if (p != null) c.appScope.launch(Dispatchers.IO) { p.close() }
    }
}
