package org.crocophant.speech2sonos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.crocophant.speech2sonos.ui.theme.Speech2SonosTheme

class MainActivity : ComponentActivity() {

    private lateinit var sonosDiscovery: SonosDiscovery
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var sonosController: SonosController

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                viewModel.onPermissionGranted()
            }
        }

    private val viewModel: SonosViewModel by lazy {
        ViewModelProvider(this, SonosViewModelFactory(audioStreamer, sonosController))[SonosViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sonosDiscovery = SonosDiscovery(this)
        audioStreamer = AudioStreamer()
        sonosController = SonosController(this)

        when (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> {
                viewModel.onPermissionGranted()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        setContent {
            Speech2SonosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SonosScreen(
                        modifier = Modifier.padding(innerPadding),
                        devicesFlow = sonosDiscovery.devices,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sonosDiscovery.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        sonosDiscovery.stopDiscovery()
    }
}

class SonosViewModelFactory(private val audioStreamer: AudioStreamer, private val sonosController: SonosController) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SonosViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SonosViewModel(audioStreamer, sonosController) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class SonosViewModel(private val audioStreamer: AudioStreamer, private val sonosController: SonosController) : ViewModel() {
    private val _selectedDevices = MutableStateFlow<Set<SonosDevice>>(emptySet())
    val selectedDevices: StateFlow<Set<SonosDevice>> = _selectedDevices.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    val waveformData: StateFlow<List<Float>> = audioStreamer.waveformData

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var permissionGranted by mutableStateOf(false)
        private set

    fun onPermissionGranted() {
        permissionGranted = true
    }

    fun toggleDeviceSelection(device: SonosDevice) {
        val currentSelection = _selectedDevices.value.toMutableSet()
        if (device in currentSelection) {
            currentSelection.remove(device)
        } else {
            currentSelection.add(device)
        }
        _selectedDevices.value = currentSelection
    }

    fun toggleRecording() {
        if (!permissionGranted) {
            _errorMessage.value = "Microphone permission not granted"
            return
        }

        if (_selectedDevices.value.isEmpty()) {
            _errorMessage.value = "Please select at least one device"
            return
        }

        viewModelScope.launch {
            try {
                val currentlyRecording = _isRecording.value
                if (currentlyRecording) {
                    stopStreaming()
                } else {
                    startStreaming()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
                _isRecording.value = false
            }
        }
    }

    fun toggleTestPlayback() {
        if (_selectedDevices.value.isEmpty()) {
            _errorMessage.value = "Please select at least one device"
            return
        }

        viewModelScope.launch {
            try {
                val currentlyTesting = _isTesting.value
                if (currentlyTesting) {
                    stopTestPlayback()
                } else {
                    startTestPlayback()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Test error: ${e.message}"
                _isTesting.value = false
            }
        }
    }

    fun testLocalServer() {
        if (_selectedDevices.value.isEmpty()) {
            _errorMessage.value = "Please select at least one device"
            return
        }

        viewModelScope.launch {
            try {
                // Start the server first
                val started = audioStreamer.start()
                if (!started) {
                    _errorMessage.value = "Failed to start server"
                    return@launch
                }
                
                val ipAddress = sonosController.getDeviceIpAddress()
                if (ipAddress == null) {
                    _errorMessage.value = "Could not determine device IP address"
                    audioStreamer.stop()
                    return@launch
                }
                
                android.util.Log.i("SonosViewModel", "Testing local server at http://$ipAddress:8080/test.mp3")
                _selectedDevices.value.forEach { device ->
                    try {
                        sonosController.playLocalTest(device, ipAddress)
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Test error: ${e.message}"
            }
        }
    }

    private suspend fun startTestPlayback() {
        _isTesting.value = true
        _selectedDevices.value.forEach { device ->
            try {
                sonosController.playTestAudio(device)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to test on ${device.name}: ${e.message}"
            }
        }
    }

    private suspend fun stopTestPlayback() {
        _selectedDevices.value.forEach { device ->
            try {
                sonosController.stop(device)
            } catch (e: Exception) {
                // Ignore stop errors
            }
        }
        _isTesting.value = false
    }

    private suspend fun startStreaming() {
        val started = audioStreamer.start()
        if (!started) {
            _errorMessage.value = "Failed to start audio streaming"
            return
        }

        val ipAddress = sonosController.getDeviceIpAddress()
        if (ipAddress == null) {
            _errorMessage.value = "Could not determine device IP address"
            audioStreamer.stop()
            return
        }

        _isRecording.value = true

        val streamUrl = "http://$ipAddress:8080/stream.aac"
        android.util.Log.i("SonosViewModel", "Starting stream with URL: $streamUrl")
        _selectedDevices.value.forEach { device ->
            try {
                android.util.Log.i("SonosViewModel", "Sending play command to ${device.name} (${device.ipAddress}:${device.port})")
                sonosController.play(device, streamUrl)
            } catch (e: Exception) {
                android.util.Log.e("SonosViewModel", "Failed to play on ${device.name}", e)
                _errorMessage.value = "Failed to start playback on ${device.name}: ${e.message}"
            }
        }
    }

    private suspend fun stopStreaming() {
        _selectedDevices.value.forEach { device ->
            try {
                sonosController.stop(device)
            } catch (e: Exception) {
                // Ignore stop errors
            }
        }
        audioStreamer.stop()
        _isRecording.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

@Composable
fun SonosScreen(
    modifier: Modifier = Modifier,
    devicesFlow: StateFlow<List<SonosDevice>>,
    viewModel: SonosViewModel
) {
    val devices by devicesFlow.collectAsState()
    val selectedDevices by viewModel.selectedDevices.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (devices.isEmpty()) {
                Text(
                    text = "Searching for Sonos devices...",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            DeviceList(
                devices = devices,
                selectedDevices = selectedDevices,
                onDeviceSelectionChanged = { device -> viewModel.toggleDeviceSelection(device) },
                modifier = Modifier.weight(1f)
            )

            AudioWaveformVisualizer(
                waveformData = waveformData,
                isRecording = isRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            BottomControls(
                isRecording = isRecording,
                isTesting = isTesting,
                onToggleRecording = { viewModel.toggleRecording() },
                onToggleTest = { viewModel.toggleTestPlayback() },
                onTestLocalServer = { viewModel.testLocalServer() },
                enabled = viewModel.permissionGranted,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
fun DeviceList(
    devices: List<SonosDevice>,
    selectedDevices: Set<SonosDevice>,
    onDeviceSelectionChanged: (SonosDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.padding(horizontal = 8.dp)) {
        items(devices) { device ->
            val isSelected = device in selectedDevices
            ListItem(
                headlineContent = { Text(text = device.name) },
                leadingContent = {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDeviceSelectionChanged(device) }
            )
        }
    }
}

@Composable
fun AudioWaveformVisualizer(
    waveformData: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .background(
                color = surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!isRecording || waveformData.isEmpty()) {
            Text(
                text = if (isRecording) "Waiting for audio..." else "Press record to start",
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariant
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val barCount = waveformData.size
                if (barCount == 0) return@Canvas

                val totalGapRatio = 0.2f
                val barWidthRatio = 1f - totalGapRatio
                val unitWidth = size.width / barCount
                val barWidth = unitWidth * barWidthRatio
                val gap = unitWidth * totalGapRatio
                val centerY = size.height / 2

                waveformData.forEachIndexed { index, amplitude ->
                    val barHeight = (amplitude * size.height * 0.95f).coerceAtLeast(6f)
                    val x = index * unitWidth + gap / 2
                    val y = centerY - barHeight / 2
                    
                    val barColor = if (amplitude > 0.7f) secondaryColor else primaryColor

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                    )
                }
            }
        }
    }
}

@Composable
fun BottomControls(
    isRecording: Boolean,
    isTesting: Boolean,
    onToggleRecording: () -> Unit,
    onToggleTest: () -> Unit,
    onTestLocalServer: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onToggleTest,
                enabled = enabled && !isRecording
            ) {
                Text(if (isTesting) "Stop Test" else "Test Audio")
            }
            
            OutlinedButton(
                onClick = onTestLocalServer,
                enabled = enabled && !isRecording
            ) {
                Text("Test Local")
            }
        }

        FloatingActionButton(
            onClick = onToggleRecording,
            modifier = Modifier.size(72.dp),
            containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary,
            contentColor = if (isRecording) Color.White else MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation()
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
