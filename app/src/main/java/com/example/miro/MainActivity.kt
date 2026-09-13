package com.example.miro

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.miro.service.ScreenCaptureService
import com.example.miro.ui.*
import com.example.miro.ui.theme.MiroTheme

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth

    private fun startSharingService(deviceId: String) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projectionIntent = manager.createScreenCaptureIntent()
        
        // This is a bit tricky since we need to launch projectionLauncher and pass the ID
        // I'll store the ID temporarily
        pendingDeviceId = deviceId
        projectionLauncher.launch(projectionIntent)
    }

    private var pendingDeviceId: String? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureService.setMediaProjectionData(result.data!!)
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("device_id", pendingDeviceId)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase Auth & Sign in Anonymously
        auth = Firebase.auth
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("Auth", "Signed in anonymously: ${auth.currentUser?.uid}")
                } else {
                    Log.w("Auth", "Sign in failed", task.exception)
                }
            }
        }

        setContent {
            MiroTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                val pin by viewModel.pin.collectAsState()
                val deviceId by viewModel.deviceId.collectAsState()

                val startSharing = {
                    deviceId?.let { startSharingService(it) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "pin"
                    ) {
                        composable("pin") {
                            PinScreen(
                                storedPin = pin,
                                onPinSuccess = { navController.navigate("home") },
                                onPinCreate = { 
                                    viewModel.savePin(it)
                                    navController.navigate("home") 
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                deviceId = deviceId ?: "...",
                                onStartSharing = { startSharing() },
                                onConnectDevice = { navController.navigate("connect") },
                                onSettingsClick = { navController.navigate("settings") }
                            )
                        }
                        composable("connect") {
                            ConnectScreen(
                                onConnect = { id -> 
                                    navController.navigate("viewer/$id") 
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("viewer/{targetId}") { backStackEntry ->
                            val targetId = backStackEntry.arguments?.getString("targetId") ?: ""
                            val myId = deviceId ?: ""
                            
                            val viewerViewModel: ViewerViewModel = viewModel(
                                factory = object : ViewModelProvider.Factory {
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                        return ViewerViewModel(application, myId, targetId) as T
                                    }
                                }
                            )
                            
                            val remoteTrack by viewerViewModel.remoteTrack.collectAsState()
                            val connectionState by viewerViewModel.connectionState.collectAsState()
                            
                            ViewerScreen(
                                targetId = targetId,
                                remoteTrack = remoteTrack,
                                connectionState = connectionState,
                                eglContext = viewerViewModel.eglBaseContext,
                                onDisconnect = { navController.popBackStack() },
                                onScreenshot = { /* TODO: Take Screenshot */ }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onChangePin = { navController.navigate("pin") }
                            )
                        }
                    }
                }
            }
        }
    }
}
