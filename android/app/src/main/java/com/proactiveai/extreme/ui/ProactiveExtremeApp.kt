package com.proactiveai.extreme.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.proactiveai.extreme.ui.permission.PermissionCommandCenterScreen
import com.proactiveai.extreme.ui.permission.PermissionCommandCenterState
import com.proactiveai.extreme.ui.permission.rememberPermissionCommandCenterState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveExtremeApp() {
    val context = LocalContext.current
    val state: PermissionCommandCenterState = rememberPermissionCommandCenterState(context)
    var activeTab by rememberSaveable { mutableStateOf(AppTab.PERMISSIONS) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = activeTab.title)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Text(tab.label.take(1)) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        PermissionCommandCenterScreen(
            modifier = Modifier.padding(innerPadding),
            state = state,
            activeTab = activeTab,
        )
    }
}
