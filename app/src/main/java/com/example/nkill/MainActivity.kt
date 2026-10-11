package com.example.nkill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
    }
}

private fun listenerEnabled(c: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

@Composable
fun Screen() {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(RuleStore.load(ctx)) }
    var shState by remember { mutableStateOf(Sh.state()) }
    var listenerOn by remember { mutableStateOf(listenerEnabled(ctx)) }
    var adding by remember { mutableStateOf(false) }

    // Re-sync blocks whenever Shizuku (re)appears or permission is granted.
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { shState = Sh.state(); Sh.syncBlocks(ctx) }
        val dead = Shizuku.OnBinderDeadListener { shState = Sh.state() }
        val perm = Shizuku.OnRequestPermissionResultListener { _, _ -> shState = Sh.state(); Sh.syncBlocks(ctx) }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(perm)
        Sh.syncBlocks(ctx)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(perm)
        }
    }

    fun update(new: List<Rule>) { rules = new; RuleStore.save(ctx, new) }

    Column(
        Modifier.statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NKill", style = MaterialTheme.typography.headlineMedium)

        // --- Shizuku card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (shState) {
                        Sh.State.NOT_RUNNING -> "Shizuku: not running (start it, e.g. via wireless debugging)"
                        Sh.State.NO_PERMISSION -> "Shizuku: running, permission needed"
                        Sh.State.READY -> "Shizuku: ready"
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (shState) {
                        Sh.State.NOT_RUNNING -> Button(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?.let { ctx.startActivity(it) }
                        }) { Text("Open Shizuku") }
                        Sh.State.NO_PERMISSION -> Button(onClick = { Shizuku.requestPermission(1) }) { Text("Allow") }
                        Sh.State.READY -> {}
                    }
                    OutlinedButton(onClick = {
                        shState = Sh.state(); listenerOn = listenerEnabled(ctx)
                    }) { Text("Refresh") }
                }
            }
        }

        // --- Listener card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (listenerOn) "Notification access: granted" else "Notification access: missing (needed for text rules)")
                if (!listenerOn) Button(onClick = {
                    if (Sh.ready()) thread { Sh.allowListener(ctx); listenerOn = listenerEnabled(ctx) }
                    else ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(if (Sh.ready()) "Grant via Shizuku" else "Open settings") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("+ Add") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (r.text.isEmpty()) "All notifications (system block)" else "Contains: \"${r.text}\"",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            val rest = rules - r
                            update(rest)
                            // Lift the system block if no other app-level rule still needs it.
                            if (r.text.isEmpty() && rest.none { it.pkg == r.pkg && it.text.isEmpty() } && Sh.ready())
                                thread { Sh.unblockApp(r.pkg) }
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (adding) AddDialog(
        onDismiss = { adding = false },
        onAdd = { rule ->
            adding = false
            update(rules + rule)
            if (rule.text.isEmpty() && Sh.ready()) thread { Sh.blockApp(rule.pkg) }
        }
    )
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onAdd: (Rule) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) } // pkg to label
    var text by remember { mutableStateOf("") }

    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.second ?: "Pick an app") },
        text = {
            if (picked == null) Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }) { a ->
                        Column(Modifier.fillMaxWidth().clickable { picked = a }.padding(vertical = 8.dp)) {
                            Text(a.second)
                            Text(a.first, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else Column {
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Text contains (empty = everything)") }
                )
            }
        },
        confirmButton = {
            if (picked != null) TextButton(onClick = {
                onAdd(Rule(picked!!.first, picked!!.second, text.trim()))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
