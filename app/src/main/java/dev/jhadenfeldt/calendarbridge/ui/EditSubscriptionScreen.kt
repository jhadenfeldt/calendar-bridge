package dev.jhadenfeldt.calendarbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jhadenfeldt.calendarbridge.android.IcuWindowsZoneLookup
import dev.jhadenfeldt.calendarbridge.core.IcsParseException
import dev.jhadenfeldt.calendarbridge.core.IcsParser
import dev.jhadenfeldt.calendarbridge.core.TimezoneResolver
import dev.jhadenfeldt.calendarbridge.data.Subscription
import dev.jhadenfeldt.calendarbridge.data.SubscriptionRepository
import dev.jhadenfeldt.calendarbridge.sync.FeedFetcher
import dev.jhadenfeldt.calendarbridge.sync.FetchResult
import dev.jhadenfeldt.calendarbridge.sync.SyncEngine
import dev.jhadenfeldt.calendarbridge.sync.SyncScheduler
import dev.jhadenfeldt.calendarbridge.sync.normalizeUrl
import dev.jhadenfeldt.calendarbridge.sync.redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** Add (subscriptionId == null) or edit a subscription. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSubscriptionScreen(subscriptionId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = SubscriptionRepository.get(context)
    val subs by repo.changes.collectAsStateWithLifecycle()
    val existing = subscriptionId?.let { id -> subs.firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(existing?.url ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: Subscription.COLORS.first()) }
    var interval by rememberSaveable { mutableStateOf(existing?.intervalMinutes ?: 60) }
    var reminder by rememberSaveable { mutableStateOf(existing?.defaultReminderMinutes) }
    // Adding is two steps: check the URL, then confirm name and options.
    var checked by rememberSaveable { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun hasCalendarPermission() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun check() {
        busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val f = FeedFetcher().fetch(normalizeUrl(url), null, null, force = true)) {
                    is FetchResult.Ok -> try {
                        val parsed = IcsParser(TimezoneResolver(IcuWindowsZoneLookup, ZoneId.systemDefault())).parse(f.body)
                        Result.success(parsed.calendarName)
                    } catch (e: IcsParseException) { Result.failure(e) }
                    is FetchResult.Failed -> Result.failure(IllegalStateException(f.message))
                    FetchResult.NotModified -> Result.success(null)
                }
            }
            busy = false
            result.onSuccess { calName -> if (name.isBlank()) name = calName ?: "Calendar"; checked = true }
                .onFailure { error = it.message ?: "Could not read that URL" }
        }
    }

    fun save() {
        fun Subscription.withEdits() =
            copy(name = name.trim().ifEmpty { "Calendar" }, color = color, intervalMinutes = interval, defaultReminderMinutes = reminder)
        val reminderChanged = existing != null && existing.defaultReminderMinutes != reminder
        // Edits go through update() so a sync finishing meanwhile keeps its calendarId/etag/status.
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) {
            repo.upsert(Subscription(id = id, url = normalizeUrl(url), name = name, color = color).withEdits())
        } else {
            repo.update(id) { it.withEdits() }
        }
        val sub = repo.get(id) ?: return
        SyncScheduler.schedule(context, sub)
        if (existing == null) {
            SyncScheduler.syncNow(context, sub.id, force = false)
        } else {
            scope.launch(Dispatchers.IO) { SyncEngine(context.applicationContext).updateCalendar(sub.id) }
            // The default reminder is written into each event, so a change needs a full rewrite.
            if (reminderChanged) SyncScheduler.syncNow(context, sub.id, force = true)
        }
        onDone()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) save() else error = "Calendar permission is needed to create calendars."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add calendar" else existing.name) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (existing == null) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; checked = false },
                    label = { Text("Calendar URL (.ics or webcal://)") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("URL: ${redact(existing.url)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (!checked) {
                Button(onClick = ::check, enabled = url.isNotBlank() && !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check URL")
                }
                return@Column
            }

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Section("Color") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Subscription.COLORS.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
            }
            Section("Sync") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Subscription.INTERVALS.forEach { m -> FilterChip(selected = interval == m, onClick = { interval = m }, label = { Text(intervalLabel(m)) }) }
                }
            }
            Section("Default reminder") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Subscription.REMINDERS.forEach { m ->
                        FilterChip(selected = reminder == m, onClick = { reminder = m }, label = { Text(m?.let { "$it min" } ?: "None") })
                    }
                }
            }

            Button(
                onClick = { if (hasCalendarPermission()) save() else permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existing == null) "Subscribe" else "Save") }

            if (existing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { SyncScheduler.syncNow(context, existing.id, force = false) }, modifier = Modifier.weight(1f)) { Text("Sync now") }
                    OutlinedButton(onClick = { SyncScheduler.syncNow(context, existing.id, force = true) }, modifier = Modifier.weight(1f)) { Text("Force sync") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }

                HorizontalDivider()
                Section("Details") {
                    Text("${existing.eventCount} events · ${lastSyncLabel(existing)}", style = MaterialTheme.typography.bodyMedium)
                    existing.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
                    if (existing.tzidMappings.isNotEmpty()) {
                        Text("Timezones", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        existing.tzidMappings.forEach { (from, to) -> Text("$from → $to", style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (existing.warnings.isNotEmpty()) {
                        Text("Warnings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        existing.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Delete calendar and all its events?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { SyncEngine(context.applicationContext).remove(existing.id) }
                        onDone()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
