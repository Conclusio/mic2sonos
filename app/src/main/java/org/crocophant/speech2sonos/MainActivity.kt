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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
        ViewModelProvider(this, SonosViewModelFactory(audioStreamer, sonosController, appSettings, sonosDiscovery.devices, this))[SonosViewModel::class.java]
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
                viewModel = viewModel,
                onAddDummyDevices = { sonosDiscovery.addDummyDevices() },
                onRefreshDiscovery = { refreshDiscovery() }
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
    
    fun refreshDiscovery() {
        sonosDiscovery.restartDiscovery()
    }
}

class SonosViewModelFactory(
    private val audioStreamer: AudioStreamer,
    private val sonosController: SonosController,
    private val appSettings: AppSettings,
    private val discoveredDevices: StateFlow<List<SonosDevice>>,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SonosViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SonosViewModel(audioStreamer, sonosController, appSettings, discoveredDevices, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class SonosViewModel(
    private val audioStreamer: AudioStreamer,
    private val sonosController: SonosController,
    private val appSettings: AppSettings,
    private val discoveredDevices: StateFlow<List<SonosDevice>>,
    private val context: android.content.Context
) : ViewModel() {
    private val _selectedDevices = MutableStateFlow<Set<SonosDevice>>(emptySet())
    val selectedDevices: StateFlow<Set<SonosDevice>> = _selectedDevices.asStateFlow()
    
    private val _devicesWithNowPlaying = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devicesWithNowPlaying: StateFlow<List<SonosDevice>> = _devicesWithNowPlaying.asStateFlow()

    enum class RecordingState { IDLE, INITIALIZING, RECORDING, STOPPING }
    
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    val isRecording: StateFlow<Boolean> = recordingState.map { it == RecordingState.RECORDING || it == RecordingState.INITIALIZING }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    val waveformData: StateFlow<List<Float>> = audioStreamer.waveformData
    
    val amplification: StateFlow<Int> = appSettings.amplification
    val announcementMode: StateFlow<Boolean> = appSettings.announcementMode
    val announcementVolume: StateFlow<Int> = appSettings.announcementVolume
    val pushToTalkMode: StateFlow<Boolean> = appSettings.pushToTalkMode

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var permissionGranted by mutableStateOf(false)
        private set

    private lateinit var eventSubscription: SonosEventSubscription
    private val subscribedDevices = mutableSetOf<String>() // IP addresses
    private var useEventSubscriptions = false
    private val POLLING_INTERVAL = 3000L // 3 seconds fallback

    fun onPermissionGranted() {
        permissionGranted = true
    }

    private fun initializeEventSubscriptions() {
        try {
            eventSubscription = SonosEventSubscription(context) { device, newInfo ->
                Log.d("SonosViewModel", "Event callback: Track changed on ${device.name}")
                val updated = _devicesWithNowPlaying.value.map { d ->
                    if (d.ipAddress == device.ipAddress) {
                        d.copy(nowPlayingInfo = newInfo)
                    } else {
                        d
                    }
                }
                _devicesWithNowPlaying.value = updated
            }
            
            // Try to start event server
            if (eventSubscription.startEventServer()) {
                useEventSubscriptions = true
                Log.d("SonosViewModel", "Event subscriptions initialized successfully")
                startSubscribingToDevices()
            } else {
                Log.w("SonosViewModel", "Failed to start event server, will use polling")
                useEventSubscriptions = false
            }
        } catch (e: Exception) {
            Log.e("SonosViewModel", "Failed to initialize event subscriptions", e)
            useEventSubscriptions = false
        }
    }

    private fun startSubscribingToDevices() {
        viewModelScope.launch {
            // Subscribe to currently discovered devices
            _devicesWithNowPlaying.collect { devices ->
                for (device in devices) {
                    if (device.ipAddress !in subscribedDevices) {
                        Log.d("SonosViewModel", "Attempting subscription to ${device.name}")
                        val success = eventSubscription.subscribeToDevice(device)
                        if (success) {
                            subscribedDevices.add(device.ipAddress)
                            Log.d("SonosViewModel", "Subscribed to ${device.name}")
                        } else {
                            Log.w("SonosViewModel", "Failed to subscribe to ${device.name}, will use polling for this device")
                        }
                    }
                }
            }
        }
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
    
    fun setPushToTalkMode(enabled: Boolean) {
        appSettings.setPushToTalkMode(enabled)
    }
    
    init {
        // Sync initial amplification to AudioStreamer
        audioStreamer.setAmplification(appSettings.amplification.value)
        
        // Initialize event subscriptions (try callback approach first)
        initializeEventSubscriptions()
        
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
        
        // Polling as fallback - only poll devices that don't have event subscriptions
        viewModelScope.launch {
            while (true) {
                try {
                    val currentDevices = _devicesWithNowPlaying.value
                    if (currentDevices.isNotEmpty()) {
                        // Only poll devices that are NOT subscribed to events
                        val devicesToPoll = currentDevices.filter { 
                            it.ipAddress !in subscribedDevices 
                        }
                        
                        if (devicesToPoll.isNotEmpty()) {
                            Log.d("SonosViewModel", "Polling ${devicesToPoll.size} unsubscribed devices (${currentDevices.size - devicesToPoll.size} via events)")
                            
                            val updatedDevices = mutableListOf<SonosDevice>()
                            currentDevices.forEach { device ->
                                if (device.ipAddress in subscribedDevices) {
                                    // This device uses event subscriptions, don't poll it
                                    updatedDevices.add(device)
                                } else {
                                    // Fallback polling for devices without subscriptions
                                    try {
                                        val info = sonosController.getNowPlaying(device)
                                        Log.v("SonosViewModel", "Polled now playing for ${device.name}: '${info.title}'")
                                        updatedDevices.add(device.copy(nowPlayingInfo = info))
                                    } catch (e: Exception) {
                                        Log.e("SonosViewModel", "Failed to poll ${device.name}", e)
                                        updatedDevices.add(device)
                                    }
                                }
                            }
                            
                            _devicesWithNowPlaying.value = updatedDevices.toList()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SonosViewModel", "Error in polling loop", e)
                }
                kotlinx.coroutines.delay(POLLING_INTERVAL)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        if (::eventSubscription.isInitialized) {
            eventSubscription.cleanup()
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
                val currentlyRecording = _recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.INITIALIZING
                if (currentlyRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
                _recordingState.value = RecordingState.IDLE
            }
        }
    }
    
    fun startRecording() {
        if (!permissionGranted || _selectedDevices.value.isEmpty()) {
            return
        }
        _recordingState.value = RecordingState.INITIALIZING
        viewModelScope.launch {
            try {
                startStreaming()
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
                _recordingState.value = RecordingState.IDLE
            }
        }
    }
    
    fun stopRecording() {
        _recordingState.value = RecordingState.STOPPING
        viewModelScope.launch {
            try {
                stopStreaming()
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
                _recordingState.value = RecordingState.IDLE
            }
        }
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

        _recordingState.value = RecordingState.RECORDING

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
        _recordingState.value = RecordingState.IDLE
    }

    fun clearError() {
        _errorMessage.value = null
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosScreen(
    modifier: Modifier = Modifier,
    viewModel: SonosViewModel,
    onAddDummyDevices: () -> Unit = {},
    onRefreshDiscovery: () -> Unit = {}
) {
    val devices by viewModel.devicesWithNowPlaying.collectAsState()
    val selectedDevices by viewModel.selectedDevices.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val amplification by viewModel.amplification.collectAsState()
    val announcementMode by viewModel.announcementMode.collectAsState()
    val announcementVolume by viewModel.announcementVolume.collectAsState()
    val pushToTalkMode by viewModel.pushToTalkMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var isVisualizerPressed by remember { mutableStateOf(false) }
    val recordingState by viewModel.recordingState.collectAsState()
    
    val noDeviceSelectedMessage = "Select a device first"
    val sheetState = rememberModalBottomSheetState()
    
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            onRefreshDiscovery()
            isRefreshing = false
        }
    }
    
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
                 pushToTalkMode = pushToTalkMode,
                 onAmplificationChange = { viewModel.setAmplification(it) },
                 onAnnouncementModeChange = { viewModel.setAnnouncementMode(it) },
                 onAnnouncementVolumeChange = { viewModel.setAnnouncementVolume(it) },
                 onPushToTalkModeChange = { viewModel.setPushToTalkMode(it) },
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
                    IconButton(onClick = { onRefreshDiscovery() }, modifier = Modifier.size(40.dp)) {
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

            PullToRefreshBox(
                modifier = Modifier.weight(1f),
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                },
                state = pullRefreshState
            ) {
                DeviceList(
                    devices = devices,
                    selectedDevices = selectedDevices,
                    onDeviceSelectionChanged = { device -> viewModel.toggleDeviceSelection(device) },
                    modifier = Modifier.fillMaxSize(),
                    context = LocalContext.current
                )
            }

            // Inset divider with vertical spacing
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .align(Alignment.Center)
                )
            }

            // Interactive waveform visualizer - serves as play/record button
             AudioWaveformVisualizer(
                 waveformData = waveformData,
                 recordingState = recordingState,
                 showDeviceSelectionHint = selectedDevices.isEmpty(),
                 isPushToTalk = pushToTalkMode,
                 deviceSelectionMessage = noDeviceSelectedMessage,
                 modifier = Modifier
                     .fillMaxWidth(0.9f)
                     .height(100.dp)
                     .align(Alignment.CenterHorizontally)
                     .pointerInput(pushToTalkMode, selectedDevices, noDeviceSelectedMessage) {
                         detectTapGestures(
                             onLongPress = {
                                 if (pushToTalkMode) {
                                     if (selectedDevices.isEmpty()) {
                                         viewModel.viewModelScope.launch {
                                             snackbarHostState.showSnackbar(noDeviceSelectedMessage)
                                         }
                                     } else {
                                         isVisualizerPressed = true
                                         if (!isRecording) viewModel.startRecording()
                                     }
                                 }
                             },
                             onPress = {
                                 if (pushToTalkMode) {
                                     this.tryAwaitRelease()
                                     isVisualizerPressed = false
                                     if (isRecording) viewModel.stopRecording()
                                 }
                             },
                             onTap = {
                                 if (!pushToTalkMode) {
                                     viewModel.toggleRecording()
                                 }
                             }
                         )
                     },
                     backgroundColor = when (recordingState) {
                         SonosViewModel.RecordingState.RECORDING -> Color(0xFFb54747)
                         SonosViewModel.RecordingState.INITIALIZING, SonosViewModel.RecordingState.STOPPING -> Color(0xFFb57947)
                         SonosViewModel.RecordingState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                     }
             )

            Spacer(modifier = Modifier.height(8.dp))
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
                            imageVector = Icons.Filled.Notes,
                            contentDescription = "View lyrics on Genius",
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
    recordingState: SonosViewModel.RecordingState,
    showDeviceSelectionHint: Boolean = false,
    isPushToTalk: Boolean = false,
    deviceSelectionMessage: String = "Select a device first",
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = MaterialTheme.shapes.medium
            ),
        contentAlignment = Alignment.Center
    ) {
        when (recordingState) {
            SonosViewModel.RecordingState.IDLE -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showDeviceSelectionHint) {
                        Text(
                            text = deviceSelectionMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = onSurfaceVariant
                        )
                        Text(
                            text = when {
                                isPushToTalk -> "Hold to stream"
                                else -> "Press to start"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = onSurfaceVariant
                        )
                    }
                }
            }

            SonosViewModel.RecordingState.INITIALIZING -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "Initializing stream...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                }
            }

            SonosViewModel.RecordingState.RECORDING -> {
                if (waveformData.isEmpty()) {
                    // Waveform data not yet available, show nothing
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

            SonosViewModel.RecordingState.STOPPING -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "Stopping stream...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsContent(
    amplification: Int,
    announcementMode: Boolean,
    announcementVolume: Int,
    pushToTalkMode: Boolean,
    onAmplificationChange: (Int) -> Unit,
    onAnnouncementModeChange: (Boolean) -> Unit,
    onAnnouncementVolumeChange: (Int) -> Unit,
    onPushToTalkModeChange: (Boolean) -> Unit,
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
        
        Column {
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
            
            // Announcement volume as sub-item
            AnimatedVisibility(
                visible = announcementMode,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Volume", style = MaterialTheme.typography.bodyMedium)
                        Text("${announcementVolume}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = announcementVolume.toFloat(),
                        onValueChange = { onAnnouncementVolumeChange(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Push-to-Talk Mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (pushToTalkMode) "Hold visualizer to stream" else "Tap visualizer to toggle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = pushToTalkMode,
                onCheckedChange = onPushToTalkModeChange
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


