package com.taboia872.androidassist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.i18n.Strings
import com.taboia872.androidassist.tasks.ScheduledTask
import com.taboia872.androidassist.tasks.TaskScheduler
import com.taboia872.androidassist.tasks.TaskRunnerService
import com.taboia872.androidassist.tasks.TaskStore
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tools.ToolAbility
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.Date
import java.util.Locale

/**
 * The scheduled tasks: what the assistant does BY ITSELF.
 *
 * Each task has a prompt, a time and the list of tools it may use —
 * chosen when the task is created. The app runs it in the background
 * and saves the report in the pointed folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(TaskStore.all(context)) }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ScheduledTask?>(null) }

    if (creating || editing != null) {
        TaskEditor(
            initial = editing,
            onBack = { creating = false; editing = null },
            onSaved = { task ->
                TaskStore.upsert(context, task)
                TaskScheduler.schedule(context, task)
                tasks = TaskStore.all(context)
                creating = false
                editing = null
            }
        )
        return
    }

    toDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(Strings.t(Strings.taskDelete)) },
            text = { Text(Strings.t(Strings.taskDeleteConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    TaskScheduler.cancel(context, task.id)
                    TaskStore.remove(context, task.id)
                    tasks = TaskStore.all(context)
                    toDelete = null
                }) { Text(Strings.t(Strings.deleteChat)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(Strings.t(Strings.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.t(Strings.tasksTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings.t(Strings.back))
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, Strings.t(Strings.newTask))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        Strings.t(Strings.taskNoTasks),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tasks.size) { i ->
                        val task = tasks[i]
                        TaskRow(
                            task = task,
                            onRunNow = { TaskRunnerService.start(context, task.id) },
                            onToggle = { on ->
                                val updated = task.copy(enabled = on)
                                TaskStore.upsert(context, updated)
                                TaskScheduler.schedule(context, updated)
                                tasks = TaskStore.all(context)
                            },
                            onClick = { editing = task },
                            onDelete = { toDelete = task }
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    Strings.t(Strings.taskNew),
                    modifier = Modifier
                        .clickable { creating = true }
                        .padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** One task in the list. */
