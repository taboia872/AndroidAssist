package com.taboia872.androidassist.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.taboia872.androidassist.MainActivity
import com.taboia872.androidassist.llm.LocalServer
import com.taboia872.androidassist.llm.LlmProviders
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tools.Workspace
import com.taboia872.androidassist.ui.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs a scheduled task in the FOREGROUND.
 *
 * The turn happens with the app closed: the service keeps the process
 * alive while the model answers, the result is saved as a Markdown file
 * in the pointed folder and a notification points to the conversation.
 */
class TaskRunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: ""
        startForegroundCompat("Executando a tarefa…")
        scope.launch {
            try {
                runTask(taskId)
            } catch (e: Exception) {
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The turn of the task + the result (file + notification). */
    private suspend fun runTask(taskId: String) {
        val app = applicationContext
        val task = TaskStore.byId(app, taskId) ?: return
        val session = ChatSession(app)
        // One conversation per task: the notification reopens it.
        session.chatId = task.chatId()
        // The session would post its own "the model answered" notification:
        // the task has its own, with the result.
        session.suppressBackgroundNotification = true
        // The local server down? The task runs with the CLOUD model
        // (the user asked for this fallback).
        val cfg = AppSettings.config.value
        val override = if (cfg.providerId == LlmProviders.LOCAL.id &&
            !LocalServer.isUp(cfg.baseUrl)
        ) {
            cloudFallback(cfg)
        } else null
        session.send(
            task.prompt,
            allowedTools = task.allowedTools,
            cfgOverride = override
        )
        // The session runs the turn in its own scope: wait for it to end.
        var waited = 0
        while (session.busy && waited < MAX_TURN_MS) {
            delay(150)
            waited += 150
        }
        val answer = lastAnswerText(session)
        saveResult(app, task, answer)
        TaskStore.upsert(
            app,
            task.copy(
                lastRunAt = System.currentTimeMillis(),
                lastResult = answer.replace(Regex("\\s+"), " ").trim().take(160)
            )
        )
        // Arm the next run (the "once" mode has none).
        TaskScheduler.schedule(app, task)
        notifyResult(app, task, answer)
    }

    /**
     * The cloud configuration to use when the local server is down: the
     * first cloud provider with a key and the last model used with one.
     */
    private fun cloudFallback(
        cfg: com.taboia872.androidassist.settings.AppConfig
    ): com.taboia872.androidassist.settings.AppConfig? {
        if (cfg.lastCloudModel.isBlank()) return null
        val provider = LlmProviders.ALL.firstOrNull { p ->
            p.id != LlmProviders.LOCAL.id &&
                p.id != "custom" &&
                p.defaultBaseUrl.isNotBlank() &&
                AppSettings.keysFor(p.id).isNotEmpty()
        } ?: return null
        return cfg.copy(
            providerId = provider.id,
            baseUrl = provider.defaultBaseUrl,
            model = cfg.lastCloudModel
        )
    }

    /** The text of the last answer of the session. */
    private fun lastAnswerText(session: ChatSession): String {
        val last = session.messages.lastOrNull { it.role == "assistant" } ?: return ""
        return last.blocks
            .filter { it.kind == "answer" }
            .map { it.text }
            .joinToString("\n\n")
            .trim()
    }

    /** Save the report in the pointed folder (Markdown). */
    private fun saveResult(context: Context, task: ScheduledTask, answer: String) {
        if (!Workspace.isReady(context)) return
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.getDefault()).format(Date())
        val safeTitle = task.title.trim()
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .replace(Regex("\\s+"), "-")
            .take(40)
            .ifBlank { "tarefa" }
        val body = buildString {
            append("# ${task.title.ifBlank { "Tarefa" }}\n\n")
            append("- Quando: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            append("\n")
            append("- Tarefa: ${task.prompt}\n")
            append("- Modelo: ${AppSettings.config.value.model.ifBlank { "(padrão)" }}\n")
            append(
                "- Ferramentas: " +
                    (if (task.allowedTools.isEmpty()) "nenhuma"
                    else task.allowedTools.joinToString(", "))
            )
            append("\n\n---\n\n")
            append(answer.ifBlank { "(o modelo não respondeu)" })
        }
        Workspace.ensureFolder(context, TASK_FOLDER)
        Workspace.writeTextFile(context, "$stamp-$safeTitle.md", body, TASK_FOLDER)
    }

    // ------------------------------------------------------- notifications

    private fun startForegroundCompat(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RUN, "Tarefas agendadas", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val n = builder(CHANNEL_RUN, "AndroidAssist", text).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_RUN, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_RUN, n)
            }
        } catch (e: Exception) {
        }
    }

    /** The "task finished" notification: the tap reopens the chat. */
    private fun notifyResult(context: Context, task: ScheduledTask, answer: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT, "Resultado das tarefas",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val preview = answer.replace(Regex("\\s+"), " ").trim().take(160)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_CHAT, task.chatId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, task.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = builder(CHANNEL_RESULT, task.title.ifBlank { "Tarefa concluída" }, preview)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_RESULT, n)
        } catch (e: SecurityException) {
        }
    }

    private fun builder(channelId: String, title: String, text: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
    }

    companion object {
        /** Where the reports live inside the pointed folder. */
        const val TASK_FOLDER = "AndroidAssist/tarefas"
        /** The intent extra that opens a conversation. */
        const val EXTRA_OPEN_CHAT = "open_chat_id"
        private const val CHANNEL_RUN = "task_runs"
        private const val CHANNEL_RESULT = "task_results"
        private const val NOTIFICATION_RUN = 2001
        private const val NOTIFICATION_RESULT = 2002
        private const val MAX_TURN_MS = 20 * 60 * 1000

        /** Start the service that runs the task. */
        fun start(context: Context, taskId: String) {
            val intent = Intent(context, TaskRunnerService::class.java).apply {
                putExtra(TaskScheduler.EXTRA_TASK_ID, taskId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ blocks a foreground service started from the
                // background without the exact-alarm exemption.
            }
        }
    }
}
