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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

val PS_Blue = Color(0xFF00439C)
val PS_Dark = Color(0xFF0A0A0A)
val PS_Grey = Color(0xFF1E1E1E)

val Context.dataStore by preferencesDataStore(name = "controller_settings")
val LAYOUT_KEY = stringPreferencesKey("gamepad_layout")
val IP_KEY = stringPreferencesKey("last_ip")

data class Widget(
    val id: String,
    var x: Float,
    var y: Float,
    var scale: Float = 1.0f,
    var type: String,
    var label: String = "",
    var bitmask: Int = 0
)

val AllFunctions = mapOf(
    "✕ (A)" to 0x0001, "◯ (B)" to 0x0002, "□ (X)" to 0x0008, "△ (Y)" to 0x0010,
    "L1" to 0x0100, "R1" to 0x0200, "L2" to -1, "R2" to -2,
    "L3" to 0x0040, "R3" to 0x0080, "SELECT" to 0x0020, "START" to 0x0010
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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = PS_Blue, background = PS_Dark)) {
                var ipInput by remember { mutableStateOf("") }
                var isConnected by remember { mutableStateOf(false) }
                val context = LocalContext.current

                LaunchedEffect(Unit) { ipInput = context.dataStore.data.map { it[IP_KEY] }.first() ?: "26.64.105.1" }

                Surface(modifier = Modifier.fillMaxSize(), color = PS_Dark) {
                    if (!isConnected) {
                        SetupScreen(ipInput, onIpChange = { ipInput = it }) {
                            val cleanIp = ipInput.split(":")[0].trim()
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val socket = ControllerSocket(cleanIp)
                                    context.dataStore.edit { it[IP_KEY] = cleanIp }
                                    withContext(Dispatchers.Main) { controllerSocket = socket; isConnected = true }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "IP xato!", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    } else {
                        GamepadScreen(useAccel, { useAccel = it }, accelX, accelY) { b, lx, ly, rx, ry, l2, r2 ->
                            controllerSocket?.sendInput(b, lx, ly, rx, ry, l2, r2)
                        }
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && useAccel) {
            // Landscape rejimida:
            // Y o'qi (event.values[1]) - bu oldinga/orqaga egilish (L-Stick Y)
            // X o'qi (event.values[0]) - bu chapga/o'ngga egilish (L-Stick X)
            // Sensor qiymatlarini 0-255 oralig'iga moslashtiramiz (128 - o'rta holat)
            
            val rawX = -event.values[1] // Landscape-da Y o'qi gorizontal harakatga mos keladi
            val rawY = -event.values[0] // Landscape-da X o'qi vertikal harakatga mos keladi
            
            accelX = (rawX * 12f) // Sezgirlik koeffitsienti
            accelY = (rawY * 12f)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() { super.onResume(); accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
}

class ControllerSocket(serverIp: String, val port: Int = 5005) {
    private val socket = DatagramSocket()
    private val address: InetAddress = InetAddress.getByName(serverIp)
    private val scope = CoroutineScope(Dispatchers.IO)
    fun sendInput(buttons: Short, lx: Byte, ly: Byte, rx: Byte, ry: Byte, l2: Byte, r2: Byte) {
        scope.launch {
            try {
                val buffer = ByteBuffer.allocate(8)
                buffer.putShort(buttons); buffer.put(lx); buffer.put(ly); buffer.put(rx); buffer.put(ry); buffer.put(l2); buffer.put(r2)
                socket.send(DatagramPacket(buffer.array(), 8, address, port))
            } catch (e: Exception) {}
        }
    }
}

@Composable
fun SetupScreen(ip: String, onIpChange: (String) -> Unit, onConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Gamepad Pro Builder", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(value = ip, onValueChange = onIpChange, label = { Text("PC IP") }, modifier = Modifier.width(280.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onConnect, modifier = Modifier.width(180.dp)) { Text("CONNECT") }
    }
}

@Composable
fun GamepadScreen(useAccel: Boolean, onToggleAccel: (Boolean) -> Unit, accelX: Float, accelY: Float, onInput: (Short, Byte, Byte, Byte, Byte, Byte, Byte) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = Gson()
    val density = LocalDensity.current
    
    var isEditMode by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val widgets = remember { mutableStateListOf<Widget>() }

    LaunchedEffect(Unit) {
        val savedJson = context.dataStore.data.map { it[LAYOUT_KEY] }.first()
        if (!savedJson.isNullOrEmpty()) {
            val list: List<Widget> = gson.fromJson(savedJson, object : TypeToken<List<Widget>>() {}.type)
            widgets.clear(); widgets.addAll(list)
        } else {
            widgets.addAll(listOf(
                Widget("lstick", 60f, 150f, 1.0f, "LSTICK"),
                Widget("rstick", 500f, 150f, 1.0f, "RSTICK"),
                Widget("dpad", 60f, 20f, 1.0f, "DPAD"),
                Widget("actions", 500f, 20f, 1.0f, "ACTIONS"),
                Widget("l1", 30f, 5f, 1.0f, "BUTTON", "L1", 0x0100),
                Widget("r1", 620f, 5f, 1.0f, "BUTTON", "R1", 0x0200),
                Widget("l2", 150f, 5f, 1.0f, "TRIGGER", "L2"),
                Widget("r2", 500f, 5f, 1.0f, "TRIGGER", "R2")
            ))
        }
    }

    var buttons by remember { mutableStateOf<Short>(0) }
    var lx by remember { mutableStateOf<Byte>(128.toByte()) }
    var ly by remember { mutableStateOf<Byte>(128.toByte()) }
    var rx by remember { mutableStateOf<Byte>(128.toByte()) }
    var ry by remember { mutableStateOf<Byte>(128.toByte()) }
    var l2v by remember { mutableStateOf<Byte>(0) }
    var r2v by remember { mutableStateOf<Byte>(0) }

    LaunchedEffect(useAccel, accelX, accelY, lx, ly, rx, ry, buttons, l2v, r2v) {
        while (true) {
            val finalLx = if (useAccel) (128f + accelX).toInt().coerceIn(0, 255).toByte() else lx
            val finalLy = if (useAccel) (128f + accelY).toInt().coerceIn(0, 255).toByte() else ly
            onInput(buttons, finalLx, finalLy, rx, ry, l2v, r2v)
            delay(15)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditMode) {
            Surface(modifier = Modifier.fillMaxWidth().height(90.dp).align(Alignment.TopCenter), color = Color.Black.copy(0.9f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    val sel = widgets.find { it.id == selectedId }
                    if (sel != null) {
                        Column(modifier = Modifier.width(120.dp)) {
                            Text("Scale: ${(sel.scale*100).toInt()}%", color = Color.White, fontSize = 9.sp)
                            Slider(value = sel.scale, onValueChange = { s -> val i = widgets.indexOf(sel); if(i!=-1) widgets[i] = sel.copy(scale = s) }, valueRange = 0.5f..2.5f)
                        }
                        if (sel.type == "BUTTON" || sel.type == "TRIGGER") {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { expanded = true }) { Text(sel.label, color = Color.Cyan) }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    AllFunctions.forEach { (name, mask) ->
                                        DropdownMenuItem(text = { Text(name) }, onClick = {
                                            val i = widgets.indexOf(sel); if (i != -1) widgets[i] = sel.copy(label = name.split(" ")[0], bitmask = if(mask<0) 0 else mask, type = if(mask<0) "TRIGGER" else "BUTTON")
                                            expanded = false
                                        })
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { widgets.remove(sel); selectedId = null }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { widgets.add(Widget("btn_${System.currentTimeMillis()}", 350f, 150f, 1f, "BUTTON", "NEW", 0x0001)) }) { Icon(Icons.Default.Add, null, tint = Color.Green) }
                    Button(onClick = { isEditMode = false; selectedId = null; scope.launch { context.dataStore.edit { it[LAYOUT_KEY] = gson.toJson(widgets.toList()) } } }) { Text("SAVE") }
                }
            }
        } else {
            Row(modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) {
                IconButton(onClick = { isEditMode = true }, modifier = Modifier.background(PS_Blue, CircleShape)) { Icon(Icons.Default.Settings, null, tint = Color.White) }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { onToggleAccel(!useAccel) }, modifier = Modifier.background(if (useAccel) Color.Green else Color.Gray, CircleShape)) { Icon(Icons.Default.LocationOn, null, tint = Color.White) }
            }
        }

        widgets.forEach { w ->
            Box(
                modifier = Modifier.offset { with(density) { IntOffset(w.x.dp.roundToPx(), w.y.dp.roundToPx()) } }.scale(w.scale)
                    .pointerInput(w.id, isEditMode) {
                        if (isEditMode) {
                            detectDragGestures(onDragStart = { selectedId = w.id }, onDrag = { change, dragAmount ->
                                change.consume()
                                val i = widgets.indexOfFirst { it.id == w.id }
                                if (i != -1) { widgets[i] = widgets[i].copy(x = widgets[i].x + dragAmount.x / density.density, y = widgets[i].y + dragAmount.y / density.density) }
                            })
                        }
                    }
                    .then(if(isEditMode) Modifier.border(if(selectedId == w.id) 2.dp else 1.dp, if(selectedId == w.id) Color.Cyan else Color.White.copy(0.3f), RoundedCornerShape(8.dp)).clickable { selectedId = w.id } else Modifier)
            ) {
                // Tahrirlash rejimida tugmalarni bloklash uchun shaffof qatlam
                Box {
                    when (w.type) {
                        "LSTICK", "RSTICK" -> Stick(if(w.type=="LSTICK") "L" else "R") { x, y -> 
                            if(w.type=="LSTICK") { lx = ((x + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte(); ly = ((y + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte() }
                            else { rx = ((x + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte(); ry = ((y + 1f) * 127.5f).toInt().coerceIn(0, 255).toByte() }
                        }
                        "DPAD" -> DPadLayout { mask, active -> buttons = if (active) (buttons.toInt() or mask).toShort() else (buttons.toInt() and mask.inv()).toShort() }
                        "ACTIONS" -> ActionLayout { mask, active -> buttons = if (active) (buttons.toInt() or mask).toShort() else (buttons.toInt() and mask.inv()).toShort() }
                        "BUTTON" -> GenericButton(w.label, false) { active -> buttons = if (active) (buttons.toInt() or w.bitmask).toShort() else (buttons.toInt() and w.bitmask.inv()).toShort() }
                        "TRIGGER" -> GenericButton(w.label, true) { active -> val v = if (active) 255.toByte() else 0.toByte(); if (w.label == "L2") l2v = v else if (w.label == "R2") r2v = v }
                    }
                    if (isEditMode) {
                        // Edit rejimida tugma ustini yopib, barcha bosishlarni blocklaydi
                        Box(modifier = Modifier.matchParentSize().background(Color.Transparent).clickable(enabled = false) {})
                    }
                }
            }
        }
    }
}

@Composable
fun Stick(label: String, onMove: (Float, Float) -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { 50.dp.toPx() } // Stickning maksimal harakatlanish radiusi

    Box(modifier = Modifier.size(120.dp).background(PS_Grey.copy(0.4f), CircleShape).border(2.dp, Color.Gray, CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val center = Offset(size.width / 2f, size.height / 2f)

                fun handleInput(pointerPos: Offset) {
                    val delta = pointerPos - center
                    val distance = delta.getDistance()
                    val clampedDelta = if (distance <= radiusPx) delta else delta * (radiusPx / distance)
                    offset = clampedDelta
                    onMove(clampedDelta.x / radiusPx, clampedDelta.y / radiusPx)
                }

                handleInput(down.position)

                drag(down.id) { change ->
                    change.consume()
                    handleInput(change.position)
                }

                // Barmoq ko'tarilganda o'rtaga qaytish
                offset = Offset.Zero
                onMove(0f, 0f)
            }
        }, contentAlignment = Alignment.Center) {
    Text(label, color = Color.Gray, fontSize = 24.sp, fontWeight = FontWeight.Black)
    Box(modifier = Modifier.offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }.size(55.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.Gray, Color.Black))))
}
}

@Composable
fun DPadLayout(onPress: (Int, Boolean) -> Unit) {
    Box(modifier = Modifier.size(150.dp)) {
        val aligns = listOf(Alignment.TopCenter, Alignment.BottomCenter, Alignment.CenterStart, Alignment.CenterEnd)
        val masks = listOf(0x1000, 0x2000, 0x4000, 0x8000)
        for(i in 0..3) {
            var isP by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(aligns[i]).size(45.dp).background(if(isP) PS_Blue else PS_Grey, RoundedCornerShape(8.dp)).pointerInput(Unit) {
                awaitPointerEventScope { while(true) { val p = awaitPointerEvent().changes.first().pressed; if(isP != p){isP=p; onPress(masks[i], p)} } }
            })
        }
    }
}

@Composable
fun ActionLayout(onPress: (Int, Boolean) -> Unit) {
    val colors = listOf(Color(0xFF388E3C), Color(0xFFC2185B), Color(0xFFD32F2F), Color(0xFF2E6DB4))
    val masks = listOf(0x0010, 0x0008, 0x0002, 0x0001)
    Box(modifier = Modifier.size(160.dp)) {
        val aligns = listOf(Alignment.TopCenter, Alignment.CenterStart, Alignment.CenterEnd, Alignment.BottomCenter)
        for(i in 0..3) {
            var isP by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(aligns[i]).size(50.dp).clip(CircleShape).background(if(isP) colors[i].copy(0.4f) else PS_Grey).border(2.dp, colors[i], CircleShape).pointerInput(Unit) {
                awaitPointerEventScope { while(true){ val p = awaitPointerEvent().changes.first().pressed; if(isP!=p){isP=p; onPress(masks[i], p)} } }
            })
        }
    }
}

@Composable
fun GenericButton(text: String, isTrigger: Boolean, onTouch: (Boolean) -> Unit) {
    var isP by remember { mutableStateOf(false) }
    Box(modifier = Modifier.width(if(isTrigger) 110.dp else 90.dp).height(45.dp).clip(RoundedCornerShape(10.dp))
        .background(if(isP) (if(isTrigger) Color.Red else Color.Gray) else PS_Grey).border(1.dp, Color.DarkGray, RoundedCornerShape(10.dp))
        .pointerInput(Unit) { awaitPointerEventScope { while(true){ val p = awaitPointerEvent().changes.first().pressed; if(isP!=p){isP=p; onTouch(p)} } } }, contentAlignment = Alignment.Center) { 
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
