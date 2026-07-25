package com.example.controller

import android.content.Context
import android.hardware.Sensor
import android.content.pm.ActivityInfo
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.roundToInt

val Context.dataStore by preferencesDataStore(name = "controller_settings")
val LAYOUT_KEY = stringPreferencesKey("gamepad_layout_v2")
val IP_KEY = stringPreferencesKey("last_ip")

/** Position is stored as a fraction of the play area so the layout never overlaps
 *  regardless of the phone's screen size or aspect ratio. */
data class Widget(
    val id: String,
    var xFrac: Float,
    var yFrac: Float,
    var scale: Float = 1.0f,
    var type: String,
    var label: String = "",
    var bitmask: Int = 0
)

fun defaultWidgets() = listOf(
    Widget("lstick", 0.04f, 0.20f, 1f, "LSTICK"),
    Widget("dpad", 0.05f, 0.58f, 1f, "DPAD"),
    Widget("actions", 0.79f, 0.20f, 1f, "ACTIONS"),
    Widget("rstick", 0.80f, 0.60f, 1f, "RSTICK"),
    Widget("lb", 0.02f, 0.02f, 1f, "BUMPER", "LB", BTN_LB),
    Widget("lt", 0.15f, 0.02f, 1f, "TRIGGER", "LT"),
    Widget("rb", 0.88f, 0.02f, 1f, "BUMPER", "RB", BTN_RB),
    Widget("rt", 0.75f, 0.02f, 1f, "TRIGGER", "RT"),
)

