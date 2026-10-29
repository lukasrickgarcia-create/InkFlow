package com.inkflow.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.inkflow.AppContainer
import com.inkflow.core.model.CoverColors
import com.inkflow.core.model.PageTemplate
import com.inkflow.data.db.FolderEntity
import com.inkflow.data.db.NotebookWithCount
import com.inkflow.pdf.PdfImporter
import com.inkflow.ui.common.ColorDot
import com.inkflow.ui.common.ConfirmDialog
import com.inkflow.ui.common.EmptyState
import com.inkflow.ui.common.SectionTitle
import com.inkflow.ui.common.TemplatePicker
import com.inkflow.ui.common.TextInputDialog
import com.inkflow.ui.diagnostics.DiagnosticsScreen
import com.inkflow.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

enum class HomeTab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Outlined.Home),
    LIBRARY("Biblioteca", Icons.Outlined.Folder),
    TRASH("Lixeira", Icons.Outlined.Delete),
    DIAGNOSTICS("Caneta", Icons.Outlined.TouchApp),
    SETTINGS("Ajustes", Icons.Outlined.Settings),
}

private enum class SortMode(val label: String) { MODIFIED("Modificação"), TITLE("Título"), CREATED("Criação") }

/** Diálogos possíveis na tela inicial (um por vez). */
private sealed interface HomeDialog {
    data object NewNotebook : HomeDialog
    data object NewFolder : HomeDialog
    data object EmptyTrash : HomeDialog
    data class Rename(val item: NotebookWithCount) : HomeDialog
    data class Subject(val item: NotebookWithCount) : HomeDialog
    data class Move(val item: NotebookWithCount) : HomeDialog
    data class Cover(val item: NotebookWithCount) : HomeDialog
    data class DeleteForever(val item: NotebookWithCount) : HomeDialog
    data class RenameFolder(val folder: FolderEntity) : HomeDialog
    data class DeleteFolder(val folder: FolderEntity) : HomeDialog
}

