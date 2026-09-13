package com.example.miro.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    deviceId: String,
    onStartSharing: () -> Unit,
    onConnectDevice: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val isHostingSupported = Build.VERSION.SDK_INT in 26..30 // Android 8 to 11

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miro Mirror") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("My Device ID", style = MaterialTheme.typography.labelLarge)
                    Text(deviceId, style = MaterialTheme.typography.headlineLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onStartSharing,
                    enabled = isHostingSupported,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Start Sharing Screen")
                }
                
                if (!isHostingSupported) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Info, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Sharing only supported on Android 8+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onConnectDevice,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Connect To Device (Viewer)")
            }
            
            Text(
                "Viewing supported on all devices (Android 8+)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
