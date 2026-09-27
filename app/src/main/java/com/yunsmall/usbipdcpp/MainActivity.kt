package com.yunsmall.usbipdcpp

import android.Manifest
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// 文件顶层常量：MainScreen 是顶层函数而非 MainActivity 方法，
// 常量放 companion（private）会访问不到
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    // Compose 观察的 Service 引用：ServiceConnection 回调都在主线程，直接写
    // State 让组合自动重组，不必再手工挂"状态变了"的回调并记住到处置空
    private val usbServiceState = mutableStateOf<UsbService?>(null)
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UsbService.UsbBinder
            usbServiceState.value = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbServiceState.value = null
            serviceBound = false
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
                MainScreen(
                    usbManager = usbManager,
                    permissionManager = permissionManager,
                    usbService = usbServiceState.value
                )
            }
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

fun isCameraDevice(device: UsbDevice): Boolean {
    for (i in 0 until device.interfaceCount) {
        if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
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
    usbService: UsbService?
) {
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("3240") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var busyDevices by remember { mutableStateOf(setOf<String>()) }
    // 存设备名而非 UsbDevice 对象（非 Parcelable 无法存 SavedState）：
    // 权限对话框期间系统回收 Activity（如"不保留活动"）后重建时，
    // remember 状态会丢，rememberSaveable 保证授权回调仍能找到待绑定设备
    var pendingBindDeviceName by rememberSaveable { mutableStateOf<String?>(null) }

    // Service 的状态是唯一真相源，UI 只观察：不再自己存一份 serverRunning/
    // boundDevices 并在每个变更点手动刷新。Service 未绑定时用空状态占位
    val emptyServiceState = remember { MutableStateFlow(UsbService.UiState()) }
    val serviceState by (usbService?.state ?: emptyServiceState).collectAsState()
    val serverRunning = serviceState.serverRunning
    val boundDevices = serviceState.boundDevices.keys

    // 启动成功后回填实际使用的端口
    LaunchedEffect(serviceState.port) {
        portText = serviceState.port.toString()
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // performBind 会被 rememberLauncherForActivityResult 的回调长期持有（首次组合
    // 的实例），必须经 rememberUpdatedState 读最新 usbService，否则授权后拿到
    // 的是服务绑定前的 null 快照，绑定必然失败
    val currentUsbService by rememberUpdatedState(usbService)

    // 执行设备绑定（USB 权限 + native 绑定）
    fun performBind(device: UsbDevice) {
        val service = currentUsbService ?: run {
            Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        // native 初始化失败时绑定无意义，明确提示而非等 native 返回模糊错误
        if (!serviceState.nativeReady) {
            Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val deviceName = device.productName?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.unknown_device)
        val accepted = permissionManager.requestPermission(device) { _, granted ->
            if (granted) {
                scope.launch {
                    busyDevices = busyDevices + device.deviceName
                    try {
                        val result = service.bindDevice(usbManager, device)
                        // 绑定状态由 Service 的 StateFlow 自动传播到 UI，不用手动同步
                        when (result) {
                            is DeviceBindResult.Success -> {
                                Toast.makeText(context, context.getString(R.string.bind_success, deviceName), Toast.LENGTH_SHORT).show()
                            }
                            is DeviceBindResult.Failure -> {
                                Toast.makeText(context, context.getString(R.string.bind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } finally {
                        busyDevices = busyDevices - device.deviceName
                    }
                }
            } else {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
            }
        }
        if (!accepted) {
            // 请求未受理：同设备已有待处理的权限请求，明确提示避免误以为无反应
            Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
        }
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName = pendingBindDeviceName ?: return@rememberLauncherForActivityResult
        pendingBindDeviceName = null
        // Activity 重建后从设备列表重新查找设备对象
        val device = usbManager.deviceList[deviceName] ?: return@rememberLauncherForActivityResult
        if (granted) {
            performBind(device)
        } else {
            Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    fun addLog(message: String) {
        logMessages = logMessages + "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}] $message"
    }

    fun refreshDevices() {
        devices = permissionManager.getDeviceList()
        addLog("Found ${devices.size} USB device(s)")
    }

    // 获取设备IP地址
    fun getDeviceIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                // 跳过 VPN/虚拟网卡（tun/ppp 前缀），避免返回虚拟接口地址
                val ifName = networkInterface.name?.lowercase(Locale.getDefault()) ?: ""
                if (ifName.startsWith("tun") || ifName.startsWith("ppp") || ifName.startsWith("vpn")) continue

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
            Log.e(TAG, "Failed to get IP address", e)
        }
        return null
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // 获取IP地址
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            // 网络接口枚举可能耗时（多虚拟网卡时），放 IO 线程避免卡主线程
            ipAddress.value = withContext(Dispatchers.IO) { getDeviceIpAddress() }
        }
    }

    // 设置native日志回调
    DisposableEffect(Unit) {
        // onLog 由 native 日志线程回调，直接更新 Compose 状态会跨线程写，
        // 切到主线程再执行
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                mainHandler.post { addLog(message.trim()) }
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // 必须清回调：旋转重建时新 setLogCallback 会替换旧引用（无需清理），
            // 但应用退后台（Activity 销毁、不再有新回调）时不清的话，JNI 的
            // 全局引用会一直持有旧 Activity，导致泄漏
            UsbIpNative.setLogCallback(null)
        }
    }

    // 首次组合刷新一次设备列表；之后由插拔广播与手动刷新按钮驱动
    LaunchedEffect(Unit) { refreshDevices() }

    // 监听USB设备插入/拔出（通过BroadcastReceiver）
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceAttachedListener {
            refreshDevices()
        }
        onDispose {
            permissionManager.setOnDeviceAttachedListener(null)
        }
    }

    // 拔出提示：设备清理由 Service 统一处理（它的接收器在 Activity 销毁后仍有效），
    // UI 只消费事件弹提示，绑定状态经 serviceState 自动更新
    LaunchedEffect(usbService) {
        usbService?.events?.collect { event ->
            when (event) {
                is UsbService.Event.DeviceDetached -> {
                    if (event.wasBound) {
                        val deviceName = event.device.productName?.takeIf { it.isNotEmpty() }
                            ?: context.getString(R.string.unknown_device)
                        Toast.makeText(context, context.getString(R.string.device_detached, deviceName), Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    // native 初始化失败时无法启动服务器，明确提示
                    if (!serviceState.nativeReady) {
                        Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    isStarting = true
                    scope.launch {
                        // 启动结果由 Service 的 StateFlow 反映，成功后 UI 自动切到运行态
                        service.startServer(port)
                        isStarting = false
                    }
                },
                onStop = {
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    isStopping = true
                    scope.launch {
                        // 停止后 serverRunning/绑定列表由 Service 的 StateFlow 清空
                        service.stopServer()
                        isStopping = false
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
                busyDevices = busyDevices,
                serverRunning = serverRunning,
                getBusid = { serviceState.boundDevices[it] },
                onBindDevice = { device ->
                    if (!serverRunning) {
                        Toast.makeText(context, context.getString(R.string.please_start_server), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    if (usbService == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // 摄像头设备需要先获取 CAMERA 权限
                    if (isCameraDevice(device) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (pendingBindDeviceName != null) {
                            // 已有待处理请求（权限对话框未完成），忽略新的，
                            // 防止 pendingBindDeviceName 被覆盖导致授权后绑错设备
                            return@DeviceListSection
                        }
                        pendingBindDeviceName = device.deviceName
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        performBind(device)
                    }
                },
                onUnbindDevice = { device ->
                    val service = usbService
                    if (service == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    val deviceName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    scope.launch {
                        busyDevices = busyDevices + device.deviceName
                        try {
                            val result = service.unbindDevice(device.deviceName)
                            // 解绑状态由 Service 的 StateFlow 自动传播到 UI
                            when (result) {
                                is DeviceUnbindResult.Success -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_success, deviceName), Toast.LENGTH_SHORT).show()
                                }
                                is DeviceUnbindResult.Failure -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            busyDevices = busyDevices - device.deviceName
                        }
                    }
                },
                onRefresh = { refreshDevices() }
            )

            LogSection(
                logMessages = logMessages,
                onClear = { logMessages = emptyList() },
                onViewFullLog = { showFullLog = true },
                onCopyLog = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log", logMessages.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                }
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
        // 当前包名查不到自己的信息理论上不可能，但规范上还是防御一下
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info", e)
            "unknown"
        }
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
    var showCopyMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = serverRunning) { showCopyMenu = true },
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

        DropdownMenu(
            expanded = showCopyMenu,
            onDismissRequest = { showCopyMenu = false }
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ipAddress?.let { ip ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_ip, ip)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip))
                        showCopyMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_port, port)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Port", port.toString()))
                        showCopyMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_address, ip, port)) },
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Address", "$ip:$port"))
                        showCopyMenu = false
                    }
                )
            }
        }
    }
}

@Composable
fun ColumnScope.DeviceListSection(
    devices: Map<String, UsbDevice>,
    boundDevices: Set<String>,
    busyDevices: Set<String>,
    serverRunning: Boolean,
    getBusid: (String) -> String?,
    onBindDevice: (UsbDevice) -> Unit,
    onUnbindDevice: (UsbDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f, fill = false)
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
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices.entries.toList()) { entry ->
                        val device = entry.value
                        val isBound = boundDevices.contains(device.deviceName)

                        val busid = getBusid(device.deviceName)
                        DeviceItem(
                            device = device,
                            isBound = isBound,
                            isBusy = busyDevices.contains(device.deviceName),
                            busid = busid,
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
    isBusy: Boolean,
    busid: String?,
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
            if (busid != null) {
                Text(
                    text = "BUSID: $busid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else if (isBound) {
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
    onViewFullLog: () -> Unit,
    onCopyLog: () -> Unit
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
                    TextButton(onClick = onCopyLog) {
                        Text(stringResource(R.string.copy))
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
            SelectionContainer {
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
