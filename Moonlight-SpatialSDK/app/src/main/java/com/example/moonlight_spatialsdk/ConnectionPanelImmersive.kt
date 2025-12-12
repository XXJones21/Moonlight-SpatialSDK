package com.example.moonlight_spatialsdk

import android.preference.PreferenceManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.limelight.nvstream.http.PairingManager
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.card.PrimaryCard
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.control.SpatialSwitch
import com.meta.spatial.uiset.dialog.SpatialBasicDialog
import com.meta.spatial.uiset.dialog.SpatialIconDialog
import com.meta.spatial.uiset.dropdown.SpatialDropdown
import com.meta.spatial.uiset.dropdown.foundation.SpatialDropdownItem
import com.meta.spatial.uiset.input.SpatialTextField
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.LocalTypography
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.CategoryAll
import com.meta.spatial.uiset.theme.icons.regular.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

@Composable
fun ConnectionPanelImmersive(
    pairingHelper: MoonlightPairingHelper,
    savedHost: String,
    savedPort: String,
    savedAppId: String,
    onSaveConnection: (String, String, String) -> Unit,
    onClearPairing: () -> Unit,
    onConnect: (String, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(savedHost) }
    var port by remember { mutableStateOf(savedPort) }
    var appId by remember { mutableStateOf(savedAppId) }
    var generatedPin by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf("Ready to connect") }
    var isConnected by remember { mutableStateOf(false) }
    var needsPairing by remember { mutableStateOf(false) }
    var isCheckingPairing by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var pairingAttemptKey by remember { mutableStateOf(0) }
    var showConfig by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showOptionsDialog by remember { mutableStateOf(false) }
    var dialogHost by remember { mutableStateOf("") }
    var dialogPort by remember { mutableStateOf("47989") }
    var serverName by remember { mutableStateOf<String?>(null) }
    var isPaired by remember { mutableStateOf(false) }
    
    val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    var selectedRes by remember { mutableStateOf(defaultPrefs.getString("list_resolution", "1280x720") ?: "1280x720") }
    var selectedFps by remember { mutableStateOf(defaultPrefs.getString("list_fps", "60") ?: "60") }
    var selectedFormat by remember {
        mutableStateOf(
            when (defaultPrefs.getString("video_format", "auto")) {
                "neverh265" -> "h264"
                "forceh265" -> "hevc"
                "forceav1" -> "av1"
                else -> "auto"
            }
        )
    }
    var resOptions by remember {
        mutableStateOf(
            listOf("640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160")
        )
    }
    var fpsOptions by remember { mutableStateOf(listOf("30", "60", "90", "120")) }
    var formatOptions by remember { mutableStateOf(listOf("auto", "h264", "hevc", "av1")) }
    var capabilitySummary by remember { mutableStateOf("") }
    var capabilityStatus by remember { mutableStateOf<String?>(null) }
    var enableHdr by remember { mutableStateOf(defaultPrefs.getBoolean("checkbox_enable_hdr", false)) }
    var enableFullRange by remember { mutableStateOf(defaultPrefs.getBoolean("checkbox_full_range", false)) }

    // App selection dropdown - only show if paired and app list is loaded
    var appList by remember { mutableStateOf<List<com.limelight.nvstream.http.NvApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var appListError by remember { mutableStateOf<String?>(null) }

    // Check pairing status and fetch server name when host/port changes
    LaunchedEffect(host, port) {
        if (host.isNotBlank() && port.isNotBlank()) {
            val portInt = port.toIntOrNull() ?: 47989
            // Always try to fetch server name when host is set, not just when paired
            pairingHelper.fetchServerName(host, portInt) { name ->
                serverName = name
            }
            pairingHelper.checkPairing(host, portInt) { isPairedResult, error ->
                isPaired = isPairedResult
            }
        } else {
            isPaired = false
            serverName = null
        }
    }

    // Fetch app list when pairing is verified
    LaunchedEffect(host, port, needsPairing) {
        if (!needsPairing && host.isNotBlank() && appList.isEmpty() && !isLoadingApps) {
            isLoadingApps = true
            appListError = null
            val portInt = port.toIntOrNull() ?: 47989
            pairingHelper.fetchAppList(host, portInt) { apps, error ->
                isLoadingApps = false
                if (apps != null) {
                    appList = apps
                    val currentAppIdInt = appId.toIntOrNull() ?: 0
                    val matchingApp = apps.find { it.getAppId() == currentAppIdInt }
                    if (matchingApp == null && apps.isNotEmpty()) {
                        appId = apps.first().getAppId().toString()
                    }
                } else {
                    appListError = error ?: "Failed to load app list"
                }
            }
        }
    }

    val scrollState = rememberScrollState()

    // Helper function to initiate pairing
    fun initiatePairing(pairHost: String, pairPort: String) {
        val portInt = pairPort.toIntOrNull() ?: 47989
        val pin = PairingManager.generatePinString()
        generatedPin = pin
        showPinDialog = true
        isPairing = true
        connectionStatus = "Enter PIN $pin on your server..."
        pairingHelper.pairWithServer(pairHost, portInt, pin) { success, error ->
            isPairing = false
            if (success) {
                connectionStatus = "Paired! Click Connect to continue"
                needsPairing = false
                generatedPin = null
                showPinDialog = false
                isPaired = true
                pairingHelper.fetchServerName(pairHost, portInt) { name ->
                    serverName = name
                }
            } else {
                when (error) {
                    "Incorrect PIN" -> {
                        connectionStatus = "PIN incorrect. Click 'Retry Pairing' to try again."
                    }
                    "Pairing already in progress" -> {
                        connectionStatus = "Another device is pairing. Please wait, then tap Retry."
                    }
                    else -> {
                        connectionStatus = error ?: "Pairing failed. Click 'Retry Pairing' to try again."
                    }
                }
            }
        }
    }

    SpatialTheme(colorScheme = getPanelTheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .verticalScroll(scrollState)
                .padding(16.dp),
        ) {
            // Enhanced title with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = SpatialIcons.Regular.CategoryAll,
                    contentDescription = null,
                    tint = SpatialTheme.colorScheme.primaryAlphaBackground,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Moonlight Connection",
                    style = LocalTypography.current.headline2Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f))
            Spacer(Modifier.height(12.dp))

            // Content with improved spacing
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Status section
                Text(
                    text = connectionStatus,
                    style = LocalTypography.current.body1Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                    ),
                )

                // Connection section with cards - side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // SecondaryCard - show server name or placeholder
                    SecondaryCard(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isPaired && host.isNotBlank()) {
                                // Connect to paired server
                                val portInt = port.toIntOrNull() ?: 47989
                                val appIdInt = appId.toIntOrNull() ?: 0
                                isCheckingPairing = true
                                connectionStatus = "Connecting..."
                                onSaveConnection(host, port, appId)
                                onConnect(host, portInt, appIdInt)
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = SpatialIcons.Regular.CategoryAll,
                                contentDescription = null,
                                tint = SpatialTheme.colorScheme.primaryAlphaBackground,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = serverName ?: host.takeIf { it.isNotBlank() } ?: "Ready to connect",
                                    style = LocalTypography.current.body1Strong.copy(
                                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                                    )
                                )
                                if (host.isNotBlank() && isPaired) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "$host:$port",
                                        style = LocalTypography.current.body2.copy(
                                            color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // SecondaryCard for Connect to PC / Pair New Server
                    SecondaryCard(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            dialogHost = host
                            dialogPort = port
                            showPairingDialog = true
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = SpatialTheme.colorScheme.primaryAlphaBackground,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = if (isPaired) "Pair New Server" else "Connect to PC",
                                style = LocalTypography.current.body1Strong.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground
                                )
                            )
                        }
                    }
                }

                // Application Selection Section
                if (appList.isNotEmpty()) {
                    val appOptions = appList.map { it.getAppName() }
                    val currentAppIdInt = appId.toIntOrNull() ?: 0
                    val selectedAppName = appList.find { it.getAppId() == currentAppIdInt }?.getAppName()
                        ?: appOptions.firstOrNull() ?: appId

                    LabeledDropdown(
                        label = "Application",
                        options = appOptions,
                        selected = selectedAppName,
                        onSelect = { selected ->
                            val selectedApp = appList.find { it.getAppName() == selected }
                            appId = selectedApp?.getAppId()?.toString() ?: "0"
                        },
                    )
                } else if (isLoadingApps) {
                    Text(
                        text = "Loading applications...",
                        style = LocalTypography.current.body1.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (appListError != null) {
                    Text(
                        text = "App list: $appListError",
                        style = LocalTypography.current.body1.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    SpatialTextField(
                        label = "App ID (fallback)",
                        placeholder = "0",
                        value = appId,
                        onValueChange = { appId = it },
                        enabled = !isConnected && !needsPairing,
                        autoValidate = false,
                        helperText = "Enter app ID manually if app list failed to load",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f))
                Spacer(Modifier.height(10.dp))

                // Action buttons section
                if (isConnected) {
                    SecondaryButton(
                        label = "Disconnect",
                        expanded = true,
                        onClick = {
                            isConnected = false
                            connectionStatus = "Disconnected"
                        },
                    )
                } else {
                    if (isCheckingPairing || isPairing) {
                        PrimaryButton(
                            label = if (isCheckingPairing) "Checking..." else "Pairing...",
                            expanded = true,
                            onClick = { },
                        )
                    } else {
                        if (needsPairing) {
                            if (isPairing) {
                                PrimaryButton(
                                    label = "Pairing in progress...",
                                    expanded = true,
                                    onClick = { },
                                )
                            } else {
                                PrimaryButton(
                                    label = "Retry Pairing",
                                    expanded = true,
                                    onClick = {
                                        pairingAttemptKey++
                                        generatedPin = null
                                        initiatePairing(host, port)
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Options button that opens dialog
                SecondaryButton(
                    label = "Options",
                    expanded = true,
                    onClick = {
                        showOptionsDialog = true
                    },
                )

                if (showConfig) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Stream Configuration",
                        style = LocalTypography.current.headline2Strong.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            text = "Decoder capabilities: $capabilitySummary",
                            style = LocalTypography.current.body1.copy(
                                color = SpatialTheme.colorScheme.primaryAlphaBackground
                            )
                        )
                        capabilityStatus?.let {
                            Text(
                                text = it,
                                style = LocalTypography.current.body1.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                )
                            )
                        }
                        Text(
                            text = "Select resolution / fps / format to apply before launch",
                            style = LocalTypography.current.body1.copy(
                                color = SpatialTheme.colorScheme.primaryAlphaBackground
                            )
                        )

                        LabeledDropdown(
                            label = "Resolution",
                            options = resOptions,
                            selected = selectedRes,
                            onSelect = { selectedRes = it },
                        )
                        LabeledDropdown(
                            label = "FPS",
                            options = fpsOptions,
                            selected = selectedFps,
                            onSelect = { selectedFps = it },
                        )
                        LabeledDropdown(
                            label = "Format",
                            options = formatOptions,
                            selected = selectedFormat,
                            onSelect = { selectedFormat = it },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable HDR",
                                    style = LocalTypography.current.body1.copy(
                                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                                    )
                                )
                                Text(
                                    text = "Request HDR from server",
                                    style = LocalTypography.current.body2.copy(
                                        color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                    )
                                )
                            }
                            SpatialSwitch(
                                checked = enableHdr,
                                onCheckedChange = { enableHdr = it }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prefer Full Range",
                                    style = LocalTypography.current.body1.copy(
                                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                                    )
                                )
                                Text(
                                    text = "Client output color range",
                                    style = LocalTypography.current.body2.copy(
                                        color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                    )
                                )
                            }
                            SpatialSwitch(
                                checked = enableFullRange,
                                onCheckedChange = { enableFullRange = it }
                            )
                        }

                        PrimaryButton(
                            label = "Apply Stream Settings",
                            expanded = true,
                            onClick = {
                                val shared = PreferenceManager.getDefaultSharedPreferences(context)
                                val storedFormat =
                                    when (selectedFormat) {
                                        "h264" -> "neverh265"
                                        "hevc" -> "forceh265"
                                        "av1" -> "forceav1"
                                        else -> "auto"
                                    }
                                shared.edit()
                                    .putString("list_resolution", selectedRes)
                                    .putString("list_fps", selectedFps)
                                    .putString("video_format", storedFormat)
                                    .putBoolean("checkbox_enable_hdr", enableHdr)
                                    .putBoolean("checkbox_full_range", enableFullRange)
                                    .apply()
                                connectionStatus = "Applied stream prefs (res/fps/format/HDR/range)"
                            },
                        )
                    }
                }
            }
        }

        // Pairing Dialog - Using SpatialBasicDialog structure with custom content overlay
        // Note: SpatialBasicDialog doesn't support custom content, so we create a custom dialog
        if (showPairingDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .clip(SpatialTheme.shapes.large)
                        .background(brush = LocalColorScheme.current.panel)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Connect to Server",
                        style = LocalTypography.current.headline2Strong.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground
                        )
                    )
                    Text(
                        text = "Enter server connection details",
                        style = LocalTypography.current.body1.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    SpatialTextField(
                        label = "IP Address",
                        placeholder = "192.168.1.100",
                        value = dialogHost,
                        onValueChange = { dialogHost = it },
                        autoValidate = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SpatialTextField(
                        label = "Port",
                        placeholder = "47989",
                        value = dialogPort,
                        onValueChange = { dialogPort = it },
                        autoValidate = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SecondaryButton(
                            label = "Cancel",
                            expanded = true,
                            onClick = { showPairingDialog = false }
                        )
                        PrimaryButton(
                            label = "Connect",
                            expanded = true,
                            onClick = {
                                host = dialogHost
                                port = dialogPort
                                showPairingDialog = false
                                val portInt = dialogPort.toIntOrNull() ?: 47989
                                pairingHelper.checkPairing(dialogHost, portInt) { isPairedResult, error ->
                                    if (isPairedResult) {
                                        connectionStatus = "Paired. Ready to connect."
                                        isPaired = true
                                        pairingHelper.fetchServerName(dialogHost, portInt) { name ->
                                            serverName = name
                                        }
                                    } else {
                                        needsPairing = true
                                        initiatePairing(dialogHost, dialogPort)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // PIN Dialog
        if (showPinDialog && generatedPin != null) {
            SpatialIconDialog(
                icon = {
                    Icon(
                        imageVector = SpatialIcons.Regular.Info,
                        contentDescription = null,
                        tint = SpatialTheme.colorScheme.primaryAlphaBackground,
                        modifier = Modifier.size(40.dp)
                    )
                },
                title = "Pairing PIN",
                description = "Enter this PIN on your server:\n\n$generatedPin",
                primaryChoiceActionLabel = "OK",
                onPrimaryChoiceActionClick = { showPinDialog = false }
            )
        }

        // Options Dialog with list of choices
        if (showOptionsDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .clip(SpatialTheme.shapes.large)
                        .background(brush = LocalColorScheme.current.panel)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Options",
                        style = LocalTypography.current.headline2Strong.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground
                        )
                    )
                    
                    // Configure Stream option
                    SecondaryCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showOptionsDialog = false
                            showConfig = !showConfig
                            if (showConfig) {
                                capabilitySummary = "Decoder capabilities: Standard H.264/HEVC/AV1 support"
                                capabilityStatus = "Loading server capabilities..."
                                val portInt = port.toIntOrNull() ?: 47989
                                if (host.isBlank()) {
                                    capabilityStatus = "Enter host/port to load capabilities"
                                } else {
                                    pairingHelper.fetchServerCapabilities(host, portInt) { caps, error ->
                                        if (caps == null || error != null) {
                                            capabilityStatus = error ?: "Failed to load server capabilities"
                                            return@fetchServerCapabilities
                                        }
                                        capabilityStatus = "Capabilities loaded"
                                        val baseRes =
                                            listOf("640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160")
                                        val maxPixels = listOf(caps.maxLumaH264, caps.maxLumaHEVC).maxOrNull() ?: 0
                                        val filteredRes =
                                            baseRes.filter { res ->
                                                val parts = res.split("x")
                                                val pixels =
                                                    if (parts.size == 2) parts[0].toLong() * parts[1].toLong() else Long.MAX_VALUE
                                                (maxPixels == 0L || pixels <= maxPixels) && (caps.supports4k || res != "3840x2160")
                                            }
                                        resOptions = if (filteredRes.isNotEmpty()) filteredRes else baseRes
                                        if (!resOptions.contains(selectedRes)) {
                                            selectedRes = resOptions.first()
                                        }
                                        val formatList = mutableListOf("auto")
                                        val codec = caps.codecModeSupport
                                        val hasH264 = codec == 0L || (codec and 0x3L) != 0L
                                        val hasHevc = codec == 0L || (codec and (1L shl 8 or (1L shl 9) or (1L shl 10))) != 0L
                                        if (hasH264) formatList.add("h264")
                                        if (hasHevc) formatList.add("hevc")
                                        formatList.add("av1")
                                        formatOptions = formatList.distinct()
                                        if (!formatOptions.contains(selectedFormat)) {
                                            selectedFormat = formatOptions.first()
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = if (showConfig) "Hide Stream Configuration" else "Configure Stream",
                                style = LocalTypography.current.body1Strong.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Device Capabilities",
                                style = LocalTypography.current.body2.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                )
                            )
                        }
                    }

                    // Reset Client Pairing option
                    SecondaryCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showOptionsDialog = false
                            onClearPairing()
                            isConnected = false
                            needsPairing = true
                            generatedPin = null
                            pairingAttemptKey++
                            isPaired = false
                            serverName = null
                            connectionStatus = "Cleared client pairing; re-pair required"
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = "Reset Client Pairing",
                                style = LocalTypography.current.body1Strong.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Clear cert & UID",
                                style = LocalTypography.current.body2.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.8f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    
                    SecondaryButton(
                        label = "Cancel",
                        expanded = true,
                        onClick = { showOptionsDialog = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LocalTypography.current.body1.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground
            )
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            val items = remember(options) { options.map { SpatialDropdownItem(title = it) } }
            var currentSelected by remember(selected, items) {
                mutableStateOf<SpatialDropdownItem?>(items.find { it.title == selected })
            }

            LaunchedEffect(selected) {
                items.find { it.title == selected }?.let { currentSelected = it }
            }

            SpatialDropdown(
                title = currentSelected?.title ?: selected,
                items = items,
                selectedItem = currentSelected,
                onItemSelected = { item ->
                    currentSelected = item
                    item.title?.let { onSelect(it) }
                },
            )
        }
    }
}
