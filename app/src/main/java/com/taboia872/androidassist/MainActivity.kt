package com.taboia872.androidassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.ui.ChatHistoryScreen
import com.taboia872.androidassist.ui.ChatScreen
import com.taboia872.androidassist.ui.ChatSessions
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
        // Permissions asked once up front (assistant must be hands-free later):
        // notifications (background answers, API 33+), calendar (direct event
        // save), location (get_location tool for transit questions).
        val wanted = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.READ_CALENDAR)
            if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.WRITE_CALENDAR)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (wanted.isNotEmpty()) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { }.launch(wanted.toTypedArray())
        }
        enableEdgeToEdge()
        setContent {
            var screen by remember { mutableStateOf("chat") }
            var resumeChatId by remember { mutableStateOf<String?>(null) }
            // Swipe accumulator: right-swipe on the chat opens Settings
            var swipeDelta by remember { mutableFloatStateOf(0f) }
            AndroidAssistTheme {
                // Android back returns to the CHAT instead of exiting the app
                // (user report): only exit from the chat screen itself.
                BackHandler(enabled = screen != "chat") { screen = "chat" }

                val chatSwipeModifier = Modifier
                    .fillMaxSize()
                    .pointerInput(screen) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (screen == "chat" && swipeDelta > 90.dp.toPx()) {
                                    screen = "settings"
                                }
                                swipeDelta = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            swipeDelta += dragAmount
                        }
                    }

                // Screen transitions: the Settings panel FOLLOWS THE FINGER.
                // Swipe left->right pulls the panel IN FROM THE LEFT; closing
                // pushes it back out to the left (fixes the inverted feel —
                // user report: settings were entering from the right).
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (targetState == "settings") {
                            // open: settings enter from the LEFT moving right;
                            // the chat is pushed a quarter to the RIGHT
                            slideInHorizontally(tween(280)) { -it } togetherWith
                                slideOutHorizontally(tween(280)) { it / 4 }
                        } else {
                            // close: settings return to the LEFT edge;
                            // the chat comes back from the right
                            slideInHorizontally(tween(280)) { it / 4 } togetherWith
                                slideOutHorizontally(tween(280)) { -it }
                        }
                    },
                    label = "screens"
                ) { currentScreen ->
                    Box(modifier = if (currentScreen == "chat") chatSwipeModifier else Modifier.fillMaxSize()) {
                        when (currentScreen) {
                            "settings" -> SettingsScreen(onBack = { screen = "chat" })
                            "history" -> ChatHistoryScreen(
                                onChatSelected = { chatId ->
                                    resumeChatId = chatId
                                    screen = "chat"
                                    // Consume after load: returning from Settings
                                    // later must NOT reload this old chat over
                                    // the current one (the resume-chat bug).
                                },
                                onBack = { screen = "chat" }
                            )
                            else -> ChatScreen(
                                resumeChatId = resumeChatId,
                                onConsumeResumeId = { resumeChatId = null },
                                onOpenSettings = { screen = "settings" },
                                onOpenHistory = { screen = "history" }
                            )
                        }
                    }
                }
            }
        }
    }
}
