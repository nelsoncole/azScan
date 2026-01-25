package com.example.azscan

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.azscan.ui.theme.AzScanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.widget.Toast


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AzScanTheme {
                AppScreen()
            }
        }
    }
}

// --- Telas para o menu ---
sealed class Screen(val label: String) {
    object Home : Screen("Home")
}

// --- Classe para resultado detalhado ---
data class ResultadoPrevisao(
    val normal: Float,
    val bronquite: Float,
    val pneumonia: Float
)

// --- Função para chamar API detalhada ---
fun chamarApiPrevisaoDetalhada(audioBytes: ByteArray, onResult: (ResultadoPrevisao?) -> Unit) {
    val cliente = OkHttpClient()
    val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
    val jsonBody = JSONObject().put("audio_base64", audioBase64).toString()

    val body = RequestBody.create(
        "application/json; charset=utf-8".toMediaTypeOrNull(),
        jsonBody
    )

    val request = Request.Builder()
        .url("https://conectapi.click/api3/prever")
        .post(body)
        .build()

    cliente.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
            onResult(null)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { res ->
                try {
                    val body = res.body?.string()
                    if (!res.isSuccessful || body.isNullOrEmpty()) {
                        onResult(null)
                        return
                    }

                    val json = JSONObject(body)
                    // Recebe os valores brutos
                    val normal = json.optDouble("Normal", 0.0).toFloat()
                    val bronquite = json.optDouble("Bronquite", 0.0).toFloat()
                    val pneumonia = json.optDouble("Pneumonia", 0.0).toFloat()

                    // --- normaliza para soma 1.0 (100%) ---
                    val total = normal + bronquite + pneumonia
                    val normalized = if (total > 0f) {
                        ResultadoPrevisao(
                            normal = normal / total,
                            bronquite = bronquite / total,
                            pneumonia = pneumonia / total
                        )
                    } else {
                        ResultadoPrevisao(0f, 0f, 0f)
                    }

                    onResult(normalized)
                } catch (e: Exception) {
                    e.printStackTrace()
                    onResult(null)
                }
            }
        }
    })
}


// --- Barra de previsão para cada classe ---
@Composable
fun BarraPrevisao(label: String, value: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = value.coerceIn(0f,1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(Color.Gray.copy(alpha = 0.2f)),
            color = when(label) {
                "Normal" -> Color.Green
                "Bronquite" -> Color.Yellow
                "Pneumonia" -> Color.Red
                else -> Color.Blue
            }
        )
    }
}

// --- Tela de resultado detalhado ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaResultadoDetalhado(
    prediction: ResultadoPrevisao,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Resultado") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Resultado da Análise", style = MaterialTheme.typography.headlineMedium)

            BarraPrevisao("Normal", prediction.normal)
            BarraPrevisao("Bronquite", prediction.bronquite)
            BarraPrevisao("Pneumonia", prediction.pneumonia)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Este resultado é apenas auxiliar. Procure um profissional de saúde.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("OK")
            }
        }
    }
}

