package app.sirenscout

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What changed in each version, from assets/RELEASE_NOTES.md (newest first). */
object ReleaseNotes {
    data class Release(val title: String, val lines: List<String>)

    /** This app's notes: Siren Test shows every version, Siren only its own (not the -test builds). */
    fun load(context: Context): List<Release> =
        runCatching { context.assets.open("RELEASE_NOTES.md").bufferedReader().use { forChannel(parse(it.readText()), BuildConfig.TEST_BUILD) } }
            .getOrDefault(emptyList())

    val Release.version get() = title.substringBefore(' ')
    val Release.isTest get() = version.endsWith("-test")

    fun forChannel(releases: List<Release>, test: Boolean) = if (test) releases else releases.filterNot { it.isTest }

    /** "## version · date" starts a release; "- " lines are its bullets; other text is kept as a paragraph. */
    fun parse(text: String): List<Release> {
        val releases = mutableListOf<Release>()
        var title: String? = null
        var lines = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    title?.let { releases += Release(it, lines) }
                    title = line.removePrefix("## ").trim()
                    lines = mutableListOf()
                }
                title != null && line.isNotEmpty() -> lines += line
            }
        }
        title?.let { releases += Release(it, lines) }
        return releases
    }
}

@Composable
fun ReleaseNotesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val releases = remember { ReleaseNotes.load(context) }
    FullScreenPage(onDismiss = onClose) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("What's new", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            if (BuildConfig.TEST_BUILD) "You have ${appVersion(context)}. Versions ending in -test are Siren Test, where new features are tried first; the others are Siren."
                            else "You have ${appVersion(context)}.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                    if (releases.isEmpty()) item { Text("No release notes yet.", style = MaterialTheme.typography.bodyMedium) }
                    items(releases) { r ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            r.lines.forEach { line ->
                                if (line.startsWith("- ")) Row {
                                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                                    Text(line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                                } else Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