@Composable
private fun TaskRow(
    task: ScheduledTask,
    onRunNow: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val next = task.nextRunAt()
    val nextLabel = if (next > 0) {
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(next))
    } else ""
    ListItem(
        headlineContent = {
            Text(
                task.title.ifBlank { Strings.t(Strings.tasksTitle) },
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                buildString {
                    append(task.scheduleLabel())
                    append(" ")
                    append(task.timeLabel())
                    if (nextLabel.isNotBlank()) append(" — ").append(nextLabel)
                    val persona = task.personaId.takeIf { it.isNotBlank() }
                        ?.let { id -> AppSettings.personas().firstOrNull { it.id == id } }
                    if (persona != null) {
                        append(" — ")
                        append(persona.name)
                    }
                    if (task.lastResult.isNotBlank()) {
                        append("\n")
                        append(task.lastResult)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Switch(checked = task.enabled, onCheckedChange = onToggle)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, Strings.t(Strings.taskRunNow))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, Strings.t(Strings.taskDelete))
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * The create/edit form: the prompt, the time, the recurrence and the
 * tools the task may use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(
    initial: ScheduledTask?,
    onBack: () -> Unit,
    onSaved: (ScheduledTask) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var repeatMode by remember {
        mutableStateOf(initial?.repeatMode ?: ScheduledTask.REPEAT_DAILY)
    }
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var days by remember { mutableStateOf(initial?.daysOfWeek ?: listOf(2)) }
    var tools by remember { mutableStateOf(initial?.allowedTools ?: emptyList()) }
    var onceAt by remember { mutableStateOf(initial?.onceAt ?: 0L) }
    var personaId by remember { mutableStateOf(initial?.personaId ?: "") }
    var showHour by remember { mutableStateOf(false) }
    var showMinute by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showPersona by remember { mutableStateOf(false) }

    val toolOptions = listOf(
        "web_search" to Strings.t(Strings.webSearchSection),
        ToolAbility.ALARM to Strings.t(Strings.abilityAlarm),
        ToolAbility.TIMER to Strings.t(Strings.abilityTimer),
        ToolAbility.CALENDAR to Strings.t(Strings.abilityCalendar),
        ToolAbility.OPEN_APP to Strings.t(Strings.abilityOpenApp),
        ToolAbility.CLIPBOARD to Strings.t(Strings.abilityClipboard),
        ToolAbility.SHARE to Strings.t(Strings.abilityShare),
        ToolAbility.LOCATION to Strings.t(Strings.abilityLocation),
        ToolAbility.MAP to Strings.t(Strings.abilityMap),
        ToolAbility.FILES to Strings.t(Strings.abilityFiles),
        ToolAbility.MESSAGING to Strings.t(Strings.abilityMessaging),
        ToolAbility.OPEN_URL to Strings.t(Strings.abilityOpenUrl),
        "memory" to Strings.t(Strings.memoriesManageLabel)
    )
    val dayNames = remember {
        java.text.DateFormatSymbols.getInstance().shortWeekdays
    }

    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = datePickerInitialMillis(onceAt)
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    onceAt = datePickerToLocal(
                        dateState.selectedDateMillis ?: 0L, hour, minute
                    )
                    showDate = false
                }) { Text(Strings.t(Strings.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(Strings.t(Strings.cancel)) }
            }
        ) {
            DatePicker(state = dateState)
        }
    }
    if (showPersona) {
        AlertDialog(
            onDismissRequest = { showPersona = false },
            title = { Text(Strings.t(Strings.taskPersona)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                personaId = ""
                                showPersona = false
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = personaId.isBlank(),
                            onClick = {
                                personaId = ""
                                showPersona = false
                            }
                        )
                        Text(Strings.t(Strings.taskPersonaDefault))
                    }
                    AppSettings.personas().forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    personaId = p.id
                                    showPersona = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = personaId == p.id,
                                onClick = {
                                    personaId = p.id
                                    showPersona = false
                                }
                            )
                            Text(p.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPersona = false }) { Text(Strings.t(Strings.ok)) }
            }
        )
    }
    if (showHour) {
        NumberPickerDialog(
            title = Strings.t(Strings.taskTime),
            options = (0..23).map { it to String.format("%02d:00", it) },
            current = hour,
            onSelected = { hour = it; showHour = false },
            onDismiss = { showHour = false }
        )
    }
    if (showMinute) {
        NumberPickerDialog(
            title = Strings.t(Strings.taskTime),
            options = (0..59).map { it to String.format("%02d min", it) },
            current = minute,
            onSelected = { minute = it; showMinute = false },
            onDismiss = { showMinute = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initial == null) Strings.t(Strings.newTask)
                        else Strings.t(Strings.editTask)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings.t(Strings.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val base = initial ?: ScheduledTask(title = title, prompt = prompt)
                        onSaved(
                            base.copy(
                                title = title.trim(),
                                prompt = prompt.trim(),
                                repeatMode = repeatMode,
                                hour = hour,
                                minute = minute,
                                daysOfWeek = days,
                                allowedTools = tools,
                                onceAt = onceAt,
                                personaId = personaId
                            )
                        )
                    }) {
                        Icon(Icons.Default.Check, Strings.t(Strings.save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(Strings.t(Strings.taskName)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(Strings.t(Strings.taskPrompt)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                Strings.t(Strings.taskRepeat),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = repeatMode == ScheduledTask.REPEAT_DAILY,
                    onClick = { repeatMode = ScheduledTask.REPEAT_DAILY },
                    label = { Text(Strings.t(Strings.repeatDaily)) }
                )
                FilterChip(
                    selected = repeatMode == ScheduledTask.REPEAT_WEEKLY,
                    onClick = { repeatMode = ScheduledTask.REPEAT_WEEKLY },
                    label = { Text(Strings.t(Strings.repeatWeekly)) }
                )
                FilterChip(
                    selected = repeatMode == ScheduledTask.REPEAT_ONCE,
                    onClick = { repeatMode = ScheduledTask.REPEAT_ONCE },
                    label = { Text(Strings.t(Strings.repeatOnce)) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Strings.t(Strings.taskTime),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeField(String.format("%02d", hour)) { showHour = true }
                    Text(":", style = MaterialTheme.typography.titleMedium)
                    TimeField(String.format("%02d", minute)) { showMinute = true }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // The DATE — only for the "once" mode (the daily/weekly ones
            // repeat by themselves).
            if (repeatMode == ScheduledTask.REPEAT_ONCE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Strings.t(Strings.taskDate),
                        style = MaterialTheme.typography.titleSmall
                    )
                    TimeField(
                        if (onceAt > 0) {
                            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                .format(Date(onceAt))
                        } else {
                            Strings.t(Strings.taskDatePick)
                        }
                    ) { showDate = true }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // The PERSONA: its prompt, its model, its provider.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Strings.t(Strings.taskPersona),
                    style = MaterialTheme.typography.titleSmall
                )
                TimeField(
                    personaId.takeIf { it.isNotBlank() }
                        ?.let { id -> AppSettings.personas().firstOrNull { it.id == id }?.name }
                        ?: Strings.t(Strings.taskPersonaDefault)
                ) { showPersona = true }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (repeatMode == ScheduledTask.REPEAT_WEEKLY) {
                Text(
                    Strings.t(Strings.taskDays),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (1..7).forEach { day ->
                        val selected = day in days
                        Surface(
                            modifier = Modifier
                                .clickable {
                                    days = if (selected) days - day else days + day
                                },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                dayNames.getOrElse(day) { "" },
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                Strings.t(Strings.taskTools),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                Strings.t(Strings.taskToolsHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            toolOptions.forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            tools = if (id in tools) tools - id else tools + id
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = id in tools,
                        onCheckedChange = { on ->
                            tools = if (on) tools + id else tools - id
                        }
                    )
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** A tappable field that shows a value (the hour, the minute, a date). */
@Composable
private fun TimeField(value: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            value,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * The UTC midnight of a day — what the Material3 DatePicker expects as
 * its selection.
 */
private fun datePickerInitialMillis(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = if (millis > 0) millis else System.currentTimeMillis()
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.clear()
    utc.set(
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    )
    return utc.timeInMillis
}

/**
 * The selected day (the DatePicker returns the UTC midnight) + the
 * chosen time -> the local moment of the task.
 */
private fun datePickerToLocal(selected: Long, hour: Int, minute: Int): Long {
    if (selected <= 0) return 0L
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = selected
    val local = Calendar.getInstance()
    local.clear()
    local.set(
        utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
        hour, minute, 0
    )
    return local.timeInMillis
}
