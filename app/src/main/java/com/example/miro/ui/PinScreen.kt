package com.example.miro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PinScreen(
    storedPin: String?,
    onPinSuccess: () -> Unit,
    onPinCreate: (String) -> Unit
) {
    var inputPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val isCreating = storedPin == null

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isCreating) "Create 4-Digit PIN" else "Enter PIN",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = inputPin,
            onValueChange = { if (it.length <= 4) inputPin = it },
            label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = PasswordVisualTransformation(),
            isError = error,
            modifier = Modifier.width(150.dp)
        )
        
        if (error) {
            Text("Incorrect PIN", color = MaterialTheme.colorScheme.error)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (isCreating) {
                    if (inputPin.length == 4) onPinCreate(inputPin)
                } else {
                    if (inputPin == storedPin) onPinSuccess() else error = true
                }
            },
            enabled = inputPin.length == 4
        ) {
            Text(if (isCreating) "Create" else "Unlock")
        }
    }
}