@Composable
fun HomeScreen(
    container: AppContainer,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    onOpenNotebook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notebooks by repo.observeNotebooks().collectAsState(initial = emptyList())
    val trash by repo.observeTrash().collectAsState(initial = emptyList())
    val folders by repo.observeFolders().collectAsState(initial = emptyList())

    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (id, anotado?)
    var busy by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = "Importando PDF…"
            scope.launch {
                when (val r = container.pdfImporter.import(uri)) {
                    is PdfImporter.Result.Ok -> {
                        busy = null
                        onOpenNotebook(r.notebookId)
                    }
                    is PdfImporter.Result.Error -> {
                        busy = null
                        toast(r.message)
                    }
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) {
            busy = "Exportando PDF…"
            scope.launch {
                val ok = if (target.second) {
                    container.pdfExporter.exportAnnotated(target.first, uri).isSuccess
                } else {
                    container.pdfExporter.exportOriginal(target.first, uri).isSuccess
                }
                busy = null
                toast(if (ok) "PDF exportado." else "Não foi possível exportar o PDF.")
            }
        }
    }

    fun onAction(item: NotebookWithCount, action: NotebookAction) {
        val nb = item.notebook
        when (action) {
            NotebookAction.RENAME -> dialog = HomeDialog.Rename(item)
            NotebookAction.FAVORITE -> scope.launch { repo.updateNotebook(nb.id) { it.copy(favorite = !it.favorite) } }
            NotebookAction.PIN -> scope.launch { repo.updateNotebook(nb.id) { it.copy(pinned = !it.pinned) } }
            NotebookAction.SUBJECT -> dialog = HomeDialog.Subject(item)
            NotebookAction.MOVE -> dialog = HomeDialog.Move(item)
            NotebookAction.COVER -> dialog = HomeDialog.Cover(item)
            NotebookAction.EXPORT_PDF -> {
                pendingExport = nb.id to true
                exportLauncher.launch("${nb.title}.pdf")
            }
            NotebookAction.EXPORT_ORIGINAL -> {
                pendingExport = nb.id to false
                exportLauncher.launch("${nb.title} (original).pdf")
            }
            NotebookAction.TRASH -> scope.launch {
                repo.moveToTrash(nb.id)
                val r = snackbar.showSnackbar("\"${nb.title}\" foi para a lixeira.", actionLabel = "Desfazer")
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) repo.restore(nb.id)
            }
            NotebookAction.RESTORE -> scope.launch { repo.restore(nb.id) }
            NotebookAction.DELETE_FOREVER -> dialog = HomeDialog.DeleteForever(item)
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(
                header = {
                    FloatingActionButton(onClick = { dialog = HomeDialog.NewNotebook }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Novo caderno")
                    }
                },
            ) {
                Spacer(Modifier.height(12.dp))
                HomeTab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = t == tab,
                        onClick = { onTabChange(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (tab) {
                    HomeTab.HOME -> HomeContent(
                        notebooks = notebooks,
                        onOpen = onOpenNotebook,
                        onAction = ::onAction,
                        onNew = { dialog = HomeDialog.NewNotebook },
                        onImport = { importPdf.launch(arrayOf("application/pdf")) },
                    )
                    HomeTab.LIBRARY -> LibraryContent(
                        notebooks = notebooks,
                        folders = folders,
                        onOpen = onOpenNotebook,
                        onAction = ::onAction,
                        onNewFolder = { dialog = HomeDialog.NewFolder },
                        onRenameFolder = { dialog = HomeDialog.RenameFolder(it) },
                        onDeleteFolder = { dialog = HomeDialog.DeleteFolder(it) },
                        onImport = { importPdf.launch(arrayOf("application/pdf")) },
                    )
                    HomeTab.TRASH -> TrashContent(trash, ::onAction) { dialog = HomeDialog.EmptyTrash }
                    HomeTab.DIAGNOSTICS -> DiagnosticsScreen(container)
                    HomeTab.SETTINGS -> SettingsScreen(container, onMessage = ::toast)
                }
                busy?.let { msg ->
                    AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(msg) }, text = { Text("Aguarde um instante.") })
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        HomeDialog.NewNotebook -> NewNotebookDialog(
            folders = folders,
            onCreate = { title, template, subject, folderId, cover ->
                scope.launch {
                    val id = repo.createNotebook(title, template, subject, folderId, cover)
                    onOpenNotebook(id)
                }
            },
            onDismiss = { dialog = null },
        )
        HomeDialog.NewFolder -> TextInputDialog("Nova pasta", "", "Nome da pasta", "Criar", onConfirm = { name ->
            scope.launch { repo.createFolder(name) }
        }, onDismiss = { dialog = null })
        HomeDialog.EmptyTrash -> ConfirmDialog(
            "Esvaziar lixeira?", "Todos os cadernos da lixeira serão excluídos para sempre. Isso não pode ser desfeito.",
            "Esvaziar", destructive = true, onConfirm = { scope.launch { repo.emptyTrash() } }, onDismiss = { dialog = null },
        )
        is HomeDialog.Rename -> TextInputDialog("Renomear caderno", d.item.notebook.title, "Título", onConfirm = { t ->
            if (t.isNotBlank()) scope.launch { repo.updateNotebook(d.item.notebook.id) { it.copy(title = t) } }
        }, onDismiss = { dialog = null })
        is HomeDialog.Subject -> TextInputDialog("Matéria", d.item.notebook.subject, "Ex.: Cálculo, História…", onConfirm = { t ->
            scope.launch { repo.updateNotebook(d.item.notebook.id) { it.copy(subject = t) } }
        }, onDismiss = { dialog = null })
        is HomeDialog.Move -> MoveDialog(folders, d.item.notebook.folderId, onPick = { folderId ->
            scope.launch { repo.updateNotebook(d.item.notebook.id) { it.copy(folderId = folderId) } }
        }, onDismiss = { dialog = null })
        is HomeDialog.Cover -> CoverDialog(d.item.notebook.coverColor, onPick = { color ->
            scope.launch { repo.updateNotebook(d.item.notebook.id) { it.copy(coverColor = color) } }
        }, onDismiss = { dialog = null })
        is HomeDialog.DeleteForever -> ConfirmDialog(
            "Excluir definitivamente?", "\"${d.item.notebook.title}\" e todas as suas anotações serão apagados. Isso não pode ser desfeito.",
            "Excluir", destructive = true, onConfirm = { scope.launch { repo.deleteForever(d.item.notebook.id) } }, onDismiss = { dialog = null },
        )
        is HomeDialog.RenameFolder -> TextInputDialog("Renomear pasta", d.folder.name, "Nome", onConfirm = { n ->
            if (n.isNotBlank()) scope.launch { repo.renameFolder(d.folder.id, n) }
        }, onDismiss = { dialog = null })
        is HomeDialog.DeleteFolder -> ConfirmDialog(
            "Excluir pasta?", "A pasta \"${d.folder.name}\" será removida. Os cadernos dentro dela NÃO são excluídos; eles ficam sem pasta.",
            "Excluir pasta", destructive = true, onConfirm = { scope.launch { repo.deleteFolder(d.folder.id) } }, onDismiss = { dialog = null },
        )
    }
}

// ------------------------------------------------------------------------ Início

@Composable
private fun HomeContent(
    notebooks: List<NotebookWithCount>,
    onOpen: (String) -> Unit,
    onAction: (NotebookWithCount, NotebookAction) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val results = if (q.isEmpty()) emptyList() else notebooks.filter {
        it.notebook.title.contains(q, ignoreCase = true) || it.notebook.subject.contains(q, ignoreCase = true)
    }
    val pinned = notebooks.filter { it.notebook.pinned }
    val favorites = notebooks.filter { it.notebook.favorite && !it.notebook.pinned }
    val recent = notebooks.sortedByDescending { it.notebook.openedAt }.take(12)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullSpan {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("InkFlow", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Importar PDF")
                }
                Spacer(Modifier.width(8.dp))
                ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Novo caderno") })
            }
        }
        fullSpan {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Buscar por título ou matéria") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (q.isNotEmpty()) {
            fullSpan { SectionTitle(if (results.isEmpty()) "Nada encontrado" else "Resultados") }
            notebookItems(results, onOpen, onAction)
            return@LazyVerticalGrid
        }
        if (notebooks.isEmpty()) {
            fullSpan {
                EmptyState(
                    "Seu primeiro caderno",
                    "Crie um caderno ou importe um PDF para começar a escrever.",
                )
            }
            return@LazyVerticalGrid
        }
        if (pinned.isNotEmpty()) {
            fullSpan { SectionTitle("Fixados") }
            notebookItems(pinned, onOpen, onAction)
        }
        if (favorites.isNotEmpty()) {
            fullSpan { SectionTitle("Favoritos") }
            notebookItems(favorites, onOpen, onAction)
        }
        fullSpan { SectionTitle("Recentes") }
        notebookItems(recent, onOpen, onAction)
    }
}

