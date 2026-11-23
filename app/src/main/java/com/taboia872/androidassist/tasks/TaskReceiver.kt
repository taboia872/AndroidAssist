package com.taboia872.androidassist.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taboia872.androidassist.settings.AppSettings

/**
 * Wakes up when a task is due — and when the device boots (the alarms
 * do not survive a reboot, so they are armed again).
 */
class TaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The app can wake up with NO activity: the settings (the
        // encrypted preferences) have to be loaded here.
        try {
            AppSettings.init(context.applicationContext)
        } catch (e: Exception) {
        }
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            TaskScheduler.rescheduleAll(context.applicationContext)
            return
        }
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: return
        TaskRunnerService.start(context.applicationContext, taskId)
    }
}
