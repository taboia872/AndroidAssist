package com.taboia872.androidassist.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The alarms of the scheduled tasks.
 *
 * The alarm wakes the app (the receiver) which starts the foreground
 * service that runs the turn. The alarms do NOT survive a reboot, so
 * the receiver re-arms them on BOOT_COMPLETED.
 */
object TaskScheduler {

    private const val ACTION = "com.taboia872.androidassist.RUN_TASK"
    const val EXTRA_TASK_ID = "task_id"

    /** Re-arm every enabled task (after a reboot, after an update). */
    fun rescheduleAll(context: Context) {
        TaskStore.all(context).forEach { schedule(context, it) }
    }

    /** Arm (or re-arm) the next run of a task. */
    fun schedule(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancel(context, task.id)
        if (!task.enabled) return
        val at = task.nextRunAt()
        if (at <= 0) return
        val pi = pendingIntent(context, task.id)
        try {
            // The EXACT alarm needs a permission on Android 12+. It also
            // matters for the foreground service: starting one from a
            // background receiver is only allowed for an exact alarm.
            if (canScheduleExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e2: Exception) {
            }
        }
    }

    /** Cancel the pending alarm of a task. */
    fun cancel(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context, taskId))
        } catch (e: Exception) {
        }
    }

    /** Can the app set an exact alarm right now? */
    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return canScheduleExact(am)
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am.canScheduleExactAlarms()
            } catch (e: Exception) {
                false
            }
        } else true

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
