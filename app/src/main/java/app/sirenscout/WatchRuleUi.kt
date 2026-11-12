package app.sirenscout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val PREFIX_FORMAT = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){1,5}$")

/**
 * Creates a watch rule, pre-filled from [from] when made from a detection. The value field
 * follows the chosen kind, so switching between address and name swaps in a sensible default.
 */
@Composable
fun WatchRuleDialog(from: Detection?, onDismiss: () -> Unit) {
    fun default(kind: WatchRule.Kind) = when (kind) {
        WatchRule.Kind.ADDRESS -> from?.id.orEmpty()
        WatchRule.Kind.PREFIX -> from?.id?.take(8).orEmpty()
        WatchRule.Kind.NAME_EXACT, WatchRule.Kind.NAME_CONTAINS -> from?.name.orEmpty()
    }
    val startKind = if (from?.name != null) WatchRule.Kind.NAME_EXACT else WatchRule.Kind.ADDRESS
    var kind by remember { mutableStateOf(startKind) }
    var value by remember { mutableStateOf(default(startKind)) }
    var label by remember { mutableStateOf(from?.signature?.label ?: from?.name ?: from?.guess ?: "") }
    var category by remember { mutableStateOf(from?.signature?.category?.takeIf { it in WatchRules.CATEGORIES } ?: Category.POLICE) }

    val isAddress = kind == WatchRule.Kind.ADDRESS || kind == WatchRule.Kind.PREFIX
    val valid = value.isNotBlank() && label.isNotBlank() && (!isAddress || PREFIX_FORMAT.matches(value.trim()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (from == null) "New watch rule" else "Watch for this") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Matching devices are shown and alert like built-in signatures, marked as your rule.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                WatchRule.Kind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == k, onClick = { kind = k; value = default(k) })
                        Text(k.describe.replaceFirstChar(Char::uppercase))
                    }
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(if (isAddress) "Address (AA:BB:CC…)" else "Name or network name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = value.isNotBlank() && isAddress && !PREFIX_FORMAT.matches(value.trim()),
                )
                if (from?.source == Source.BLE && isAddress) {
                    Text(
                        "Many Bluetooth devices change their address every few minutes. A name rule is more reliable when there's a name.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300),
                    )
                }
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Call it") }, placeholder = { Text("e.g. County sheriff body camera") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WatchRules.CATEGORIES.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                WatchRules.add(kind, value, label, category)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings section listing the user's rules. */
@Composable
fun WatchRuleList() {
    val rules by WatchRules.rules.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    if (adding) WatchRuleDialog(from = null, onDismiss = { adding = false })

    Text("Your watch rules (${rules.size})", style = MaterialTheme.typography.titleSmall)
    if (rules.isEmpty()) {
        Text(
            "Spotted local equipment that Siren doesn't know? Tap it, then Watch - or add a rule here.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
    rules.forEach { rule ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.bodyMedium)
                Text("${rule.category.label} · ${rule.description}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            TextButton(onClick = { WatchRules.remove(rule.id) }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add watch rule") }
}
