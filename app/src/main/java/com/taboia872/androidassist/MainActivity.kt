package com.taboia872.androidassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.ui.ChatHistoryScreen
import com.taboia872.androidassist.ui.ChatSessions
import com.taboia872.androidassist.ui.ChatScreen
import com.taboia872.androidassist.ui.SettingsScreen
import com.taboia872.androidassist.ui.theme.AndroidAssistTheme
import com.taboia872.androidassist.tts.TtsManager

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        ChatSessions.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        ChatSessions.appInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        ChatSessions.init(this)
        TtsManager.init(this, AppSettings.config.value.ttsLanguage)
        // Heads-up notifications for backgrounded model answers (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            var screen by remember { mutableStateOf("chat") }
            var resumeChatId by remember { mutableStateOf<String?>(null) }
            AndroidAssistTheme {
                // Android back returns to the CHAT instead of exiting the app
                // (user report): only exit from the chat screen itself.
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                when (screen) {
                    "settings" -> SettingsScreen(onBack = { screen = "chat" })
                    "history" -> ChatHistoryScreen(
                        onChatSelected = { chatId ->
                            resumeChatId = chatId
                            screen = "chat"
                        },
                        onBack = { screen = "chat" }
                    )
                    else -> ChatScreen(
                        resumeChatId = resumeChatId,
                        onOpenSettings = { screen = "settings" },
                        onOpenHistory = { screen = "history" }
                    )
                }
            }
        }
    }
}
