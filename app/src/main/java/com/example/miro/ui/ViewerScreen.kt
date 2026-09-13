package com.example.miro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun ViewerScreen(
    targetId: String,
    remoteTrack: VideoTrack?,
    connectionState: String,
    eglContext: EglBase.Context,
    onDisconnect: () -> Unit,
    onScreenshot: (SurfaceViewRenderer) -> Unit
) {
    var renderer: SurfaceViewRenderer? by remember { mutableStateOf(null) }

    DisposableEffect(remoteTrack, renderer) {
        if (remoteTrack != null && renderer != null) {
            remoteTrack.addSink(renderer)
        }
        onDispose {
            if (remoteTrack != null && renderer != null) {
                remoteTrack.removeSink(renderer)
            }
        }
    }

    Scaffold(
        bottomBar = {
            BottomAppBar {
                Button(onClick = { renderer?.let { onScreenshot(it) } }) {
                    Text("Screenshot")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Disconnect")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        setMirror(false)
                        renderer = this
                    }
                },
                update = { view ->
                    if (remoteTrack != null) {
                        view.requestLayout()
                    }
                },
                onRelease = { view ->
                    view.release()
                    renderer = null
                },
                modifier = Modifier.fillMaxSize()
            )

            // Status Overlay
            if (connectionState != "Streaming" && connectionState != "Connected" && connectionState != "CONNECTED") {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = connectionState,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (connectionState.contains("Blocked")) 
                                "Firewall is blocking the connection. Try switching from Data to Wi-Fi (or vice versa)."
                                else "Establishing secure link to $targetId...",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
