package com.inkflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkflow.core.model.NotebookKind
import com.inkflow.data.db.NotebookWithCount
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class NotebookAction(val label: String) {
    RENAME("Renomear"),
    FAVORITE("Favoritar / desfavoritar"),
    PIN("Fixar / desafixar"),
    SUBJECT("Definir matéria"),
    MOVE("Mover para pasta"),
    COVER("Cor da capa"),
    EXPORT_PDF("Exportar PDF anotado"),
    EXPORT_ORIGINAL("Exportar PDF original"),
    TRASH("Mover para a lixeira"),
    RESTORE("Restaurar"),
    DELETE_FOREVER("Excluir definitivamente"),
}

private val ptBR: Locale = Locale.forLanguageTag("pt-BR")

fun formatWhen(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) "Hoje, " + SimpleDateFormat("HH:mm", ptBR).format(Date(millis))
    else SimpleDateFormat("dd/MM/yyyy", ptBR).format(Date(millis))
}

@Composable
fun NotebookCard(
    item: NotebookWithCount,
    inTrash: Boolean,
    onOpen: () -> Unit,
    onAction: (NotebookAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nb = item.notebook
    val cover = Color(nb.coverColor)
    var menu by remember { mutableStateOf(false) }
    Column(modifier.padding(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.76f)
                .shadow(3.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(cover, cover.copy(alpha = 0.86f))))
                .clickable(enabled = !inTrash, onClick = onOpen),
        ) {
            // Lombada
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(12.dp)
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
            if (nb.kind == NotebookKind.PDF) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = Color(0xFFB3261E), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("PDF", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3A3A3A))
                    }
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (nb.pinned) Icon(Icons.Filled.PushPin, contentDescription = "Fixado", tint = Color.White, modifier = Modifier.size(16.dp))
                if (nb.favorite) Icon(Icons.Filled.Star, contentDescription = "Favorito", tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, end = 12.dp, bottom = 14.dp),
            ) {
                Text(
                    nb.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nb.subject.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(nb.subject, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp, start = 2.dp)) {
                Text(nb.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pages = if (item.pageCount == 1) "1 página" else "${item.pageCount} páginas"
                val whenText = if (inTrash && nb.deletedAt != null) "excluído ${formatWhen(nb.deletedAt)}" else formatWhen(nb.updatedAt)
                Text(
                    "$pages · $whenText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opções") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val actions = if (inTrash) {
                        listOf(NotebookAction.RESTORE, NotebookAction.DELETE_FOREVER)
                    } else {
                        buildList {
                            add(NotebookAction.RENAME); add(NotebookAction.FAVORITE); add(NotebookAction.PIN)
                            add(NotebookAction.SUBJECT); add(NotebookAction.MOVE); add(NotebookAction.COVER)
                            add(NotebookAction.EXPORT_PDF)
                            if (nb.kind == NotebookKind.PDF) add(NotebookAction.EXPORT_ORIGINAL)
                            add(NotebookAction.TRASH)
                        }
                    }
                    actions.forEach { a ->
                        if (a == NotebookAction.TRASH || a == NotebookAction.DELETE_FOREVER) HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    a.label,
                                    color = if (a == NotebookAction.TRASH || a == NotebookAction.DELETE_FOREVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { menu = false; onAction(a) },
                        )
                    }
                }
            }
        }
    }
}
