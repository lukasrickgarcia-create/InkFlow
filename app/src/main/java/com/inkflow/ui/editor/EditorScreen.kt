package com.inkflow.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkflow.AppContainer
import com.inkflow.MainActivity
import com.inkflow.core.model.HighlighterColors
import com.inkflow.core.model.InkColors
import com.inkflow.core.model.NotebookKind
import com.inkflow.core.model.PageTemplate
import com.inkflow.core.model.Tool
import com.inkflow.core.model.withAlpha
import com.inkflow.ink.view.InkCanvasView
import com.inkflow.ui.common.ColorDot
import com.inkflow.ui.common.TemplatePicker
import com.inkflow.ui.theme.isAppDark
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    container: AppContainer,
    activity: MainActivity,
    notebookId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember(notebookId) { EditorController(container, notebookId) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    DisposableEffect(activity, controller) {
        activity.keyHandler = { controller.handleKey(it) }
        onDispose { activity.keyHandler = null }
    }

    val settings by container.settings.state.collectAsState()
    val dark = isAppDark(settings.theme)
    val surround = if (dark) 0xFF0E0F12.toInt() else 0xFFE4E2DC.toInt()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var optionsOpen by remember { mutableStateOf(false) }
    var pagesOpen by remember { mutableStateOf(false) }
    var templateOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var exportAnnotated by remember { mutableStateOf(true) }

    LaunchedEffect(controller.notice) {
        val msg = controller.notice ?: return@LaunchedEffect
        controller.notice = null
        snackbar.showSnackbar(msg)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) {
            busy = "Exportando PDF…"
            scope.launch {
                val ok = if (exportAnnotated) {
                    container.pdfExporter.exportAnnotated(notebookId, uri).isSuccess
                } else {
                    container.pdfExporter.exportOriginal(notebookId, uri).isSuccess
                }
                busy = null
                controller.notice = if (ok) "PDF exportado." else "Não foi possível exportar o PDF."
            }
        }
    }

    val nb = controller.notebook
    val tools = controller.tools

    Box(modifier.fillMaxSize().background(Color(surround))) {
        AndroidView(
            factory = { ctx ->
                InkCanvasView(ctx).also { v ->
                    v.inputConfig = settings.input
                    v.surroundColor = surround
                    controller.attachView(v)
                }
            },
            update = { v ->
                v.inputConfig = settings.input
                v.surroundColor = surround
            },
            onRelease = { v -> controller.detachView(v) },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 10.dp, end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EditorToolbar(
                title = nb?.title ?: "",
                tool = tools.tool,
                currentColor = when (tools.tool) {
                    Tool.PENCIL -> tools.pencilColor
                    Tool.HIGHLIGHTER -> tools.highlighterColor
                    else -> tools.penColor
                },
                canUndo = controller.canUndo,
                canRedo = controller.canRedo,
                isPdf = nb?.kind == NotebookKind.PDF,
                onBack = onClose,
                onTool = { t ->
                    if (t == tools.tool) optionsOpen = !optionsOpen else controller.selectTool(t)
                },
                onQuickColor = { color ->
                    val t = tools
                    controller.updateTools(
                        when (t.tool) {
                            Tool.PENCIL -> t.copy(pencilColor = withAlpha(color, alphaOrOne(t.pencilColor)))
                            Tool.HIGHLIGHTER -> t.copy(highlighterColor = withAlpha(color, alphaOrOne(t.highlighterColor)))
                            else -> t.copy(tool = Tool.PEN, penColor = withAlpha(color, alphaOrOne(t.penColor)))
                        },
                    )
                },
                onOptions = { optionsOpen = !optionsOpen },
                onUndo = controller::undo,
                onRedo = controller::redo,
                onPages = { pagesOpen = true },
                onTemplate = { templateOpen = true },
                onExport = { annotated ->
                    exportAnnotated = annotated
                    val base = nb?.title ?: "Caderno"
                    exportLauncher.launch(if (annotated) "$base.pdf" else "$base (original).pdf")
                },
                onFit = controller::fitWidth,
            )
            if (controller.selectionCount > 0) {
                Spacer(Modifier.height(8.dp))
                SelectionBar(
                    count = controller.selectionCount,
                    onDuplicate = controller::duplicateSelection,
                    onRecolor = controller::recolorSelection,
                    onDelete = controller::deleteSelection,
                    onClose = controller::clearSelection,
                )
            }
            if (optionsOpen) {
                Spacer(Modifier.height(8.dp))
                ToolOptionsPanel(
                    tools = tools,
                    presets = settings.presets,
                    onChange = controller::updateTools,
                    onSavePreset = { preset ->
                        container.settings.update { s -> s.copy(presets = (s.presets + preset).takeLast(10)) }
                    },
                    onRemovePreset = { index ->
                        container.settings.update { s -> s.copy(presets = s.presets.filterIndexed { i, _ -> i != index }) }
                    },
                    onClose = { optionsOpen = false },
                )
            }
        }

        PagePill(
            index = controller.currentIndex,
            count = controller.pages.size,
            zoom = controller.zoom,
            onPrev = controller::previousPage,
            onNext = controller::nextPage,
            onAdd = controller::addPageAfterCurrent,
            onGrid = { pagesOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )

        if (controller.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    if (pagesOpen) {
        PagesDialog(container = container, controller = controller, onDismiss = { pagesOpen = false })
    }
    if (templateOpen) {
        var selected by remember { mutableStateOf(PageTemplate.of(controller.currentPage?.template)) }
        AlertDialog(
            onDismissRequest = { templateOpen = false },
            title = { Text("Modelo de papel desta página") },
            text = {
                Column {
                    Text(
                        "Trocar o modelo não altera nada do que já foi escrito.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    TemplatePicker(selected, { selected = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.setTemplate(selected); templateOpen = false }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { templateOpen = false }) { Text("Cancelar") } },
        )
    }
    busy?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(msg) }, text = { Text("Aguarde um instante.") })
    }
}

private fun alphaOrOne(color: Int): Float = ((color ushr 24) and 0xFF) / 255f

// ---------------------------------------------------------------------------- barra superior

@Composable
private fun EditorToolbar(
    title: String,
    tool: Tool,
    currentColor: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    isPdf: Boolean,
    onBack: () -> Unit,
    onTool: (Tool) -> Unit,
    onQuickColor: (Int) -> Unit,
    onOptions: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPages: () -> Unit,
    onTemplate: () -> Unit,
    onExport: (annotated: Boolean) -> Unit,
    onFit: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp).padding(end = 6.dp),
            )
            ToolbarDivider()
            ToolButton(ToolIcons.Pen, "Caneta (P)", tool == Tool.PEN) { onTool(Tool.PEN) }
            ToolButton(ToolIcons.Pencil, "Lápis (L)", tool == Tool.PENCIL) { onTool(Tool.PENCIL) }
            ToolButton(ToolIcons.Highlighter, "Marca-texto (M)", tool == Tool.HIGHLIGHTER) { onTool(Tool.HIGHLIGHTER) }
            ToolButton(ToolIcons.Eraser, "Borracha (E)", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            ToolButton(ToolIcons.Lasso, "Seleção (S)", tool == Tool.LASSO) { onTool(Tool.LASSO) }
            ToolButton(Icons.Outlined.PanTool, "Mover página (H)", tool == Tool.HAND) { onTool(Tool.HAND) }
            ToolbarDivider()
            val quick = if (tool == Tool.HIGHLIGHTER) HighlighterColors.take(5) else InkColors.take(5)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                quick.forEach { c ->
                    ColorDot(c, selected = (c and 0xFFFFFF) == (currentColor and 0xFFFFFF), size = 26.dp) { onQuickColor(c) }
                }
            }
            IconButton(onClick = onOptions) { Icon(Icons.Outlined.Tune, contentDescription = "Opções da ferramenta") }
            ToolbarDivider()
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Desfazer") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Refazer") }
            ToolbarDivider()
            IconButton(onClick = onPages) { Icon(Icons.Outlined.GridView, contentDescription = "Páginas") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Modelo de papel…") }, onClick = { more = false; onTemplate() })
                    DropdownMenuItem(text = { Text("Ajustar à largura") }, onClick = { more = false; onFit() })
                    DropdownMenuItem(text = { Text("Exportar PDF anotado") }, onClick = { more = false; onExport(true) })
                    if (isPdf) DropdownMenuItem(text = { Text("Exportar PDF original") }, onClick = { more = false; onExport(false) })
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    VerticalDivider(Modifier.height(26.dp).padding(horizontal = 4.dp))
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = fg) }
    }
}

// ---------------------------------------------------------------------------- seleção

@Composable
private fun SelectionBar(
    count: Int,
    onDuplicate: () -> Unit,
    onRecolor: (Int) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var colors by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 1) "1 traço selecionado" else "$count traços selecionados", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicar") }
            Box {
                IconButton(onClick = { colors = true }) { Icon(Icons.Outlined.Palette, contentDescription = "Mudar cor") }
                DropdownMenu(expanded = colors, onDismissRequest = { colors = false }) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkColors.take(8).forEach { c -> ColorDot(c, false, 28.dp) { onRecolor(c); colors = false } }
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error) }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Cancelar seleção") }
        }
    }
}

// ---------------------------------------------------------------------------- navegação de páginas

@Composable
private fun PagePill(
    index: Int,
    count: Int,
    zoom: Float,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
    onGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 5.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onPrev, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Página anterior") }
            TextButton(onClick = onGrid) {
                Text(if (count == 0) "–" else "${index + 1} / $count", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onNext, enabled = index < count - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Próxima página") }
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Nova página depois desta") }
            if (zoom > 1.02f || zoom < 0.98f) {
                Text(
                    "${(zoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}
