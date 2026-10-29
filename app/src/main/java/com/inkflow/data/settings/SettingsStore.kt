package com.inkflow.data.settings

import android.content.Context
import androidx.core.content.edit
import com.inkflow.core.model.EraserMode
import com.inkflow.core.model.Tool
import com.inkflow.ink.view.InputConfig
import com.inkflow.ink.view.PressureMode
import com.inkflow.ink.view.ToolConfig
import com.inkflow.ink.view.TouchMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Seguir o sistema"), LIGHT("Claro"), DARK("Escuro") }

/** Predefinição de caneta salva pelo usuário. */
data class PenPreset(val tool: Tool, val color: Int, val width: Float)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val input: InputConfig = InputConfig(),
    val tools: ToolConfig = ToolConfig(),
    val presets: List<PenPreset> = DEFAULT_PRESETS,
) {
    companion object {
        val DEFAULT_PRESETS = listOf(
            PenPreset(Tool.PEN, 0xFF111318.toInt(), 1.4f),
            PenPreset(Tool.PEN, 0xFF1F4E9C.toInt(), 1.4f),
            PenPreset(Tool.PEN, 0xFFC62828.toInt(), 1.4f),
            PenPreset(Tool.HIGHLIGHTER, 0x66FFEB3B, 12f),
        )
    }
}

/**
 * Ajustes do usuário em SharedPreferences (pequeno, local, sem dependências extras),
 * expostos como StateFlow para a interface reagir.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("inkflow_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        save(next)
    }

    fun updateInput(transform: (InputConfig) -> InputConfig) = update { it.copy(input = transform(it.input)) }
    fun updateTools(transform: (ToolConfig) -> ToolConfig) = update { it.copy(tools = transform(it.tools)) }

    private fun load(): AppSettings {
        val d = AppSettings()
        val di = d.input
        val dt = d.tools
        return AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: d.theme,
            input = InputConfig(
                touchMode = TouchMode.of(prefs.getString("touchMode", null)),
                palmRejection = prefs.getBoolean("palmRejection", di.palmRejection),
                palmThresholdPx = prefs.getFloat("palmThresholdPx", di.palmThresholdPx),
                smoothing = prefs.getFloat("smoothing", di.smoothing),
                prediction = prefs.getBoolean("prediction", di.prediction),
                pressureMode = PressureMode.of(prefs.getString("pressureMode", null)),
                tapGestures = prefs.getBoolean("tapGestures", di.tapGestures),
                palmGuard = prefs.getBoolean("palmGuard", di.palmGuard),
                palmGuardTop = prefs.getFloat("palmGuardTop", di.palmGuardTop),
                stylusDetected = prefs.getBoolean("stylusDetected", di.stylusDetected),
            ),
            tools = ToolConfig(
                tool = Tool.entries.firstOrNull { it.name == prefs.getString("tool", null) } ?: dt.tool,
                penColor = prefs.getInt("penColor", dt.penColor),
                penWidth = prefs.getFloat("penWidth", dt.penWidth),
                pencilColor = prefs.getInt("pencilColor", dt.pencilColor),
                pencilWidth = prefs.getFloat("pencilWidth", dt.pencilWidth),
                highlighterColor = prefs.getInt("highlighterColor", dt.highlighterColor),
                highlighterWidth = prefs.getFloat("highlighterWidth", dt.highlighterWidth),
                eraserMode = EraserMode.entries.firstOrNull { it.name == prefs.getString("eraserMode", null) } ?: dt.eraserMode,
                eraserSizeDp = prefs.getFloat("eraserSizeDp", dt.eraserSizeDp),
            ),
            presets = decodePresets(prefs.getString("presets", null)) ?: d.presets,
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit {
            putString("theme", s.theme.name)
            putString("touchMode", s.input.touchMode.name)
            putBoolean("palmRejection", s.input.palmRejection)
            putFloat("palmThresholdPx", s.input.palmThresholdPx)
            putFloat("smoothing", s.input.smoothing)
            putBoolean("prediction", s.input.prediction)
            putString("pressureMode", s.input.pressureMode.name)
            putBoolean("tapGestures", s.input.tapGestures)
            putBoolean("palmGuard", s.input.palmGuard)
            putFloat("palmGuardTop", s.input.palmGuardTop)
            putBoolean("stylusDetected", s.input.stylusDetected)
            putString("tool", s.tools.tool.name)
            putInt("penColor", s.tools.penColor)
            putFloat("penWidth", s.tools.penWidth)
            putInt("pencilColor", s.tools.pencilColor)
            putFloat("pencilWidth", s.tools.pencilWidth)
            putInt("highlighterColor", s.tools.highlighterColor)
            putFloat("highlighterWidth", s.tools.highlighterWidth)
            putString("eraserMode", s.tools.eraserMode.name)
            putFloat("eraserSizeDp", s.tools.eraserSizeDp)
            putString("presets", encodePresets(s.presets))
        }
    }

    private fun encodePresets(list: List<PenPreset>): String =
        list.joinToString(";") { "${it.tool.name},${it.color},${it.width}" }

    private fun decodePresets(raw: String?): List<PenPreset>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { item ->
            val p = item.split(",")
            if (p.size != 3) return@mapNotNull null
            val tool = Tool.entries.firstOrNull { it.name == p[0] } ?: return@mapNotNull null
            val color = p[1].toIntOrNull() ?: return@mapNotNull null
            val width = p[2].toFloatOrNull() ?: return@mapNotNull null
            PenPreset(tool, color, width)
        }
    }
}
