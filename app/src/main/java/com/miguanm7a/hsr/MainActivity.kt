package com.miguanm7a.hsr

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.ui.MainScreen
import com.miguanm7a.hsr.ui.theme.AccentTheme
import com.miguanm7a.hsr.ui.theme.MaaTermuxTheme
import com.miguanm7a.hsr.ui.theme.ThemeMode

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = DeploySettings(applicationContext)
        val initialTheme = runCatching { ThemeMode.valueOf(settings.themeMode()) }
            .getOrDefault(ThemeMode.SYSTEM)
        val initialAccent = runCatching { AccentTheme.valueOf(settings.accentTheme()) }
            .getOrDefault(AccentTheme.MARCH7)

        setContent {
            var themeMode by remember { mutableStateOf(initialTheme) }
            var accent by remember { mutableStateOf(initialAccent) }

            MaaTermuxTheme(themeMode = themeMode, accentTheme = accent) {
                MainScreen(
                    themeMode = themeMode,
                    accentTheme = accent,
                    onThemeModeChange = {
                        themeMode = it
                        settings.setThemeMode(it.name)
                    },
                    onAccentChange = {
                        accent = it
                        settings.setAccentTheme(it.name)
                    },
                )
            }
        }
    }
}