private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.notebookItems(
    list: List<NotebookWithCount>,
    onOpen: (String) -> Unit,
    onAction: (NotebookWithCount, NotebookAction) -> Unit,
    inTrash: Boolean = false,
) {
    items(list, key = { it.notebook.id + if (inTrash) "-t" else "" }) { item ->
        NotebookCard(item, inTrash = inTrash, onOpen = { onOpen(item.notebook.id) }, onAction = { onAction(item, it) })
    }
}

// ------------------------------------------------------------------------ Biblioteca

private const val ALL = "__all__"
private const val NO_FOLDER = "__none__"

@Composable
private fun LibraryContent(
    notebooks: List<NotebookWithCount>,
    folders: List<FolderEntity>,
    onOpen: (String) -> Unit,
    onAction: (NotebookWithCount, NotebookAction) -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit,
    onImport: () -> Unit,
) {
    var folderFilter by remember { mutableStateOf(ALL) }
    var subjectFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SortMode.MODIFIED) }
    var sortMenu by remember { mutableStateOf(false) }

    val subjects = notebooks.map { it.notebook.subject }.filter { it.isNotBlank() }.distinct().sorted()
    val filtered = notebooks
        .filter {
            when (folderFilter) {
                ALL -> true
                NO_FOLDER -> it.notebook.folderId == null
                else -> it.notebook.folderId == folderFilter
            }
        }
        .filter { subjectFilter == null || it.notebook.subject == subjectFilter }
        .let { list ->
            when (sort) {
                SortMode.MODIFIED -> list.sortedByDescending { it.notebook.updatedAt }
                SortMode.TITLE -> list.sortedBy { it.notebook.title.lowercase() }
                SortMode.CREATED -> list.sortedByDescending { it.notebook.createdAt }
            }
        }
    val selectedFolder = folders.firstOrNull { it.id == folderFilter }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullSpan {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Biblioteca", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Outlined.Sort, null)
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { sort = m; sortMenu = false })
                        }
                    }
                }
                OutlinedButton(onClick = onImport) { Text("Importar PDF") }
            }
        }
        fullSpan {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = folderFilter == ALL, onClick = { folderFilter = ALL }, label = { Text("Todos") })
                FilterChip(selected = folderFilter == NO_FOLDER, onClick = { folderFilter = NO_FOLDER }, label = { Text("Sem pasta") })
                folders.forEach { f ->
                    FilterChip(selected = folderFilter == f.id, onClick = { folderFilter = f.id }, label = { Text(f.name) })
                }
                TextButton(onClick = onNewFolder) {
                    Icon(Icons.Filled.Add, null)
                    Text("Pasta")
                }
                if (selectedFolder != null) {
                    IconButton(onClick = { onRenameFolder(selectedFolder) }) { Icon(Icons.Outlined.Edit, "Renomear pasta") }
                    IconButton(onClick = { onDeleteFolder(selectedFolder); folderFilter = ALL }) { Icon(Icons.Outlined.Delete, "Excluir pasta") }
                }
            }
        }
        if (subjects.isNotEmpty()) {
            fullSpan {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Matérias:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = subjectFilter == null, onClick = { subjectFilter = null }, label = { Text("Todas") })
                    subjects.forEach { s ->
                        FilterChip(selected = subjectFilter == s, onClick = { subjectFilter = s }, label = { Text(s) })
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            fullSpan { EmptyState("Nenhum caderno aqui", "Ajuste os filtros ou crie um novo caderno.") }
        } else {
            notebookItems(filtered, onOpen, onAction)
        }
    }
}

