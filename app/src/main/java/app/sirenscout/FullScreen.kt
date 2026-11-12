package app.sirenscout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen pages (Settings, the manual, device details) drawn in the app's own window, on top
 * of everything else, rather than as separate dialog windows. On Android 17 a dialog window is
 * fitted between the system bars yet still draws under the gesture bar, so the bottom was hidden.
 */
private class PageStack {
    val pages = mutableStateListOf<Pair<Any, @Composable () -> Unit>>()
}

private val LocalPageStack = staticCompositionLocalOf<PageStack?> { null }

/** Hosts [FullScreenPage]s opened anywhere inside [content]. */
@Composable
fun FullScreenHost(content: @Composable () -> Unit) {
    val stack = remember { PageStack() }
    CompositionLocalProvider(LocalPageStack provides stack) {
        Box(Modifier.fillMaxSize()) {
            content()
            stack.pages.forEach { (id, page) -> key(id) { Box(Modifier.fillMaxSize()) { page() } } }
        }
    }
}

/** Shows [content] over the whole app while this is in the composition; back calls [onDismiss]. */
@Composable
fun FullScreenPage(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val stack = LocalPageStack.current
    if (stack == null) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
        return
    }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(stack) {
        val entry: Pair<Any, @Composable () -> Unit> = Any() to {
            BackHandler { latestDismiss() }
            latestContent()
        }
        stack.pages.add(entry)
        onDispose { stack.pages.remove(entry) }
    }
}
