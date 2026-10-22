@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daymark.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.daymark.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.daymark.app.domain.*
import java.time.*

@Composable fun TaskEditor(
    task: Task?, initialPriority: Priority, day: LocalDate, onDismiss: () -> Unit,
    onSave: (String, Priority, TaskStatus) -> Unit, onDelete: () -> Unit,
    saving: Boolean = false, error: String? = null,
) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var priority by rememberSaveable(task?.id) { mutableStateOf(initialPriority) }
    var status by rememberSaveable(task?.id) { mutableStateOf(task?.status ?: TaskStatus.OPEN) }
    var confirmDelete by remember { mutableStateOf(false) }
    val moved = task?.status == TaskStatus.MOVED
    val dirty = title != (task?.title ?: "") || priority != initialPriority || status != (task?.status ?: TaskStatus.OPEN)
    EditorSheet(dirty = dirty, saving = saving, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (task == null) "A little intention." else "Make it yours.", style = MaterialTheme.typography.headlineMedium)
            Text((task?.day ?: day).label("EEEE, MMMM d, yyyy"), color = Muted)
            OutlinedTextField(value = title, onValueChange = { if (it.length <= 200) title = it }, label = { Text("Task") },
                placeholder = { Text("What would you like to do?") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                readOnly = moved, enabled = !saving, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Text("PRIORITY", color = Muted, style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p -> FilterChip(selected = p == priority, onClick = { priority = p }, enabled = !moved && !saving,
                    label = { Text(p.name) }, leadingIcon = { Icon(painterResource(R.drawable.ic_flag), null, tint = p.color(), modifier = Modifier.size(16.dp)) }) }
            }
            Text("${priority.subtitle}. Unfinished tasks carry into the next day automatically.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (task != null && !moved) {
                Text("STATUS", color = Muted, style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TaskStatus.OPEN to "Open", TaskStatus.DONE to "Done", TaskStatus.STALE to "Stale").forEach { (value, label) ->
                        FilterChip(selected = status == value, onClick = { status = value }, enabled = !saving, label = { Text(label) })
                    }
                }
                if (status == TaskStatus.STALE) Text("No longer needed. Kept in your history, never carried over.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = Clay, style = MaterialTheme.typography.bodyMedium) }
            if (moved) {
                Text("This is a historical snapshot. The task was carried to ${task?.movedTo?.label("MMMM d, yyyy")}.", color = Muted)
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            } else Button(onClick = { onSave(title.trim(), priority, status) }, enabled = title.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (saving) "Saving…" else if (task == null) "Add task" else "Save changes") }
            if (task != null) TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Delete task", color = Clay) }
        }
    }
    if (confirmDelete) DeleteConfirmation("Delete this task?", "This entry will be permanently removed from ${task?.day?.label("MMMM d")}. Other daily snapshots stay in your journal.", { confirmDelete = false }, { confirmDelete = false; onDelete() })
}

@Composable fun EventEditor(event: Event?, onDismiss: () -> Unit, onSave: (Event) -> Unit, onDelete: () -> Unit, saving: Boolean = false, error: String? = null) {
    val context = LocalContext.current
    val initialString by rememberSaveable(event?.id) { mutableStateOf((event?.start ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)).toString()) }
    val initial = LocalDateTime.parse(initialString)
    var title by rememberSaveable(event?.id) { mutableStateOf(event?.title ?: "") }
    var dateString by rememberSaveable(event?.id) { mutableStateOf(initial.toLocalDate().toString()) }
    var timeString by rememberSaveable(event?.id) { mutableStateOf(initial.toLocalTime().toString()) }
    var repeat by rememberSaveable(event?.id) { mutableStateOf(event?.repeat ?: Repeat.ONCE) }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = LocalDate.parse(dateString)
    val time = LocalTime.parse(timeString)
    val start = LocalDateTime.of(date, time)
    val invalidPast = repeat == Repeat.ONCE && start <= LocalDateTime.now() && (event == null || event.start != start)

    val dirty = title != (event?.title ?: "") || start != initial || repeat != (event?.repeat ?: Repeat.ONCE)
    EditorSheet(dirty = dirty, saving = saving, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (event == null) "Something to remember." else "Keep it close.", style = MaterialTheme.typography.headlineMedium)
            Text("Give it a moment. We'll give you a heads-up.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = title, onValueChange = { if (it.length <= 200) title = it }, label = { Text("Event name") },
                placeholder = { Text("An exam, a birthday, a good plan…") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !saving,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Text("WHEN", color = Muted, style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day -> dateString = LocalDate.of(year, month + 1, day).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Icon(painterResource(R.drawable.ic_calendar_month), null, modifier = Modifier.padding(end = 10.dp).size(20.dp)); Text(date.label("EEE, MMM d, yyyy")) }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute -> timeString = LocalTime.of(hour, minute).toString() }, time.hour, time.minute, true).show()
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Icon(painterResource(R.drawable.ic_schedule), null, modifier = Modifier.padding(end = 10.dp).size(20.dp)); Text("Time · ${start.label("HH:mm")}") }
            if (invalidPast) Text("Choose a future date and time for a one-time event.", color = Clay, style = MaterialTheme.typography.bodyMedium)
            Text("REPEAT", color = Muted, style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { value -> FilterChip(selected = repeat == value, onClick = { repeat = value }, enabled = !saving, label = { Text(if (value == Repeat.ONCE) "Once" else value.name.lowercase().replaceFirstChar { it.titlecase() }) }) }
            }
            if (repeat == Repeat.MONTHLY || repeat == Repeat.YEARLY) Text("Shorter months use their last day. The original date is kept for the next occurrence.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A timely nudge", color = Forest, style = MaterialTheme.typography.titleMedium)
                    Text("24 hours before · 30 minutes before\nShown in Today the day before and on the day.", color = Forest, style = MaterialTheme.typography.bodyMedium)
                }
            }
            error?.let { Text(it, color = Clay, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { onSave(Event(id = event?.id ?: 0, title = title.trim(), start = start, repeat = repeat, version = event?.version ?: 1)) },
                enabled = title.isNotBlank() && !invalidPast && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (saving) "Saving…" else if (event == null) "Add event" else "Save changes") }
            if (event != null) TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Delete event", color = Clay) }
        }
    }
    if (confirmDelete) DeleteConfirmation("Delete this event?", "This removes the event, its future repeats, and its reminders.", { confirmDelete = false }, { confirmDelete = false; onDelete() })
}

@Composable private fun EditorSheet(dirty: Boolean, saving: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (!saving) { if (dirty) confirmDiscard = true else onDismiss() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value == SheetValue.Hidden && (dirty || saving)) {
            if (!saving) confirmDiscard = true
            false
        } else true
    })
    ModalBottomSheet(onDismissRequest = requestClose, sheetState = sheetState, containerColor = Paper, content = content)
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") }, text = { Text("Your changes haven't been saved yet.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard", color = Clay) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
}

@Composable private fun DeleteConfirmation(title: String, body: String, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, style = MaterialTheme.typography.titleLarge) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = delete) { Text("Delete", color = Clay) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Keep it") } })
}

@Composable fun CalendarDialog(selected: LocalDate, dismiss: () -> Unit, choose: (LocalDate) -> Unit) {
    val dateState = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = { dateState.selectedDateMillis?.let { choose(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }, enabled = dateState.selectedDateMillis != null) { Text("Open day") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) { DatePicker(state = dateState) }
}
