package com.inkflow.ui.diagnostics

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkflow.AppContainer
import java.util.Locale

/**
 * Diagnóstico do hardware de entrada. Mostra o que o Android REALMENTE informa
 * no seu tablet, e permite calibrar a rejeição de palma pelo tamanho do contato.
 */
@Composable
fun DiagnosticsScreen(container: AppContainer) {
    val context = LocalContext.current
    val deviceInfo = remember { collectDeviceInfo(context) }
    var tick by remember { mutableIntStateOf(0) }
    var viewRef by remember { mutableStateOf<PenDiagnosticsView?>(null) }
    var calibration by remember { mutableStateOf(PenDiagnosticsView.Calibration.NONE) }
    var message by remember { mutableStateOf<String?>(null) }
    val settings = container.settings

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight()) {
            Text("Teste da caneta", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Escreva aqui com a caneta HYQ e depois com o dedo. Azul = stylus, laranja = dedo/caneta capacitiva.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            ) {
                AndroidView(
                    factory = { ctx -> PenDiagnosticsView(ctx).also { v -> v.onUpdate = { tick++ }; viewRef = v } },
                    update = { v -> v.calibration = calibration },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewRef?.clear() }) { Text("Limpar") }
                Button(onClick = {
                    val report = buildReport(deviceInfo, viewRef, settings.current.input.palmThresholdPx)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico InkFlow", report))
                    message = "Relatório copiado. Cole na conversa para análise."
                }) { Text("Copiar relatório") }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            // key(tick): refaz este bloco quando a área de teste avisa que chegaram novos eventos.
            key(tick) {
            val v = viewRef
            InfoCard("Conclusão") {
                Text(conclusion(v), style = MaterialTheme.typography.bodyMedium)
            }
            InfoCard("Entrada observada") {
                if (v == null || v.stats.isEmpty()) {
                    Mono("Nenhum toque registrado ainda.")
                } else {
                    v.stats.forEach { (tool, s) ->
                        Text(PenDiagnosticsView.toolName(tool), style = MaterialTheme.typography.labelLarge)
                        Mono(statsText(s))
                        Spacer(Modifier.height(6.dp))
                    }
                    Mono(
                        "Toques simultâneos (máx.): ${v.maxPointers}\n" +
                            "Botões da caneta vistos: ${if (v.buttonStates == 0) "nenhum" else "0x" + Integer.toHexString(v.buttonStates)}\n" +
                            "Eventos de hover (caneta perto da tela): ${v.hoverEvents}\n" +
                            "Toques cancelados pelo sistema: ${v.canceledEvents}\n" +
                            "Dispositivos: ${v.deviceNames.joinToString().ifEmpty { "—" }}",
                    )
                }
            }
            InfoCard("Calibrar rejeição de palma") {
                Text(
                    "Mede o tamanho do contato (TOUCH_MAJOR) da ponta da caneta e da palma. " +
                        "Contatos maiores que o limiar são ignorados ao escrever.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val pen = v?.penMajors.orEmpty()
                val palm = v?.palmMajors.orEmpty()
                Mono(
                    "Caneta: ${pen.size} amostras, p95 = ${fmt(percentile(pen, 0.95f))} px\n" +
                        "Palma:  ${palm.size} amostras, p10 = ${fmt(percentile(palm, 0.10f))} px\n" +
                        "Limiar atual: ${if (settings.current.input.palmThresholdPx > 0f) fmt(settings.current.input.palmThresholdPx) + " px" else "padrão (~11 mm)"}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { v?.penMajors?.clear(); calibration = PenDiagnosticsView.Calibration.PEN }) {
                        Text(if (calibration == PenDiagnosticsView.Calibration.PEN) "Medindo caneta…" else "1. Medir caneta")
                    }
                    TextButton(onClick = { v?.palmMajors?.clear(); calibration = PenDiagnosticsView.Calibration.PALM }) {
                        Text(if (calibration == PenDiagnosticsView.Calibration.PALM) "Medindo palma…" else "2. Medir palma")
                    }
                    TextButton(onClick = {
                        calibration = PenDiagnosticsView.Calibration.NONE
                        val threshold = computeThreshold(pen, palm)
                        if (threshold == null) {
                            message = "Escreva com a caneta por alguns segundos antes de salvar."
                        } else {
                            settings.updateInput { it.copy(palmThresholdPx = threshold) }
                            message = "Limiar salvo: ${fmt(threshold)} px."
                        }
                    }) { Text("Salvar") }
                }
                if (pen.isNotEmpty() && palm.isNotEmpty() && (percentile(palm, 0.10f) ?: 0f) <= (percentile(pen, 0.95f) ?: 0f)) {
                    Text(
                        "Os tamanhos da caneta e da palma se sobrepõem: este digitalizador não permite separá-los pelo tamanho. " +
                            "A rejeição vai depender só das regras de múltiplos toques.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            InfoCard("Aparelho") { Mono(deviceInfo) }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

private fun fmt(v: Float?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)

private fun percentile(list: List<Float>, q: Float): Float? {
    if (list.isEmpty()) return null
    val sorted = list.sorted()
    return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
}

/** Limiar entre a caneta e a palma; sem medida da palma usa 1,6× a caneta. */
private fun computeThreshold(pen: List<Float>, palm: List<Float>): Float? {
    val p95 = percentile(pen, 0.95f) ?: return null
    if (p95 <= 0f) return null
    val palm10 = percentile(palm, 0.10f)
    return if (palm10 != null && palm10 > p95) (p95 + palm10) / 2f else p95 * 1.6f
}

private fun statsText(s: PenDiagnosticsView.ToolStats): String {
    val rate = if (s.movingMs > 50) s.samples * 1000f / s.movingMs else null
    val pressureVaries = s.pressureBuckets.size > 3 && (s.pMax - s.pMin) > 0.05f
    return "contatos: ${s.contacts}, amostras: ${s.events}\n" +
        "pressão: ${fmt(s.pMin)} – ${fmt(s.pMax)} (${if (pressureVaries) "varia" else "constante"})\n" +
        "tamanho do contato: ${fmt(s.majorMin)} – ${fmt(s.majorMax)} px\n" +
        "inclinação máx.: ${fmt(s.tiltMax)} rad${if (s.tiltMax == 0f) " (não informada)" else ""}\n" +
        "orientação: ${fmt(s.orientationMin)} – ${fmt(s.orientationMax)} rad\n" +
        "taxa de amostragem: ${if (rate == null) "—" else fmt(rate) + " Hz"}"
}

private fun conclusion(v: PenDiagnosticsView?): String {
    if (v == null || v.stats.isEmpty()) {
        return "Escreva na área de teste com a caneta HYQ para descobrir como o Android a reconhece."
    }
    val stylus = v.stats[MotionEvent.TOOL_TYPE_STYLUS]
    val finger = v.stats[MotionEvent.TOOL_TYPE_FINGER]
    val sb = StringBuilder()
    if (stylus != null && stylus.events > 0) {
        sb.append("A caneta é reconhecida como STYLUS. O modo Automático usa a caneta para escrever e o dedo para navegar.\n")
        val pressureVaries = stylus.pressureBuckets.size > 3 && (stylus.pMax - stylus.pMin) > 0.05f
        sb.append(if (pressureVaries) "Pressão: informada e variável — a espessura pode acompanhar a pressão.\n" else "Pressão: não varia — o app não vai simular pressão como se fosse real.\n")
        sb.append(if (stylus.tiltMax > 0f) "Inclinação: informada.\n" else "Inclinação: não informada pelo sistema.\n")
    } else if (finger != null) {
        sb.append(
            "Nenhum evento de STYLUS foi registrado. Se você escreveu com a caneta HYQ, ela é capacitiva: " +
                "para o Android ela é igual a um dedo. Consequências:\n" +
                "• sem pressão real nem inclinação;\n" +
                "• o app não consegue distinguir caneta de dedo pelo tipo de entrada;\n" +
                "• use o modo de toque \"Qualquer toque escreve\" (ou Automático) — dois dedos fazem zoom e rolagem;\n" +
                "• a rejeição de palma usa o tamanho do contato (calibre abaixo) e regras de múltiplos toques.\n",
        )
    }
    if (v.hoverEvents > 0) sb.append("Hover detectado: a tela percebe a caneta antes do toque.\n")
    return sb.toString().trim()
}

private fun collectDeviceInfo(context: Context): String {
    val dm = context.resources.displayMetrics
    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    val modes = display?.supportedModes?.map { it.refreshRate }?.distinct()?.joinToString { String.format(Locale.US, "%.0f", it) }
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val sb = StringBuilder()
    sb.append("Modelo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
    sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
    sb.append("Tela: ${dm.widthPixels}×${dm.heightPixels} px, ${dm.densityDpi} dpi (xdpi ${String.format(Locale.US, "%.0f", dm.xdpi)})\n")
    sb.append("Atualização: ${display?.refreshRate?.let { String.format(Locale.US, "%.0f", it) } ?: "?"} Hz (modos: ${modes ?: "?"})\n")
    sb.append("RAM: ${mi.totalMem / (1024 * 1024)} MB total, ${mi.availMem / (1024 * 1024)} MB livre; heap do app ${am.memoryClass} MB (large ${am.largeMemoryClass} MB)\n")
    sb.append("\nDispositivos de entrada:\n")
    for (id in InputDevice.getDeviceIds()) {
        val d = InputDevice.getDevice(id) ?: continue
        val src = d.sources
        val kinds = buildList {
            if (src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) add("tela de toque")
            if (src and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS) add("STYLUS")
            if (src and InputDevice.SOURCE_BLUETOOTH_STYLUS == InputDevice.SOURCE_BLUETOOTH_STYLUS) add("stylus bluetooth")
            if (src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) add("teclado")
            if (src and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) add("mouse")
            if (src and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) add("touchpad")
        }
        if (kinds.isEmpty()) continue
        sb.append("• ${d.name}: ${kinds.joinToString()}\n")
        if (src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN || src and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS) {
            val pr = d.getMotionRange(MotionEvent.AXIS_PRESSURE)
            val tilt = d.getMotionRange(MotionEvent.AXIS_TILT)
            val major = d.getMotionRange(MotionEvent.AXIS_TOUCH_MAJOR)
            sb.append("   pressão: ${pr?.let { "${fmt(it.min)}–${fmt(it.max)} (resolução ${fmt(it.resolution)})" } ?: "eixo ausente"}\n")
            sb.append("   inclinação: ${if (tilt != null) "eixo presente" else "eixo ausente"}\n")
            sb.append("   tamanho do contato: ${major?.let { "${fmt(it.min)}–${fmt(it.max)}" } ?: "eixo ausente"}\n")
        }
    }
    return sb.toString().trim()
}

private fun buildReport(deviceInfo: String, v: PenDiagnosticsView?, threshold: Float): String {
    val sb = StringBuilder("=== Diagnóstico InkFlow ===\n").append(deviceInfo).append("\n\n=== Entrada observada ===\n")
    if (v == null || v.stats.isEmpty()) {
        sb.append("(nenhum toque)\n")
    } else {
        v.stats.forEach { (tool, s) -> sb.append(PenDiagnosticsView.toolName(tool)).append('\n').append(statsText(s)).append("\n\n") }
        sb.append("Toques simultâneos máx.: ${v.maxPointers}\nBotões: 0x${Integer.toHexString(v.buttonStates)}\nHover: ${v.hoverEvents}\nCancelados: ${v.canceledEvents}\n")
        sb.append("Dispositivos: ${v.deviceNames.joinToString()}\n")
        sb.append("Calibração: caneta p95=${fmt(percentile(v.penMajors, 0.95f))}, palma p10=${fmt(percentile(v.palmMajors, 0.10f))}, limiar salvo=${fmt(threshold)}\n")
    }
    sb.append("\n=== Conclusão ===\n").append(conclusion(v))
    return sb.toString()
}