class MainActivity : ComponentActivity(), SensorEventListener {
    private var controllerSocket: ControllerSocket? = null
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var accelX = 0f
    private var accelY = 0f
    private var useAccel by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = XGreen,
                    onPrimary = Chassis,
                    background = Chassis,
                    surface = Panel,
                )
            ) {
                var ipInput by remember { mutableStateOf("") }
                var isConnected by remember { mutableStateOf(false) }
                val context = LocalContext.current

                LaunchedEffect(Unit) { ipInput = context.dataStore.data.map { it[IP_KEY] }.first() ?: "" }

                Surface(modifier = Modifier.fillMaxSize(), color = Chassis) {
                    if (!isConnected) {
                        SetupScreen(ipInput, onIpChange = { ipInput = it }) {
                            val cleanIp = ipInput.split(":")[0].trim()
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val socket = ControllerSocket(cleanIp)
                                    context.dataStore.edit { it[IP_KEY] = cleanIp }
                                    withContext(Dispatchers.Main) { controllerSocket = socket; isConnected = true }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Invalid address", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    } else {
                        GamepadScreen(useAccel, { useAccel = it }, accelX, accelY) { b, lx, ly, rx, ry, lt, rt ->
                            controllerSocket?.sendInput(b, lx, ly, rx, ry, lt, rt)
                        }
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && useAccel) {
            // Landscape: the sensor's Y axis is forward/back tilt (stick Y), X axis is
            // left/right tilt (stick X).
            val rawX = -event.values[1]
            val rawY = -event.values[0]
            accelX = rawX * 12f
            accelY = rawY * 12f
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() { super.onResume(); accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
}

class ControllerSocket(serverIp: String, val port: Int = UDP_PORT) {
    private val socket = DatagramSocket()
    private val address: InetAddress = InetAddress.getByName(serverIp)
    private val scope = CoroutineScope(Dispatchers.IO)
    fun sendInput(buttons: Short, lx: Byte, ly: Byte, rx: Byte, ry: Byte, lt: Byte, rt: Byte) {
        scope.launch {
            try {
                val buffer = ByteBuffer.allocate(8)
                buffer.putShort(buttons); buffer.put(lx); buffer.put(ly); buffer.put(rx); buffer.put(ry); buffer.put(lt); buffer.put(rt)
                socket.send(DatagramPacket(buffer.array(), 8, address, port))
            } catch (e: Exception) {}
        }
    }
}

@Composable
fun SetupScreen(ip: String, onIpChange: (String) -> Unit, onConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(Panel).border(2.dp, Edge, CircleShape),
            contentAlignment = Alignment.Center
        ) { GuideButton(56.dp) {} }
        Spacer(modifier = Modifier.height(20.dp))
        Text("GAMEPAD PRO BUILDER", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 2.sp, fontFamily = Mono)
        Text("XBOX 360 EMULATION OVER UDP", fontSize = 12.sp, color = Muted, letterSpacing = 3.sp, fontFamily = Mono)
        Spacer(modifier = Modifier.height(32.dp))
        Text("PC IP ADDRESS", fontSize = 11.sp, color = Muted, letterSpacing = 2.sp, fontFamily = Mono, modifier = Modifier.width(280.dp))
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            placeholder = { Text("192.168.1.42", color = Muted.copy(alpha = 0.5f), fontFamily = Mono) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 18.sp, color = Ink),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = XGreen,
                unfocusedBorderColor = Edge,
                focusedContainerColor = Recess,
                unfocusedContainerColor = Recess,
            ),
            modifier = Modifier.width(280.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConnect,
            enabled = ip.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = XGreen, contentColor = Chassis, disabledContainerColor = Edge),
            modifier = Modifier.width(200.dp).height(48.dp)
        ) { Text("CONNECT", fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
    }
}

@Composable
fun GamepadScreen(useAccel: Boolean, onToggleAccel: (Boolean) -> Unit, accelX: Float, accelY: Float, onInput: (Short, Byte, Byte, Byte, Byte, Byte, Byte) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = Gson()

    var isEditMode by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val widgets = remember { mutableStateListOf<Widget>() }

    LaunchedEffect(Unit) {
        val savedJson = context.dataStore.data.map { it[LAYOUT_KEY] }.first()
        if (!savedJson.isNullOrEmpty()) {
            val list: List<Widget> = gson.fromJson(savedJson, object : TypeToken<List<Widget>>() {}.type)
            widgets.clear(); widgets.addAll(list)
        } else {
            widgets.addAll(defaultWidgets())
        }
    }

    var buttons by remember { mutableStateOf<Short>(0) }
    var lx by remember { mutableStateOf<Byte>(128.toByte()) }
    var ly by remember { mutableStateOf<Byte>(128.toByte()) }
    var rx by remember { mutableStateOf<Byte>(128.toByte()) }
    var ry by remember { mutableStateOf<Byte>(128.toByte()) }
    var ltv by remember { mutableStateOf<Byte>(0) }
    var rtv by remember { mutableStateOf<Byte>(0) }

    fun press(mask: Int, active: Boolean) {
        buttons = if (active) (buttons.toInt() or mask).toShort() else (buttons.toInt() and mask.inv()).toShort()
    }

    LaunchedEffect(useAccel, accelX, accelY, lx, ly, rx, ry, buttons, ltv, rtv) {
        while (true) {
            val finalLx = if (useAccel) (128f + accelX).toInt().coerceIn(0, 255).toByte() else lx
            val finalLy = if (useAccel) (128f + accelY).toInt().coerceIn(0, 255).toByte() else ly
            onInput(buttons, finalLx, finalLy, rx, ry, ltv, rtv)
            delay(15)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Chassis)) {
        val density = LocalDensity.current
        val areaWpx = with(density) { maxWidth.toPx() }
        val areaHpx = with(density) { maxHeight.toPx() }
        val metrics = remember(maxWidth, maxHeight) { Metrics.of(maxWidth, maxHeight) }

        fun dimFor(type: String) = when (type) {
            "LSTICK", "RSTICK" -> metrics.stick
            "DPAD" -> metrics.dpad
            "ACTIONS" -> metrics.cluster
            "BUMPER", "TRIGGER" -> metrics.bumperW
            else -> metrics.bumperW
        }

        if (isEditMode) {
            // Narrow and centred so it never reaches the LB/LT/RT/RB corners — those stay
            // selectable while editing instead of being hidden under a full-width bar.
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).fillMaxWidth(0.44f),
                color = Panel.copy(0.97f),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Edge)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val sel = widgets.find { it.id == selectedId }
                    if (sel != null) {
                        Column(modifier = Modifier.width(130.dp)) {
                            Text("SCALE ${(sel.scale * 100).toInt()}%", color = Muted, fontSize = 10.sp, fontFamily = Mono, letterSpacing = 1.sp)
                            Slider(
                                value = sel.scale,
                                onValueChange = { s -> val i = widgets.indexOf(sel); if (i != -1) widgets[i] = sel.copy(scale = s) },
                                valueRange = 0.6f..1.8f,
                                colors = SliderDefaults.colors(thumbColor = XGreen, activeTrackColor = XGreen, inactiveTrackColor = Edge)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        if (sel.type == "BUTTON") {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { expanded = true }) {
                                    Text(sel.label, color = XGreen, fontFamily = Mono, fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    AssignableButtons.forEach { (name, mask) ->
                                        DropdownMenuItem(text = { Text(name, fontFamily = Mono) }, onClick = {
                                            val i = widgets.indexOf(sel)
                                            if (i != -1) widgets[i] = sel.copy(label = name, bitmask = mask)
                                            expanded = false
                                        })
                                    }
                                }
                            }
                        } else {
                            Text(sel.label.ifEmpty { sel.type }, color = Muted, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { widgets.remove(sel); selectedId = null }) { Icon(Icons.Default.Delete, null, tint = BtnB) }
                    } else {
                        Text("TAP A CONTROL TO EDIT IT", color = Muted, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    }
                    IconButton(onClick = {
                        widgets.add(Widget("btn_${widgets.size}_${selectedId.hashCode()}", 0.46f, 0.40f, 1f, "BUTTON", "A", BTN_A))
                    }) { Icon(Icons.Default.Add, null, tint = XGreen) }
                    Button(
                        onClick = { isEditMode = false; selectedId = null; scope.launch { context.dataStore.edit { it[LAYOUT_KEY] = gson.toJson(widgets.toList()) } } },
                        colors = ButtonDefaults.buttonColors(containerColor = XGreen, contentColor = Chassis)
                    ) { Text("SAVE", fontFamily = Mono, fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MenuButton("BACK", 40.dp) { active -> press(BTN_BACK, active) }
                GuideButton(52.dp) { isEditMode = true }
                MenuButton("START", 40.dp) { active -> press(BTN_START, active) }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("MOTION", color = if (useAccel) XGreen else Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
                IconButton(
                    onClick = { onToggleAccel(!useAccel) },
                    modifier = Modifier.size(36.dp)
                        .background(if (useAccel) XGreenDim else Panel, CircleShape)
                        .border(1.dp, if (useAccel) XGreen else Edge, CircleShape)
                ) { Icon(Icons.Default.LocationOn, null, tint = if (useAccel) XGreen else Muted, modifier = Modifier.size(18.dp)) }
            }
        }

        widgets.forEach { w ->
            val dim = dimFor(w.type)
            val xPx = w.xFrac * areaWpx
            val yPx = w.yFrac * areaHpx

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                    .scale(w.scale)
                    .pointerInput(w.id, isEditMode, areaWpx, areaHpx) {
                        if (isEditMode) {
                            awaitEachGesture {
                                // Select as soon as the finger lands — not requireUnconsumed,
                                // since a control's own press feedback (e.g. GenericButton)
                                // observes the same down without consuming it. A plain tap
                                // then just selects; any following movement also repositions.
                                val down = awaitFirstDown(requireUnconsumed = false)
                                selectedId = w.id
                                drag(down.id) { change ->
                                    change.consume()
                                    val i = widgets.indexOfFirst { it.id == w.id }
                                    if (i != -1) {
                                        widgets[i] = widgets[i].copy(
                                            xFrac = (widgets[i].xFrac + change.positionChange().x / areaWpx).coerceIn(0f, 0.94f),
                                            yFrac = (widgets[i].yFrac + change.positionChange().y / areaHpx).coerceIn(0f, 0.90f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    .then(
                        if (isEditMode) Modifier
                            .border(if (selectedId == w.id) 2.dp else 1.dp, if (selectedId == w.id) XGreen else Edge, RoundedCornerShape(12.dp))
                        else Modifier
                    )
            ) {
                Box {
                    when (w.type) {
                        "LSTICK" -> AnalogStick("L", dim) { x, y ->
                            lx = ((x + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte()
                            ly = ((y + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte()
                        }
                        "RSTICK" -> AnalogStick("R", dim) { x, y ->
                            rx = ((x + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte()
                            ry = ((y + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte()
                        }
                        "DPAD" -> DPad(dim) { mask, active -> press(mask, active) }
                        "ACTIONS" -> FaceCluster(dim) { mask, active -> press(mask, active) }
                        "BUMPER" -> ShoulderButton(w.label, metrics.bumperW, metrics.bumperH, trigger = false) { active -> press(w.bitmask, active) }
                        "TRIGGER" -> ShoulderButton(w.label, metrics.bumperW, metrics.bumperH, trigger = true) { active ->
                            val v = if (active) 255.toByte() else 0.toByte()
                            if (w.label == "LT") ltv = v else rtv = v
                        }
                        "BUTTON" -> GenericButton(w.label, metrics.bumperW * 0.8f, metrics.bumperH) { active -> press(w.bitmask, active) }
                    }
                    if (isEditMode) {
                        Box(modifier = Modifier.matchParentSize().background(Color.Transparent).clickable(enabled = false) {})
                    }
                }
            }
        }
    }
}
