package de.tonmaschine.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tonmaschine.android.model.*
import kotlinx.coroutines.delay

private val bg = Color(0xFF171B23)
private val panel = Color(0xFF232936)
private val blue = Color(0xFF76A9FF)
private val muted = Color(0xFFA7B4C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { if (it) vm.connect() }
            val connect = {
                if (Build.VERSION.SDK_INT >= 37 &&
                    checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                } else vm.connect()
            }
            LaunchedEffect(Unit) { if (Catalog.validIp(vm.ip.value)) connect() }
            Tonmaschine(vm, connect)
        }
    }
}

@Composable
fun Tonmaschine(vm: MainViewModel, connect: () -> Unit) {
    val state by vm.device.collectAsState()
    val ip by vm.ip.collectAsState()
    val message by vm.message.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val selected by vm.selectedProfile.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val active = state.connection == Connection.CONNECTED
    MaterialTheme(colorScheme = darkColorScheme(primary = blue, background = bg, surface = panel)) {
        Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TONMASCHINE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("LG S95QR  ·  " + when (state.connection) {
                        Connection.CONNECTED -> "Verbunden"
                        Connection.CONNECTING -> "Verbinde …"
                        Connection.DISCONNECTED -> "Offline"
                    }, color = muted)
                }
                TextButton(onClick = { settings = !settings }) { Text(if (settings) "Zurück" else "IP / Einstellungen") }
            }
            if (settings) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Panel("Verbindung") {
                        OutlinedTextField(ip, vm::updateIp, modifier = Modifier.fillMaxWidth(),
                            label = { Text("IPv4-Adresse der Soundbar") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = connect) { Text("Verbinden") }
                            OutlinedButton(onClick = vm::refresh, enabled = active) { Text("Aktualisieren") }
                        }
                        Text("Direkte WLAN-Verbindung auf TCP 9741. Profile bleiben auf diesem Gerät.", color = muted)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Panel("Gesamtlautstärke") {
                            val actual = state.volume ?: 0
                            var draft by remember(actual) { mutableFloatStateOf(actual.toFloat()) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.setVolume((actual - 1).coerceAtLeast(0)) }, enabled = active) { Text("−") }
                                Slider(draft, onValueChange = { draft = it },
                                    onValueChangeFinished = { vm.setVolume(draft.toInt()) },
                                    modifier = Modifier.weight(1f), valueRange = 0f..100f, enabled = active)
                                TextButton(onClick = { vm.setVolume((actual + 1).coerceAtMost(100)) }, enabled = active) { Text("+") }
                                Text("${draft.toInt()}", modifier = Modifier.width(35.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    item {
                        Panel("Eingang und Audioformat") {
                            var inputExpanded by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Zuspieler", color = muted)
                                    Box {
                                        OutlinedButton(onClick = { inputExpanded = true }, enabled = state.canSelectInput) {
                                            Text(Catalog.inputs[state.currentInput] ?: "Nicht erkannt")
                                        }
                                        DropdownMenu(expanded = inputExpanded, onDismissRequest = { inputExpanded = false }) {
                                            state.selectableInputs.forEach { code ->
                                                DropdownMenuItem(text = { Text(Catalog.inputs[code].orEmpty()) },
                                                    onClick = { inputExpanded = false; vm.chooseSource(code) })
                                            }
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Gemeldetes Format", color = muted)
                                    Text(state.audioFormat, fontWeight = FontWeight.Bold, color = blue)
                                }
                            }
                        }
                    }
                    item {
                        Panel("Klangoptionen") {
                            ToggleRow("Night Time", state.nightTime, active) { vm.setOption("b_night_time", it) }
                            ToggleRow("DRC", state.drc, active) { vm.setOption("b_drc", it) }
                            ToggleRow("Surround", state.surround, active) { vm.setOption("b_rear", it) }
                        }
                    }
                    item {
                        Panel("Klangmodus") {
                            val modes = state.selectableModes.entries.toList()
                            modes.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { (label, id) ->
                                        val checked = state.currentMode == id
                                        if (checked) Button(onClick = { vm.chooseMode(id) }, enabled = active,
                                            modifier = Modifier.weight(1f)) { Text(label, maxLines = 2) }
                                        else OutlinedButton(onClick = { vm.chooseMode(id) }, enabled = active,
                                            modifier = Modifier.weight(1f)) { Text(label, maxLines = 2) }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            Text("Bass ${state.bass ?: "–"}  ·  Höhen ${state.treble ?: "–"}", color = muted)
                        }
                    }
                    item {
                        Panel("Klangprofile") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Gespeicherte Profile: ${profiles.size}", modifier = Modifier.weight(1f))
                                TextButton(onClick = { profileDialog = true }) { Text("Verwalten") }
                            }
                            profiles.keys.sorted().forEach { title ->
                                OutlinedButton(onClick = { vm.applyProfile(title) }, enabled = active,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text((if (selected == title) "✓  " else "") + title)
                                }
                            }
                        }
                    }
                    item {
                        Panel("Lautsprecherpegel") {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Pegel ausblenden" else "Alle 7 Pegel anzeigen")
                            }
                            if (expanded) Catalog.levels.forEach { spec ->
                                val value = state.levels[spec.field]
                                Text("${spec.name}: ${value?.let { "%+d dB".format(it) } ?: "–"}")
                                var draft by remember(spec.field, value) { mutableFloatStateOf((value ?: 0).toFloat()) }
                                Slider(draft, onValueChange = { draft = it },
                                    onValueChangeFinished = { vm.setLevel(spec, draft.toInt()) },
                                    enabled = active && value != null, valueRange = spec.min.toFloat()..spec.max.toFloat(),
                                    steps = (spec.max - spec.min - 1).coerceAtLeast(0))
                            }
                        }
                    }
                }
            }
            Text(message, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                color = if (state.error != null) Color(0xFFFFB4AB) else muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (profileDialog) {
            AlertDialog(onDismissRequest = { profileDialog = false },
                title = { Text("Klangprofile verwalten") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text("Name / neuer Name") },
                            singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.saveProfile(name) }, enabled = active && name.isNotBlank()) {
                            Text("Aktuelle Werte speichern")
                        }
                        HorizontalDivider()
                        profiles.keys.sorted().forEach { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item, Modifier.weight(1f))
                                TextButton(onClick = { name = item }) { Text("Bearbeiten") }
                                TextButton(onClick = { vm.deleteProfile(item) }) { Text("Löschen") }
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { profileDialog = false }) { Text("Schließen") } })
        }
    }
}
@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
@Composable
private fun ToggleRow(label: String, value: Boolean?, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value ?: false, onCheckedChange = onChanged, enabled = enabled && value != null)
    }
}