// ------------------------------------------------------------------------ Lixeira

@Composable
private fun TrashContent(
    trash: List<NotebookWithCount>,
    onAction: (NotebookWithCount, NotebookAction) -> Unit,
    onEmpty: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullSpan {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lixeira", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Cadernos aqui podem ser restaurados. Nada é apagado automaticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trash.isNotEmpty()) OutlinedButton(onClick = onEmpty) { Text("Esvaziar lixeira", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (trash.isEmpty()) {
            fullSpan { EmptyState("Lixeira vazia", "Cadernos excluídos aparecem aqui.") }
        } else {
            notebookItems(trash, onOpen = {}, onAction = onAction, inTrash = true)
        }
    }
}

// ------------------------------------------------------------------------ diálogos

@Composable
private fun NewNotebookDialog(
    folders: List<FolderEntity>,
    onCreate: (String, PageTemplate, String, String?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(PageTemplate.LINED) }
    var cover by remember { mutableStateOf(CoverColors.first()) }
    var folderId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo caderno") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subject, { subject = it }, label = { Text("Matéria (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Papel", style = MaterialTheme.typography.labelLarge)
                TemplatePicker(template, { template = it })
                Text("Capa", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverColors.forEach { c -> ColorDot(c, c == cover, 30.dp) { cover = c } }
                }
                if (folders.isNotEmpty()) {
                    Text("Pasta", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = folderId == null, onClick = { folderId = null }, label = { Text("Nenhuma") })
                        folders.forEach { f -> FilterChip(selected = folderId == f.id, onClick = { folderId = f.id }, label = { Text(f.name) }) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title.trim(), template, subject, folderId, cover); onDismiss() }) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MoveDialog(folders: List<FolderEntity>, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover para pasta") },
        text = {
            Column {
                if (folders.isEmpty()) Text("Crie pastas na Biblioteca para organizar seus cadernos.")
                FilterChip(selected = current == null, onClick = { onPick(null); onDismiss() }, label = { Text("Sem pasta") })
                folders.forEach { f ->
                    FilterChip(selected = current == f.id, onClick = { onPick(f.id); onDismiss() }, label = { Text(f.name) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun CoverDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cor da capa") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CoverColors.forEach { c -> ColorDot(c, c == current, 36.dp) { onPick(c); onDismiss() } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
