package com.inkflow.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inkflow.AppContainer
import com.inkflow.data.settings.ThemeMode
import com.inkflow.ink.view.PressureMode
import com.inkflow.ink.view.TouchMode
import com.inkflow.ui.common.ConfirmDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(container: AppContainer, onMessage: (String) -> Unit) {
    val settings by container.settings.state.collectAsState()
    val store = container.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                val r = container.backup.export(uri)
                working = false
                onMessage(if (r.isSuccess) "Backup salvo." else "Falha no backup: ${r.exceptionOrNull()?.message}")
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.widthIn(max = 760.dp)) {
            Section("Aparência") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = settings.theme == m, onClick = { store.update { it.copy(theme = m) } }, label = { Text(m.label) })
                    }
                }
            }

            Section("Toque e caneta") {
                Text(
                    "Canetas capacitivas (como a maioria das universais) aparecem para o Android como um dedo. " +
                        "Use a aba Caneta para confirmar como a sua é reconhecida.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TouchMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = settings.input.touchMode == m, onClick = { store.updateInput { it.copy(touchMode = m) } })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.input.touchMode == m, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (settings.input.stylusDetected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Uma caneta ativa (stylus) já foi detectada neste aparelho.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { store.updateInput { it.copy(stylusDetected = false) } }) { Text("Esquecer") }
                    }
                }
                SwitchRow(
                    "Rejeição de palma",
                    "Ignora contatos grandes (palma, lateral da mão) e toques extras enquanto você escreve. Não é perfeita em telas capacitivas comuns.",
                    settings.input.palmRejection,
                ) { v -> store.updateInput { it.copy(palmRejection = v) } }
                SwitchRow(
                    "Gestos de toque",
                    "Toque com dois dedos desfaz; com três dedos refaz.",
                    settings.input.tapGestures,
                ) { v -> store.updateInput { it.copy(tapGestures = v) } }
            }

            Section("Escrita") {
                Text("Suavização: ${(settings.input.smoothing * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Mais suavização = traço mais limpo, com um pouco mais de atraso em movimentos lentos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = settings.input.smoothing, onValueChange = { v -> store.updateInput { it.copy(smoothing = v) } })
                SwitchRow(
                    "Predição de traço",
                    "Desenha alguns milissegundos à frente para reduzir a sensação de atraso.",
                    settings.input.prediction,
                ) { v -> store.updateInput { it.copy(prediction = v) } }
                Text("Pressão", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PressureMode.entries.forEach { m ->
                        FilterChip(selected = settings.input.pressureMode == m, onClick = { store.updateInput { it.copy(pressureMode = m) } }, label = { Text(m.label) })
                    }
                }
            }

            Section("Backup e dados") {
                Text(
                    "Tudo fica somente neste tablet, no armazenamento privado do app. Nada é enviado para a internet " +
                        "(o app nem pede permissão de internet). Desinstalar o app apaga os dados — faça backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !working, onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                        exportBackup.launch("InkFlow-backup-$stamp.zip")
                    }) { Text("Exportar backup") }
                    OutlinedButton(enabled = !working, onClick = { importBackup.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("Restaurar backup")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Locais: banco de dados em databases/inkflow.db; PDFs importados em files/pdfs; miniaturas em files/thumbs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmWipe = true }) { Text("Apagar todos os dados", color = MaterialTheme.colorScheme.error) }
            }

            Section("Sobre") {
                Text("InkFlow 0.1.0 — caderno digital local, sem conta, sem anúncios e sem telemetria.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Atalhos com teclado: P caneta · L lápis · M marca-texto · E borracha · S seleção · H mover · " +
                        "Ctrl+Z desfazer · Ctrl+Y/Ctrl+Shift+Z refazer · Ctrl +/− zoom · Ctrl+0 ajustar · PgUp/PgDn páginas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmRestore?.let { uri ->
        ConfirmDialog(
            title = "Restaurar backup?",
            message = "Todos os cadernos atuais serão SUBSTITUÍDOS pelos do backup. Se quiser, exporte um backup do estado atual antes. O app vai reiniciar.",
            confirmLabel = "Restaurar",
            destructive = true,
            onConfirm = {
                working = true
                scope.launch {
                    val r = container.backup.restore(uri)
                    working = false
                    if (r.isSuccess) container.backup.restartApp() else onMessage("Falha ao restaurar: ${r.exceptionOrNull()?.message}")
                }
            },
            onDismiss = { confirmRestore = null },
        )
    }
    if (confirmWipe) {
        ConfirmDialog(
            title = "Apagar todos os dados?",
            message = "Cadernos, PDFs importados, anotações e ajustes serão apagados deste tablet. Isso não pode ser desfeito.",
            confirmLabel = "Apagar tudo",
            destructive = true,
            onConfirm = {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
