package com.miguanm7a.hsr.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguanm7a.hsr.ui.components.BottomBar
import com.miguanm7a.hsr.ui.components.NavTab
import com.miguanm7a.hsr.ui.monitor.MonitorActivity
import com.miguanm7a.hsr.ui.screens.DeployScreen
import com.miguanm7a.hsr.ui.screens.HomeScreen
import com.miguanm7a.hsr.ui.screens.LogScreen
import com.miguanm7a.hsr.ui.screens.SettingsScreen
import com.miguanm7a.hsr.ui.screens.TasksScreen
import com.miguanm7a.hsr.ui.terminal.TerminalActivity
import com.miguanm7a.hsr.ui.theme.AccentTheme
import com.miguanm7a.hsr.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun MainScreen(
    themeMode: ThemeMode,
    accentTheme: AccentTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentTheme) -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { NavTab.entries.size })

    LaunchedEffect(Unit) { vm.refresh(context) }

    val openTerminal: (String?) -> Unit = { cmd ->
        val intent = Intent(context, TerminalActivity::class.java)
        if (!cmd.isNullOrBlank()) intent.putExtra(TerminalActivity.EXTRA_COMMAND, cmd)
        context.startActivity(intent)
    }

    val openMonitor: () -> Unit = {
        context.startActivity(Intent(context, MonitorActivity::class.java))
    }

    Scaffold(
        bottomBar = {
            BottomBar(
                selected = NavTab.entries[pagerState.currentPage],
                onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (NavTab.entries[page]) {
                    NavTab.HOME -> HomeScreen(
                        vm = vm,
                        onGoDeploy = {
                            scope.launch { pagerState.animateScrollToPage(NavTab.DEPLOY.ordinal) }
                        },
                        onOpenTerminal = { openTerminal(null) },
                        onOpenMonitor = openMonitor,
                    )

                    NavTab.DEPLOY -> DeployScreen(vm = vm)

                    NavTab.TASKS -> TasksScreen(
                        vm = vm,
                        onOpenTerminal = openTerminal,
                        onOpenMonitor = openMonitor,
                    )

                    NavTab.LOG -> LogScreen(vm = vm)

                    NavTab.SETTINGS -> SettingsScreen(
                        vm = vm,
                        themeMode = themeMode,
                        accentTheme = accentTheme,
                        onThemeModeChange = onThemeModeChange,
                        onAccentChange = onAccentChange,
                        onOpenMonitor = openMonitor,
                    )
                }
            }
        }
    }
}
