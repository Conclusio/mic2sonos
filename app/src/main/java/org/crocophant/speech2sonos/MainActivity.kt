package org.crocophant.speech2sonos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.crocophant.speech2sonos.ui.theme.Speech2SonosTheme

class MainActivity : ComponentActivity() {

    private lateinit var sonosDiscovery: SonosDiscovery
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var sonosController: SonosController
    private lateinit var appSettings: AppSettings

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                viewModel.onPermissionGranted()
            }
        }

    private val viewModel: SonosViewModel by lazy {
        ViewModelProvider(this, SonosViewModelFactory(audioStreamer, sonosController, appSettings, sonosDiscovery.devices))[SonosViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sonosDiscovery = SonosDiscovery(this)
        audioStreamer = AudioStreamer()
        sonosController = SonosController(this)
        appSettings = AppSettings(this)

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
                        viewModel = viewModel,
                        onAddDummyDevices = { sonosDiscovery.addDummyDevices() }
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

class SonosViewModelFactory(
    private val audioStreamer: AudioStreamer,
    private val sonosController: SonosController,
    private val appSettings: AppSettings,
    private val discoveredDevices: StateFlow<List<SonosDevice>>
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SonosViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SonosViewModel(audioStreamer, sonosController, appSettings, discoveredDevices) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class SonosViewModel(
    private val audioStreamer: AudioStreamer,
    private val sonosController: SonosController,
    private val appSettings: AppSettings,
    private val discoveredDevices: StateFlow<List<SonosDevice>>
) : ViewModel() {
    private val _selectedDevices = MutableStateFlow<Set<SonosDevice>>(emptySet())
    val selectedDevices: StateFlow<Set<SonosDevice>> = _selectedDevices.asStateFlow()
    
    private val _devicesWithNowPlaying = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devicesWithNowPlaying: StateFlow<List<SonosDevice>> = _devicesWithNowPlaying.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    val waveformData: StateFlow<List<Float>> = audioStreamer.waveformData
    
    val amplification: StateFlow<Int> = appSettings.amplification
    val announcementMode: StateFlow<Boolean> = appSettings.announcementMode
    val announcementVolume: StateFlow<Int> = appSettings.announcementVolume

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var permissionGranted by mutableStateOf(false)
        private set

    fun onPermissionGranted() {
        permissionGranted = true
    }
    
    fun setAmplification(value: Int) {
        appSettings.setAmplification(value)
        audioStreamer.setAmplification(value)
    }
    
    fun setAnnouncementMode(enabled: Boolean) {
        appSettings.setAnnouncementMode(enabled)
    }
    
    fun setAnnouncementVolume(value: Int) {
        appSettings.setAnnouncementVolume(value)
    }
    
    init {
        // Sync initial amplification to AudioStreamer
        audioStreamer.setAmplification(appSettings.amplification.value)
        
        // Initialize with discovered devices (only once)
        viewModelScope.launch {
            discoveredDevices.collect { devices ->
                Log.d("SonosViewModel", "Discovered ${devices.size} devices")
                // Only update if the device list actually changed
                val currentIps = _devicesWithNowPlaying.value.map { it.ipAddress }.toSet()
                val newIps = devices.map { it.ipAddress }.toSet()
                
                if (currentIps != newIps) {
                    // Device list changed, update it (preserving now playing info for existing ones)
                    val mapped = devices.map { newDevice ->
                        val existing = _devicesWithNowPlaying.value.find { it.ipAddress == newDevice.ipAddress }
                        if (existing != null) {
                            // Preserve existing now playing info
                            Log.d("SonosViewModel", "Preserving now playing for ${newDevice.name}: '${existing.nowPlayingInfo.title}'")
                            newDevice.copy(nowPlayingInfo = existing.nowPlayingInfo)
                        } else {
                            Log.d("SonosViewModel", "New device: ${newDevice.name}")
                            newDevice
                        }
                    }
                    Log.d("SonosViewModel", "Device list changed, updating _devicesWithNowPlaying with ${mapped.size} devices")
                    _devicesWithNowPlaying.value = mapped
                    
                    // Auto-select devices that were previously selected (if they're now discovered)
                    val savedIps = appSettings.getSelectedDeviceIPs()
                    if (savedIps.isNotEmpty()) {
                        val devicesToSelect = mapped.filter { it.ipAddress in savedIps }.toSet()
                        if (devicesToSelect.isNotEmpty()) {
                            Log.d("SonosViewModel", "Restoring ${devicesToSelect.size} previously selected devices")
                            _selectedDevices.value = devicesToSelect
                        }
                    }
                } else {
                    Log.d("SonosViewModel", "Device list unchanged, not updating")
                }
            }
        }
        
        // Separate coroutine for periodic refreshing of now playing info
        viewModelScope.launch {
            while (true) {
                try {
                    val currentDevices = _devicesWithNowPlaying.value
                    if (currentDevices.isNotEmpty()) {
                        Log.d("SonosViewModel", "Refreshing now playing for ${currentDevices.size} devices")
                        
                        val updatedDevices = mutableListOf<SonosDevice>()
                        currentDevices.forEach { device ->
                            try {
                                val info = sonosController.getNowPlaying(device)
                                Log.d("SonosViewModel", "Got now playing for ${device.name}: '${info.title}'")
                                updatedDevices.add(device.copy(nowPlayingInfo = info))
                            } catch (e: Exception) {
                                Log.e("SonosViewModel", "Failed to get now playing for ${device.name}", e)
                                updatedDevices.add(device)
                            }
                        }
                        
                        Log.d("SonosViewModel", "Setting _devicesWithNowPlaying to ${updatedDevices.size} devices with updated info: ${updatedDevices.map { it.nowPlayingInfo.title }}")
                        _devicesWithNowPlaying.value = updatedDevices.toList()
                    }
                } catch (e: Exception) {
                    Log.e("SonosViewModel", "Error in now playing refresh loop", e)
                }
                kotlinx.coroutines.delay(3000) // Refresh every 3 seconds
            }
        }
    }

    fun toggleDeviceSelection(device: SonosDevice) {
        val currentSelection = _selectedDevices.value.toMutableSet()
        // Check by IP address since device objects may differ
        val existingDevice = currentSelection.find { it.ipAddress == device.ipAddress }
        if (existingDevice != null) {
            currentSelection.remove(existingDevice)
        } else {
            currentSelection.add(device)
        }
        _selectedDevices.value = currentSelection
        // Save selected device IPs to preferences
        appSettings.saveSelectedDeviceIPs(currentSelection.map { it.ipAddress }.toSet())
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
                
                _isRecording.value = true  // Enable visualization
                
                _selectedDevices.value.forEach { device ->
                    try {
                        sonosController.playLocalTest(device, ipAddress)
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Test error: ${e.message}"
                _isRecording.value = false
            }
        }
    }
    
    fun testStaticWav() {
        if (_selectedDevices.value.isEmpty()) {
            _errorMessage.value = "Please select at least one device"
            return
        }

        viewModelScope.launch {
            try {
                // Start the server first (needed to serve static WAV)
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
                
                android.util.Log.i("SonosViewModel", "Testing static WAV at http://$ipAddress:8080/test-static.wav")
                _selectedDevices.value.forEach { device ->
                    try {
                        sonosController.playStaticTest(device, ipAddress)
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Static WAV test error: ${e.message}"
            }
        }
    }
    
    fun startHlsStream() {
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
                val started = audioStreamer.start()
                if (!started) {
                    _errorMessage.value = "Failed to start audio streaming"
                    return@launch
                }
                
                val ipAddress = sonosController.getDeviceIpAddress()
                if (ipAddress == null) {
                    _errorMessage.value = "Could not determine device IP address"
                    audioStreamer.stop()
                    return@launch
                }
                
                // Wait a bit for first HLS segment to be created
                android.util.Log.i("SonosViewModel", "Waiting for HLS segments to build...")
                kotlinx.coroutines.delay(2500) // Wait for first segment
                
                _isRecording.value = true
                
                android.util.Log.i("SonosViewModel", "Starting HLS stream at http://$ipAddress:8080/live.m3u8")
                _selectedDevices.value.forEach { device ->
                    try {
                        sonosController.playHlsStream(device, ipAddress)
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "HLS stream error: ${e.message}"
                _isRecording.value = false
            }
        }
    }
    
    /**
     * Test the announce feature with a sample audio file.
     * This plays audio over currently playing music with automatic ducking.
     */
    fun testAnnounce() {
        if (_selectedDevices.value.isEmpty()) {
            _errorMessage.value = "Please select at least one device"
            return
        }

        viewModelScope.launch {
            try {
                // Use a well-known test audio file
                val testUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
                android.util.Log.i("SonosViewModel", "Testing announcement with: $testUrl")
                
                _selectedDevices.value.forEach { device ->
                    try {
                        val result = sonosController.playAnnouncement(device, testUrl, volume = 30)
                        result.fold(
                            onSuccess = { response ->
                                if (response.success) {
                                    android.util.Log.i("SonosViewModel", "Announcement started on ${device.name}, clipId: ${response.clipId}")
                                } else {
                                    _errorMessage.value = "Announce failed on ${device.name}: ${response.error}"
                                }
                            },
                            onFailure = { e ->
                                _errorMessage.value = "Announce failed on ${device.name}: ${e.message}"
                            }
                        )
                    } catch (e: Exception) {
                        _errorMessage.value = "Announce failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Announce error: ${e.message}"
            }
        }
    }
    
    /**
     * Start streaming microphone audio as an announcement.
     * The currently playing music will be ducked automatically.
     */
    fun startAnnouncementStream() {
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
                val started = audioStreamer.start()
                if (!started) {
                    _errorMessage.value = "Failed to start audio streaming"
                    return@launch
                }
                
                val ipAddress = sonosController.getDeviceIpAddress()
                if (ipAddress == null) {
                    _errorMessage.value = "Could not determine device IP address"
                    audioStreamer.stop()
                    return@launch
                }
                
                _isRecording.value = true
                
                android.util.Log.i("SonosViewModel", "Starting announcement stream at http://$ipAddress:8080/stream.wav")
                _selectedDevices.value.forEach { device ->
                    try {
                        val result = sonosController.playAnnouncementStream(device, ipAddress, volume = 50)
                        result.fold(
                            onSuccess = { response ->
                                if (response.success) {
                                    android.util.Log.i("SonosViewModel", "Announcement stream started on ${device.name}")
                                } else {
                                    _errorMessage.value = "Announce stream failed on ${device.name}: ${response.error}"
                                }
                            },
                            onFailure = { e ->
                                _errorMessage.value = "Announce stream failed on ${device.name}: ${e.message}"
                            }
                        )
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed on ${device.name}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Announcement stream error: ${e.message}"
                _isRecording.value = false
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

        val useAnnouncement = appSettings.announcementMode.value
        val streamUrl = "http://$ipAddress:8080/stream.wav"
        
        _selectedDevices.value.forEach { device ->
            try {
                if (useAnnouncement) {
                    // Use announcement mode with ducking
                    val volume = appSettings.announcementVolume.value
                    val result = sonosController.playAnnouncementStream(device, ipAddress, volume = volume)
                    result.fold(
                        onSuccess = { response ->
                            if (!response.success) {
                                _errorMessage.value = "Announce failed on ${device.name}: ${response.error}"
                            }
                        },
                        onFailure = { e ->
                            _errorMessage.value = "Announce failed on ${device.name}: ${e.message}"
                        }
                    )
                } else {
                    // Use traditional streaming without ducking
                    sonosController.play(device, streamUrl, "Live Microphone", forceRadio = false)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to start playback on ${device.name}: ${e.message}"
            }
        }
    }

    private suspend fun stopStreaming() {
        val useAnnouncement = appSettings.announcementMode.value
        
        // Only stop device playback if not using announcement mode
        // In announcement mode, stopping the audio stream will naturally end the clip
        if (!useAnnouncement) {
            _selectedDevices.value.forEach { device ->
                try {
                    sonosController.stop(device)
                } catch (e: Exception) {
                    // Ignore stop errors
                }
            }
        }
        audioStreamer.stop()
        _isRecording.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }
    
    fun refreshDiscovery() {
        viewModelScope.launch {
            Log.d("SonosViewModel", "Manual discovery refresh triggered")
            discoveredDevices.collect { devices ->
                Log.d("SonosViewModel", "Refreshed ${devices.size} devices")
                val currentIps = _devicesWithNowPlaying.value.map { it.ipAddress }.toSet()
                val newIps = devices.map { it.ipAddress }.toSet()
                
                if (currentIps != newIps) {
                    val mapped = devices.map { newDevice ->
                        val existing = _devicesWithNowPlaying.value.find { it.ipAddress == newDevice.ipAddress }
                        if (existing != null) {
                            newDevice.copy(nowPlayingInfo = existing.nowPlayingInfo)
                        } else {
                            newDevice
                        }
                    }
                    _devicesWithNowPlaying.value = mapped
                }
                return@collect
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosScreen(
    modifier: Modifier = Modifier,
    devicesFlow: StateFlow<List<SonosDevice>>,
    viewModel: SonosViewModel,
    onAddDummyDevices: () -> Unit = {}
) {
    val devices by viewModel.devicesWithNowPlaying.collectAsState()
    val selectedDevices by viewModel.selectedDevices.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val amplification by viewModel.amplification.collectAsState()
    val announcementMode by viewModel.announcementMode.collectAsState()
    val announcementVolume by viewModel.announcementVolume.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showGainSlider by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    
    LaunchedEffect(devices) {
        Log.d("SonosScreen", "devices updated: ${devices.size} devices")
        devices.forEach { device ->
            Log.d("SonosScreen", "  - ${device.name}: title='${device.nowPlayingInfo.title}', artwork='${device.nowPlayingInfo.artworkUrl}'")
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState
        ) {
            SettingsContent(
                amplification = amplification,
                announcementMode = announcementMode,
                announcementVolume = announcementVolume,
                onAmplificationChange = { viewModel.setAmplification(it) },
                onAnnouncementModeChange = { viewModel.setAnnouncementMode(it) },
                onAnnouncementVolumeChange = { viewModel.setAnnouncementVolume(it) },
                onAddDummyDevices = onAddDummyDevices
            )
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
            // Header with settings button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = { viewModel.refreshDiscovery() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh device discovery")
                    }
                    if (devices.isEmpty()) {
                        Text(
                            text = "Searching for Sonos devices...",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = "${devices.size} device${if (devices.size != 1) "s" else ""} found",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

            DeviceList(
                devices = devices,
                selectedDevices = selectedDevices,
                onDeviceSelectionChanged = { device -> viewModel.toggleDeviceSelection(device) },
                modifier = Modifier.weight(1f),
                context = LocalContext.current
            )

            // Inset divider with vertical spacing
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Divider(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .align(Alignment.Center)
                )
            }

            // Waveform with tap-to-show gain slider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp)
                    .clickable { showGainSlider = !showGainSlider }
            ) {
                AudioWaveformVisualizer(
                    waveformData = waveformData,
                    isRecording = isRecording,
                    showDeviceSelectionHint = selectedDevices.isEmpty(),
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Gain slider (hidden by default, shown on waveform tap)
            AnimatedVisibility(
                visible = showGainSlider,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                GainSlider(
                    value = amplification,
                    onValueChange = { viewModel.setAmplification(it) },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            BottomControls(
                isRecording = isRecording,
                onToggleRecording = { viewModel.toggleRecording() },
                enabled = viewModel.permissionGranted && selectedDevices.isNotEmpty(),
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
    modifier: Modifier = Modifier,
    context: android.content.Context? = null
) {
    LazyColumn(modifier = modifier.padding(horizontal = 8.dp)) {
        items(devices) { device ->
            // Check if selected by IP address (more stable than object equality)
            val isSelected = selectedDevices.any { it.ipAddress == device.ipAddress }
            DeviceCard(
                device = device,
                isSelected = isSelected,
                onSelectionChanged = { onDeviceSelectionChanged(device) },
                context = context
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: SonosDevice,
    isSelected: Boolean,
    onSelectionChanged: () -> Unit,
    modifier: Modifier = Modifier,
    context: android.content.Context? = null
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onSelectionChanged() },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Device name and checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (device.nowPlayingInfo.title.isEmpty()) {
                Text(
                    text = "Fetching track information...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Album art
                    if (device.nowPlayingInfo.artworkUrl.isNotEmpty()) {
                        coil.compose.AsyncImage(
                            model = device.nowPlayingInfo.artworkUrl,
                            contentDescription = "Album art",
                            modifier = Modifier
                                .size(60.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Music",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.nowPlayingInfo.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        if (device.nowPlayingInfo.artist.isNotEmpty() || device.nowPlayingInfo.album.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    if (device.nowPlayingInfo.artist.isNotEmpty()) {
                                        append(device.nowPlayingInfo.artist)
                                    }
                                    if (device.nowPlayingInfo.album.isNotEmpty()) {
                                        if (isNotEmpty()) append(" - ")
                                        append(device.nowPlayingInfo.album)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                            )
                        }
                    }
                    
                    // Lyrics button
                    IconButton(
                        onClick = {
                            if (context != null) {
                                // Remove text after dash (e.g., "- 2008 Remaster")
                                val cleanTitle = device.nowPlayingInfo.title.split(" - ").first().trim()
                                val query = "${device.nowPlayingInfo.artist} $cleanTitle".trim()
                                Log.d("DeviceCard", "Searching Genius for: $query")
                                val geniusUrl = "https://genius.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geniusUrl))
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = "View lyrics",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(
    waveformData: List<Float>,
    isRecording: Boolean,
    showDeviceSelectionHint: Boolean = false,
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (showDeviceSelectionHint) {
                    Text(
                        text = "Select a device first",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRecording) "Waiting for audio..." else "Press",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = onSurfaceVariant
                    )
                    Text(
                        text = "to start",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                }
            }
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
fun GainSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Gain", style = MaterialTheme.typography.labelMedium)
            Text("${value}x", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 1f..20f,
            steps = 18,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsContent(
    amplification: Int,
    announcementMode: Boolean,
    announcementVolume: Int,
    onAmplificationChange: (Int) -> Unit,
    onAnnouncementModeChange: (Boolean) -> Unit,
    onAnnouncementVolumeChange: (Int) -> Unit,
    onAddDummyDevices: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Microphone Gain", style = MaterialTheme.typography.bodyLarge)
                Text("${amplification}x", style = MaterialTheme.typography.bodyLarge)
            }
            Slider(
                value = amplification.toFloat(),
                onValueChange = { onAmplificationChange(it.toInt()) },
                valueRange = 1f..20f,
                steps = 18,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Increase if audio is too quiet, decrease if distorted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Announcement Mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (announcementMode) "Ducks playback when speaking" else "Overlays microphone audio",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = announcementMode,
                onCheckedChange = onAnnouncementModeChange
            )
        }
        
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Announcement Volume", style = MaterialTheme.typography.bodyLarge)
                Text("${announcementVolume}", style = MaterialTheme.typography.bodyLarge)
            }
            Slider(
                value = announcementVolume.toFloat(),
                onValueChange = { onAnnouncementVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Volume level for announcements (0-100)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
         
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedButton(
            onClick = onAddDummyDevices,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Dummy Devices (Testing)")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun BottomControls(
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FloatingActionButton(
            onClick = onToggleRecording,
            modifier = Modifier.size(72.dp),
            containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary,
            contentColor = if (isRecording) Color.White else MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation()
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop streaming" else "Start streaming",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
