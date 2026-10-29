package com.inkflow

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.inkflow.ui.editor.EditorScreen
import com.inkflow.ui.home.HomeScreen
import com.inkflow.ui.home.HomeTab
import com.inkflow.ui.theme.InkFlowTheme

class MainActivity : ComponentActivity() {

    /** Atalhos de teclado físico (o TL10 tem capa-teclado). Registrado pelo editor. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            val settings by container.settings.state.collectAsState()
            InkFlowTheme(settings.theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppRoot(container, this)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && keyHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Editor(val notebookId: String) : Screen
}

@Composable
private fun AppRoot(container: AppContainer, activity: MainActivity) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var homeTab by remember { mutableStateOf(HomeTab.HOME) }

    val modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            container = container,
            tab = homeTab,
            onTabChange = { homeTab = it },
            onOpenNotebook = { screen = Screen.Editor(it) },
            modifier = modifier,
        )
        is Screen.Editor -> {
            BackHandler { screen = Screen.Home }
            EditorScreen(
                container = container,
                activity = activity,
                notebookId = s.notebookId,
                onClose = { screen = Screen.Home },
                modifier = modifier,
            )
        }
    }
}
