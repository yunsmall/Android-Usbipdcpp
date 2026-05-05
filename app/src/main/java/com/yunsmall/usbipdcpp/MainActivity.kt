package com.yunsmall.usbipdcpp

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import java.net.NetworkInterface
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunsmall.usbipdcpp.ui.theme.UsbipdcppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    private var refreshDevicesCallback: (() -> Unit)? = null

    // 用于通知 Compose Service 状态变化
    private var onServiceStateChanged: (() -> Unit)? = null

    private var usbService: UsbService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UsbService.UsbBinder
            usbService = binder.getService()
            serviceBound = true
            refreshDevicesCallback?.invoke()
            onServiceStateChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbService = null
            serviceBound = false
            onServiceStateChanged?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionManager.registerReceiver()

        // 启动并绑定 Service
        val serviceIntent = Intent(this, UsbService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UsbipdcppTheme {
                // 用 State 观察 Service 变化
                var serviceState by remember { mutableStateOf(Pair<UsbService?, Boolean>(null, false)) }

                DisposableEffect(Unit) {
                    onServiceStateChanged = {
                        serviceState = Pair(usbService, serviceBound)
                    }
                    // 立即触发一次以获取当前状态
                    serviceState = Pair(usbService, serviceBound)
                    onDispose {
                        onServiceStateChanged = null
                    }
                }

                MainScreen(
                    usbManager = usbManager,
                    permissionManager = permissionManager,
                    usbService = serviceState.first,
                    serviceBound = serviceState.second,
                    onRefreshCallbackReady = { callback -> refreshDevicesCallback = callback }
                )
            }
        }

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d("MainActivity", "USB device attached via intent")
            refreshDevicesCallback?.invoke()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionManager.unregisterReceiver()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // 不停止 Service，让它继续运行
    }
}

