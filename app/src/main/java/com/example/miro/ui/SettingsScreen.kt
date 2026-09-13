package com.example.miro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ListItem(
                headlineContent = { Text("Change 4-Digit PIN") },
                supportingContent = { Text("Secure your access") },
                trailingContent = {
                    Button(onClick = onChangePin) {
                        Text("Change")
                    }
                }
            )
            
            HorizontalDivider()
            
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Miro Screen Mirroring v1.0", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
