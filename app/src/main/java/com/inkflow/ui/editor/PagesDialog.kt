package com.inkflow.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkflow.AppContainer
import com.inkflow.data.db.PageEntity
import com.inkflow.ui.common.ConfirmDialog

/** Visão geral das páginas: miniaturas geradas em segundo plano, reordenar, duplicar e excluir. */
@Composable
fun PagesDialog(container: AppContainer, controller: EditorController, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<PageEntity?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Páginas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { controller.addPageAtEnd() }) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Nova página no final")
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Fechar") }
                }
                val pages = controller.pages
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                        PageCell(
                            container = container,
                            page = p,
                            index = i,
                            count = pages.size,
                            current = i == controller.currentIndex,
                            onOpen = { controller.openPage(i); onDismiss() },
                            onDuplicate = { controller.duplicatePage(p.id) },
                            onMoveBefore = { controller.movePage(i, i - 1) },
                            onMoveAfter = { controller.movePage(i, i + 1) },
                            onDelete = { confirmDelete = p },
                        )
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        ConfirmDialog(
            title = "Excluir página?",
            message = "A página e tudo o que foi escrito nela serão apagados.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = { controller.deletePage(p.id) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun PageCell(
    container: AppContainer,
    page: PageEntity,
    index: Int,
    count: Int,
    current: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveBefore: () -> Unit,
    onMoveAfter: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val version by container.thumbnails.version.collectAsState()
    var bmp by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.id, version) {
        bmp = container.thumbnails.load(page.id)?.asImageBitmap()
    }
    val ring = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(page.widthPt / page.heightPt)
                .clip(RoundedCornerShape(8.dp))
                .border(if (current) 2.5.dp else 1.dp, ring, RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClick = onOpen),
        ) {
            bmp?.let { Image(it, contentDescription = "Página ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opções da página") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Abrir") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Duplicar") }, onClick = { menu = false; onDuplicate() })
                    if (index > 0) DropdownMenuItem(text = { Text("Mover para antes") }, onClick = { menu = false; onMoveBefore() })
                    if (index < count - 1) DropdownMenuItem(text = { Text("Mover para depois") }, onClick = { menu = false; onMoveAfter() })
                    DropdownMenuItem(
                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                        enabled = count > 1,
                    )
                }
            }
        }
    }
}