fun setLanguage(language: String) {
    val localeList = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbManager: UsbManager,
    permissionManager: UsbPermissionManager,
    usbService: UsbService?,
    serviceBound: Boolean,
    onRefreshCallbackReady: (() -> Unit) -> Unit = {}
) {
    var serverRunning by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("3240") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var boundDevices by remember { mutableStateOf(setOf<String>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun addLog(message: String) {
        logMessages = logMessages + "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}] $message"
    }

    fun refreshDevices() {
        devices = permissionManager.getDeviceList()
        addLog("Found ${devices.size} USB device(s)")
    }

    fun refreshState() {
        usbService?.let { service ->
            serverRunning = service.serverRunning
            boundDevices = service.boundDeviceNames
            portText = service.port.toString()
        }
    }

    // 获取设备IP地址
    fun getDeviceIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // 只返回IPv4地址
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to get IP address", e)
        }
        return null
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // 获取IP地址
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            ipAddress.value = getDeviceIpAddress()
        }
    }

    // 设置native日志回调
    DisposableEffect(Unit) {
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                addLog(message.trim())
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // 不在这里清理，因为native层可能还在使用
        }
    }

    // Service 状态变化时刷新
    LaunchedEffect(serviceBound, usbService) {
        onRefreshCallbackReady { refreshDevices() }
        refreshDevices()
        refreshState()
    }

    // 监听USB设备插入/拔出（通过BroadcastReceiver）
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceAttachedListener {
            refreshDevices()
        }
        permissionManager.setOnDeviceDetachedListener { device ->
            scope.launch {
                val wasBound = usbService?.handleDeviceDetached(device.deviceName) ?: false
                if (wasBound) {
                    boundDevices = usbService?.boundDeviceNames ?: emptySet()
                    val deviceName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    Toast.makeText(context, context.getString(R.string.device_detached, deviceName), Toast.LENGTH_SHORT).show()
                }
            }
        }
        onDispose {
            permissionManager.setOnDeviceAttachedListener(null)
            permissionManager.setOnDeviceDetachedListener(null)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { showAbout = true }) {
                        Text(stringResource(R.string.about))
                    }
                    Box {
                        TextButton(onClick = { showLanguageMenu = true }) {
                            Text(stringResource(R.string.language))
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_en)) },
                                onClick = {
                                    setLanguage("en")
                                    showLanguageMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_zh)) },
                                onClick = {
                                    setLanguage("zh")
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServerControlPanel(
                serverRunning = serverRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                portText = portText,
                onPortChange = { portText = it },
                onStart = {
                    val port = portText.toIntOrNull() ?: 3240
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, "Service not ready", Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    isStarting = true
                    scope.launch {
                        val success = service.startServer(port)
                        isStarting = false
                        if (success) {
                            serverRunning = true
                        }
                    }
                },
                onStop = {
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, "Service not ready", Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    isStopping = true
                    scope.launch {
                        service.stopServer()
                        isStopping = false
                        serverRunning = false
                        boundDevices = emptySet()
                    }
                }
            )

            StatusCard(
                serverRunning = serverRunning,
                boundCount = boundDevices.size,
                ipAddress = ipAddress.value,
                port = portText.toIntOrNull() ?: 3240
            )

            DeviceListSection(
                devices = devices,
                boundDevices = boundDevices,
                serverRunning = serverRunning,
                onBindDevice = { device ->
                    if (!serverRunning) {
                        Toast.makeText(context, context.getString(R.string.please_start_server), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, "Service not ready", Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    val deviceName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    permissionManager.requestPermission(device) { _, granted ->
                        if (granted) {
                            scope.launch {
                                val result = service.bindDevice(usbManager, device)
                                when (result) {
                                    is DeviceBindResult.Success -> {
                                        boundDevices = service.boundDeviceNames
                                        Toast.makeText(context, context.getString(R.string.bind_success, deviceName), Toast.LENGTH_SHORT).show()
                                    }
                                    is DeviceBindResult.Failure -> {
                                        Toast.makeText(context, context.getString(R.string.bind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onUnbindDevice = { device ->
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, "Service not ready", Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    val deviceName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    scope.launch {
                        val result = service.unbindDevice(device.deviceName)
                        when (result) {
                            is DeviceUnbindResult.Success -> {
                                boundDevices = service.boundDeviceNames
                                Toast.makeText(context, context.getString(R.string.unbind_success, deviceName), Toast.LENGTH_SHORT).show()
                            }
                            is DeviceUnbindResult.Failure -> {
                                Toast.makeText(context, context.getString(R.string.unbind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onRefresh = { refreshDevices() }
            )

            LogSection(
                logMessages = logMessages,
                onClear = { logMessages = emptyList() },
                onViewFullLog = { showFullLog = true }
            )
        }
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logMessages,
            onDismiss = { showFullLog = false }
        )
    }

    if (showAbout) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        val githubUrl = "https://github.com/yunsmall/Android-Usbipdcpp"
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_description))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_version, version))
                    Text(stringResource(R.string.about_license))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.about_github), color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
fun ServerControlPanel(
    serverRunning: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    portText: String,
    onPortChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.server_control), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
                    label = { Text(stringResource(R.string.port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(100.dp),
                    enabled = !serverRunning && !isStarting,
                    singleLine = true
                )

                Spacer(modifier = Modifier.weight(1f))

                if (serverRunning || isStopping) {
                    Button(
                        onClick = onStop,
                        enabled = !isStopping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isStopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Text(stringResource(R.string.stopping))
                            } else {
                                Text(stringResource(R.string.stop_server))
                            }
                        }
                    }
                } else {
                    Button(onClick = onStart, enabled = !isStarting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isStarting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Text(stringResource(R.string.starting))
                            } else {
                                Text(stringResource(R.string.start_server))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    serverRunning: Boolean,
    boundCount: Int,
    ipAddress: String?,
    port: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (serverRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (serverRunning) Color.Green else Color.Red,
                        RoundedCornerShape(50)
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = if (serverRunning) stringResource(R.string.server_running) else stringResource(R.string.server_stopped),
                    style = MaterialTheme.typography.titleMedium
                )
                if (serverRunning) {
                    ipAddress?.let {
                        Text(
                            text = stringResource(R.string.address, it, port),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = stringResource(R.string.devices_bound, boundCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ColumnScope.DeviceListSection(
    devices: Map<String, UsbDevice>,
    boundDevices: Set<String>,
    serverRunning: Boolean,
    onBindDevice: (UsbDevice) -> Unit,
    onUnbindDevice: (UsbDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.usb_devices), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices.entries.toList()) { entry ->
                        val device = entry.value
                        val isBound = boundDevices.contains(device.deviceName)

                        DeviceItem(
                            device = device,
                            isBound = isBound,
                            canBind = serverRunning && !isBound,
                            onBind = { onBindDevice(device) },
                            onUnbind = { onUnbindDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: UsbDevice,
    isBound: Boolean,
    canBind: Boolean,
    onBind: () -> Unit,
    onUnbind: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.productName?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_device),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "VID: ${device.vendorId.toString(16).uppercase()}, PID: ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isBound) {
            TextButton(onClick = onUnbind, modifier = Modifier.height(36.dp)) {
                Text(stringResource(R.string.unbind), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Button(onClick = onBind, modifier = Modifier.height(36.dp), enabled = canBind) {
                Text(stringResource(R.string.bind), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ColumnScope.LogSection(
    logMessages: List<String>,
    onClear: () -> Unit,
    onViewFullLog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.log), style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onViewFullLog) {
                        Text(stringResource(R.string.expand))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                if (logMessages.isEmpty()) {
                    Text(
                        stringResource(R.string.no_logs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val scrollState = rememberScrollState()

                    LaunchedEffect(logMessages.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    Text(
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

@Composable
fun FullLogDialog(logMessages: List<String>, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()

    LaunchedEffect(logMessages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_messages)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = logMessages.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
