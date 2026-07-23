package de.bestora.canonwirelessrawandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.validateCredentials

/**
 * WLAN-Zugangsdaten der Kamera erfassen/bearbeiten. Persistiert in [de.bestora.canonwirelessrawandroid.data.Prefs]
 * und von [ConnectScreen]s Auto-Verbinden-Pfad genutzt.
 */
@Composable
fun CredentialsScreen(container: AppContainer, onSaved: () -> Unit, onBack: () -> Unit) {
    val prefs = container.prefs
    var ssid by remember { mutableStateOf(prefs.cameraSsid ?: "") }
    var psk by remember { mutableStateOf(prefs.cameraPsk ?: "") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val err = validateCredentials(ssid, psk)
        if (err != null) {
            error = err
        } else {
            prefs.cameraSsid = ssid
            prefs.cameraPsk = psk
            onSaved()
        }
    }

    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Wi-Fi credentials", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("The camera shows its Wi-Fi SSID and password when you add a device under Remote control (EOS Utility).")
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = psk,
                    onValueChange = { psk = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Text(if (visible) "🙈" else "👁")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }
    }
}