// --- Ajuste no AppScreen() para usar o resultado detalhado ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    var showResult by remember { mutableStateOf(false) }
    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var soundCaptured by remember { mutableStateOf(false) }
    var recordedAudio by remember { mutableStateOf<ByteArray?>(null) }
    var currentPrediction by remember { mutableStateOf<ResultadoPrevisao?>(null) }

    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            if (!showResult) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedScreen is Screen.Home,
                        onClick = { selectedScreen = Screen.Home },
                        icon = { Icon(Icons.Default.Mic, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showResult && currentPrediction != null) {
                TelaResultadoDetalhado(
                    prediction = currentPrediction!!,
                    onBack = { showResult = false }
                )
            } else {
                when (selectedScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            onFinishRecording = { /* antigo resultado Alta/Média */ },
                            onSoundCaptured = { soundCaptured = true },
                            onSoundCapturedFalse = { soundCaptured = false },
                            onAudioCaptured = { audio -> recordedAudio = audio }
                        )
                    }
                }
            }

            if (soundCaptured) {
                var isProcessing by remember { mutableStateOf(false) }

                DialogoCaputaraSom(
                    isProcessing = isProcessing,
                    onAnalyze = {
                        isProcessing = true
                        recordedAudio?.let { audio ->
                            chamarApiPrevisaoDetalhada(audio) { result ->
                                scope.launch(Dispatchers.Main) {
                                    if (result != null) {
                                        currentPrediction = result
                                        showResult = true
                                    } else {
                                        currentPrediction = ResultadoPrevisao(0f, 0f, 0f)
                                        showResult = true
                                    }
                                    soundCaptured = false
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    onRepeat = {
                        soundCaptured = false
                    }
                )

            }
        }
    }
}


// --- Tela inicial ---
@Composable
fun HomeScreen(
    onFinishRecording: () -> Unit,
    onSoundCaptured: () -> Unit,
    onSoundCapturedFalse: () -> Unit,
    onAudioCaptured: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AzScan",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Análise de tosse para saúde respiratória.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        BotaoGravacao(
            permissionGranted = permissionGranted,
            onRequestPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            onStopRecording = onFinishRecording,
            onSoundDetected = onSoundCaptured,
            onAudioCaptured = { audio ->
                if (audio == null) {
                    // Nenhuma tosse detectada: apenas mostra Toast
                    Toast.makeText(context, "Nenhuma tosse detectada. Por favor, tente novamente.", Toast.LENGTH_SHORT).show()
                    onSoundCapturedFalse() // chamando função para definir falso
                } else {
                    // Som detectado: atualiza pai e mostra dialog
                    onAudioCaptured(audio)
                    onSoundCaptured()  // apenas aqui!
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pressione o botão e tussa próximo ao telefone",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// --- Botão de gravação com barra animada e detecção de som ---
@Composable
fun BotaoGravacao(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onStopRecording: () -> Unit,
    onSoundDetected: () -> Unit,
    onAudioCaptured: (ByteArray?) -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }
    var amplitude by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val recorderState = remember { mutableStateOf<AudioRecord?>(null) }
    val recordedBuffer = remember { mutableStateListOf<Short>() }
    val showNoCoughDialog = remember { mutableStateOf(false) }

    val context = LocalContext.current // garante context válido

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {
                // --- Verifica permissão ---
                if (!permissionGranted) {
                    Toast.makeText(context, "Permissão de microfone necessária.", Toast.LENGTH_SHORT).show()
                    onRequestPermission()
                    return@Button
                }

                if (!isRecording) {
                    isRecording = true
                    recordedBuffer.clear()
                    var coughDetected = false
                    val sampleRate = 16000
                    val bufferSize = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                    try {
                        val recorder = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )

                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            throw IllegalStateException("AudioRecord não pôde ser inicializado")
                        }

                        if (NoiseSuppressor.isAvailable()) {
                            NoiseSuppressor.create(recorder.audioSessionId)
                        }

                        recorderState.value = recorder
                        recorder.startRecording()
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                        Toast.makeText(context, "Permissão de microfone negada.", Toast.LENGTH_SHORT).show()
                        isRecording = false
                        return@Button
                    } catch (e: Exception) {
                        e.printStackTrace()
                        isRecording = false
                        return@Button
                    }

                    // --- Gravação em coroutine ---
                    scope.launch(Dispatchers.IO) {
                        val buffer = ShortArray(bufferSize)
                        val MAX_SAMPLES = 320000

                        while (isRecording && recordedBuffer.size < MAX_SAMPLES) {
                            val read = recorderState.value?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) {
                                recordedBuffer.addAll(buffer.take(read))
                                val max = buffer.take(read).maxOrNull() ?: 0
                                val normalized = max.toFloat() / 32768f

                                if (normalized > 0.1f) {
                                    coughDetected = true
                                }

                                withContext(Dispatchers.Main) {
                                    amplitude = normalized.coerceIn(0f, 1f)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            try {
                                recorderState.value?.stop()
                                recorderState.value?.release()
                                recorderState.value = null
                            } catch (e: Exception) { e.printStackTrace() }

                            isRecording = false
                            amplitude = 0f
                            onSoundDetected()

                            if (coughDetected) {
                                val shortArray = if (recordedBuffer.size < MAX_SAMPLES) {
                                    recordedBuffer.toShortArray() + ShortArray(MAX_SAMPLES - recordedBuffer.size)
                                } else {
                                    recordedBuffer.take(MAX_SAMPLES).toShortArray()
                                }

                                val byteBuffer = ByteBuffer.allocate(MAX_SAMPLES * 2)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                shortArray.forEach { s -> byteBuffer.putShort(s) }
                                onAudioCaptured(byteBuffer.array())
                            } else {
                                onAudioCaptured(null)
                                showNoCoughDialog.value = true
                            }
                        }
                    }

                } else {
                    // Stop manual
                    isRecording = false
                    try {
                        recorderState.value?.stop()
                        recorderState.value?.release()
                        recorderState.value = null
                    } catch (e: Exception) { e.printStackTrace() }
                    amplitude = 0f
                    onStopRecording()
                }
            },
            shape = CircleShape,
            modifier = Modifier.size(120.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Gravar tosse",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isRecording) {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth(0.7f)
                    .background(Color.Gray.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(amplitude)
                        .background(Color.Red)
                )
            }
        }
    }

    if (showNoCoughDialog.value) {
        AlertDialog(
            onDismissRequest = { showNoCoughDialog.value = false },
            title = { Text("Nenhuma tosse detectada") },
            text = { Text("Não foi capturado nenhum som de tosse. Tente novamente.") },
            confirmButton = {
                Button(
                    onClick = { showNoCoughDialog.value = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OK") }
            }
        )
    }
}

// --- Dialog central de som capturado ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoCaputaraSom(
    onAnalyze: () -> Unit,
    onRepeat: () -> Unit,
    isProcessing: Boolean = false
) {
    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = "Som Capturado",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Deseja analisar o resultado ou repetir a gravação?",
                    textAlign = TextAlign.Center
                )

                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onAnalyze,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Analisar resultado")
                }
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = onRepeat,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Repetir gravação")
                }
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewHome() {
    AzScanTheme {
        AppScreen()
    }
}
