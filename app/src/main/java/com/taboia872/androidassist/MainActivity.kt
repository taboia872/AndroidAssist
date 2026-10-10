package com.taboia872.androidassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.ui.ChatScreen
import com.taboia872.androidassist.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            if (showSettings) {
                SettingsScreen(onBack = { showSettings = false })
            } else {
                ChatScreen(onOpenSettings = { showSettings = true })
            }
        }
    }
}
