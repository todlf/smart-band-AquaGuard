package com.example.smart_band_kids

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContentProviderCompat.requireContext
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2
private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 3
private const val SERVICE_UUID = "25AE1441-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_READ_UUID = "25AE1442-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_WRITE_UUID = "25AE1443-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_INDICATE_UUID = "25AE1444-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

private var sensorValues: Map<String, Float?> = mutableMapOf()

class MainActivity : AppCompatActivity() {
    enum class BLELifecycleState {
        Disconnected,
        Scanning,
        Connecting,
        ConnectedDiscovering,
        ConnectedSubscribing,
        Connected
    }

    private var lifecycleState = BLELifecycleState.Disconnected
        set(value) {
            field = value
            appendLog("status = $value")
            runOnUiThread {
                textViewLifecycleState.text = "State: ${value.name}"

                if (value != BLELifecycleState.Connected) {
                    //textViewSubscription.text = getString(R.string.text_not_subscribed)
                }
            }
        }

    private val switchConnect: SwitchMaterial
        get() = findViewById<SwitchMaterial>(R.id.switchConnect)
    private val textViewLifecycleState: TextView
        get() = findViewById<TextView>(R.id.textViewLifecycleState)
/*    private val textViewReadValue: TextView
        get() = findViewById<TextView>(R.id.textViewReadValue)*/
    private val textViewIndicateValue: TextView
        get() = findViewById<TextView>(R.id.textViewIndicateValue)
/*    private val textViewLog: TextView
        get() = findViewById<TextView>(R.id.textViewLog)
    private val scrollViewLog: ScrollView
        get() = findViewById<ScrollView>(R.id.scrollViewLog)*/
    private val textViewWaterSensor: TextView
        get() = findViewById<TextView>(R.id.textViewWaterSensor)
    private val textViewAccelSensor: TextView
        get() = findViewById<TextView>(R.id.textViewAccelSensor)
    private val swimmingImage: ImageView
        get() = findViewById<ImageView>(R.id.swimmingImage)
    private val fallingImage: ImageView
        get() = findViewById<ImageView>(R.id.fallingImage)
    private val swimChronometer: Chronometer
        get() = findViewById<Chronometer>(R.id.swimChronometer)
    private val resetButton: Button
        get() = findViewById<Button>(R.id.resetButton)
    private val swimModeSwitch: SwitchMaterial
        get() = findViewById<SwitchMaterial>(R.id.swimModeSwitch)
    private val timeRadioGroup: RadioGroup
        get() = findViewById<RadioGroup>(R.id.timeRadioGroup)
    private val timeRadioGroupTwo: RadioGroup
        get() = findViewById<RadioGroup>(R.id.timeRadioGroupTwo)
    private val swimFrame: FrameLayout
        get() = findViewById<FrameLayout>(R.id.swimFrame)
    private val tumbleFrame: FrameLayout
        get() = findViewById<FrameLayout>(R.id.tumbleFrame)

    private val userWantsToScanAndConnect: Boolean get() = switchConnect.isChecked
    private var isScanning = false
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForRead: BluetoothGattCharacteristic? = null
    private var characteristicForWrite: BluetoothGattCharacteristic? = null
    private var characteristicForIndicate: BluetoothGattCharacteristic? = null

    private var isSwimming = false
    private var lastStartTime = 0L
    private var elapsedTime = 0L
    private var selectedTime = 10 // 기본값 15분
    private var swimMode = false
    private var swimStartTime = 0L

    private var vibrationJob: Job? = null

    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        // Vibrator 서비스 초기화
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        timeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                // 두 번째 RadioGroup의 선택 해제
                timeRadioGroupTwo.clearCheck()

                selectedTime = when (checkedId) {
                    R.id.time10m -> 10
                    R.id.time20m -> 20
                    R.id.time30m -> 30
                    else -> 10
                }
                Toast.makeText(this, "${selectedTime}분으로 설정되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 두 번째 RadioGroup 리스너 설정
        timeRadioGroupTwo.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                // 첫 번째 RadioGroup의 선택 해제
                timeRadioGroup.clearCheck()

                selectedTime = when (checkedId) {
                    R.id.time40m -> 40
                    R.id.time50m -> 50
                    R.id.time1h -> 60
                    else -> 10
                }
                Toast.makeText(this, "${selectedTime}분으로 설정되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }


        initializeSensorViews()

        swimModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            swimMode = isChecked
            if (isChecked) {
                timeRadioGroup.visibility = VISIBLE
                timeRadioGroupTwo.visibility = VISIBLE
                Toast.makeText(this, "수영 모드가 활성화되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                timeRadioGroup.visibility = GONE
                timeRadioGroupTwo.visibility = GONE
                Toast.makeText(this, "수영 모드가 비활성화되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }


        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            when (isChecked) {
                true -> {
                    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                    registerReceiver(bleOnOffListener, filter)
                }

                false -> {
                    unregisterReceiver(bleOnOffListener)
                }
            }
            bleRestartLifecycle()
        }

        // Chronometer 리스너 설정


        swimChronometer.setOnChronometerTickListener { chronometer ->
            val elapsedMillis = SystemClock.elapsedRealtime() - chronometer.base
            val elapsedMinutes = elapsedMillis / 1000  // Convert to minutes

            if (elapsedMinutes >= selectedTime && isSwimming && swimMode) {
                // Cancel any existing vibration job
                vibrationJob?.cancel()

                // Start new vibration and toast for 3 seconds
                vibrationJob = CoroutineScope(Dispatchers.Main).launch {
                    // Start vibration
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(1000)
                    }

                    // Show toast
                    Toast.makeText(
                        this@MainActivity,
                        "설정된 수영 시간 ${selectedTime}분이 지났습니다!",
                        Toast.LENGTH_LONG
                    ).show()

                    showNotification(
                        "수영 시간 초과",
                        "설정된 수영 시간 ${selectedTime}분이 지났습니다!"
                    )

                    // Wait for 3 seconds
                    delay(1000)

                    // Stop vibration
                    vibrator.cancel()
                }
            }
        }

        appendLog("MainActivity.onCreate")
    }

    override fun onDestroy() {
        bleEndLifecycle()
        super.onDestroy()
    }

/*    fun onTapRead(view: View) {
        var gatt = connectedGatt ?: run {
            appendLog("ERROR: read failed, no connected device")
            return
        }
        var characteristic = characteristicForRead ?: run {
            appendLog("ERROR: read failed, characteristic unavailable $CHAR_FOR_READ_UUID")
            return
        }
        if (!characteristic.isReadable()) {
            appendLog("ERROR: read failed, characteristic not readable $CHAR_FOR_READ_UUID")
            return
        }
        gatt.readCharacteristic(characteristic)
    }*/

    fun onTapWrite(view: View) {
        var gatt = connectedGatt ?: run {
            appendLog("ERROR: write failed, no connected device")
            return
        }
        var characteristic = characteristicForWrite ?: run {
            appendLog("ERROR: write failed, characteristic unavailable $CHAR_FOR_WRITE_UUID")
            return
        }
        if (!characteristic.isWriteable()) {
            appendLog("ERROR: write failed, characteristic not writeable $CHAR_FOR_WRITE_UUID")
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val writeValue = "VIBRATE"
        characteristic.value = writeValue.toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(characteristic)
        appendLog("Sent command: $writeValue")
    }

    // Notification Channel 설정
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "수영 알림"
            val descriptionText = "수영 및 안전 관련 알림"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("SWIM_CHANNEL", name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Notification 생성 함수
    private fun showNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, "SWIM_CHANNEL")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // notification 아이콘 필요
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(System.currentTimeMillis().toInt(), builder.build())
            }
        }
    }

    private fun moveToNavigationActivity() {
        val intent = Intent(this, NavigationActivity::class.java).apply {
            putExtra("water", sensorValues["water"])
            putExtra("accelX", sensorValues["accelX"])
            putExtra("accelY", sensorValues["accelY"])
            putExtra("accelZ", sensorValues["accelZ"])
        }
        startActivity(intent)
        finish()
    }

    private fun appendLog(message: String) {
        Log.d("appendLog", message)
        runOnUiThread {
            val strTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            //textViewLog.text = textViewLog.text.toString() + "\n$strTime $message"

            // scroll after delay, because textView has to be updated first
            Handler().postDelayed({
                //scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 16)
        }
    }

    private fun bleEndLifecycle() {
        safeStopBleScan()
        connectedGatt?.close()
        setConnectedGattToNull()
        lifecycleState = BLELifecycleState.Disconnected
    }

    private fun setConnectedGattToNull() {
        connectedGatt = null
        characteristicForRead = null
        characteristicForWrite = null
        characteristicForIndicate = null
    }

    private fun bleRestartLifecycle() {
        runOnUiThread {
            if (userWantsToScanAndConnect) {
                if (connectedGatt == null) {
                    prepareAndStartBleScan()
                } else {
                    connectedGatt?.disconnect()
                }
            } else {
                bleEndLifecycle()
            }
        }
    }

    private fun prepareAndStartBleScan() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            appendLog(message)
            if (isSuccess) {
                safeStartBleScan()
            }
        }
    }

    private fun safeStartBleScan() {
        if (isScanning) {
            appendLog("Already scanning")
            return
        }

        val serviceFilter = scanFilter.serviceUuid?.uuid.toString()
        appendLog("Starting BLE scan, filter: $serviceFilter")

        isScanning = true
        lifecycleState = BLELifecycleState.Scanning
        bleScanner.startScan(mutableListOf(scanFilter), scanSettings, scanCallback)
    }

    private fun safeStopBleScan() {
        if (!isScanning) {
            appendLog("Already stopped")
            return
        }

        appendLog("Stopping BLE scan")
        isScanning = false
        bleScanner.stopScan(scanCallback)
    }

    private fun subscribeToIndications(
        characteristic: BluetoothGattCharacteristic,
        gatt: BluetoothGatt
    ) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }

    private fun unsubscribeFromCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = connectedGatt ?: return

        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, false)) {
                appendLog("ERROR: setNotification(false) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    //region BLE Scanning
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
        .build()

    private val scanSettings: ScanSettings
        get() {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                scanSettingsSinceM
            } else {
                scanSettingsBeforeM
            }
        }

    private val scanSettingsBeforeM = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .setReportDelay(0)
        .build()

    @RequiresApi(Build.VERSION_CODES.M)
    private val scanSettingsSinceM = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        .setReportDelay(0)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name: String? = result.scanRecord?.deviceName ?: result.device.name
            appendLog("onScanResult name=$name address= ${result.device?.address}")
            safeStopBleScan()
            lifecycleState = BLELifecycleState.Connecting
            result.device.connectGatt(this@MainActivity, false, gattCallback)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            appendLog("onBatchScanResults, ignoring")
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("onScanFailed errorCode=$errorCode")
            safeStopBleScan()
            lifecycleState = BLELifecycleState.Disconnected
            bleRestartLifecycle()
        }
    }
    //endregion

    //region BLE events, when connected
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // TODO: timeout timer: if this callback not called - disconnect(), wait 120ms, close()

            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    appendLog("Connected to $deviceAddress")

                    // TODO: bonding state

                    // recommended on UI thread https://punchthrough.com/android-ble-guide/
                    Handler(Looper.getMainLooper()).post {
                        lifecycleState = BLELifecycleState.ConnectedDiscovering
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLog("Disconnected from $deviceAddress")
                    setConnectedGattToNull()
                    gatt.close()
                    lifecycleState = BLELifecycleState.Disconnected
                    bleRestartLifecycle()
                }
            } else {
                // TODO: random error 133 - close() and try reconnect

                appendLog("ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting")

                setConnectedGattToNull()
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                bleRestartLifecycle()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("onServicesDiscovered status: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logAllServicesAndCharacteristics(gatt)

                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                if (service == null) {
                    appendLog("ERROR: Service not found: $SERVICE_UUID")
                    return
                }

                connectedGatt = gatt
                characteristicForWrite =
                    service.getCharacteristic(UUID.fromString(CHAR_FOR_WRITE_UUID))
                characteristicForIndicate =
                    service.getCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID))
                if (characteristicForIndicate == null) {
                    appendLog("ERROR: Indicate characteristic not found: $CHAR_FOR_INDICATE_UUID")
                } else {
                    appendLog("Indicate characteristic found")
                    enableNotifications(characteristicForIndicate!!, gatt)
                }
            } else {
                appendLog("Service discovery failed")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                val data = characteristic.value
                val dataString = String(data, Charset.forName("UTF-8"))

                appendLog("Received data: $dataString")

                // 데이터 파싱
                sensorValues = parseSensorData(dataString)

                runOnUiThread {
                    textViewWaterSensor.text = "Water Level: ${sensorValues["water"] ?: "N/A"}"
                    textViewAccelSensor.text = "Accel X: ${sensorValues["accelX"] ?: "N/A"}, " +
                            "Y: ${sensorValues["accelY"] ?: "N/A"}, " +
                            "Z: ${sensorValues["accelZ"] ?: "N/A"}"

                    // 수위 센서 값에 따른 처리
                    val waterValue = sensorValues["water"]
                    val fallValue = sensorValues["accelX"]

                    if (waterValue != null) {
                        if (waterValue == 1f) {
                            if (swimMode) {
                                // 수영 모드가 켜져있을 때만 크로노미터 시작
                                if (!isSwimming) {
                                    Toast.makeText(this@MainActivity, "수영을 시작했습니다.", Toast.LENGTH_SHORT).show()
                                    showNotification("수영 시작", "수영을 시작했습니다.")
                                    swimmingImage.visibility = View.VISIBLE
                                    swimFrame.setBackgroundColor(getResources().getColor(R.color.swim_color))
                                    lastStartTime = SystemClock.elapsedRealtime()
                                    swimChronometer.base = SystemClock.elapsedRealtime() - elapsedTime
                                    swimChronometer.start()
                                    isSwimming = true
                                }
                            } else {
                                // 수영 모드가 꺼져있을 때는 진동과 토스트 메시지만
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(1000)
                                }
                                Toast.makeText(this@MainActivity, "물이 감지되었습니다!", Toast.LENGTH_SHORT).show()
                                showNotification("물 감지", "물이 감지되었습니다!")
                            }
                        } else if (waterValue != 1f && isSwimming) {
                            // 수영 종료
                            Toast.makeText(this@MainActivity, "수영을 종료했습니다.", Toast.LENGTH_SHORT).show()
                            showNotification("수영 종료", "수영을 종료했습니다.")
                            swimmingImage.visibility = View.GONE
                            swimFrame.setBackgroundColor(getResources().getColor(R.color.white))
                            swimChronometer.stop()
                            elapsedTime += SystemClock.elapsedRealtime() - lastStartTime
                            isSwimming = false
                        }
                    }

                    // 낙상 상태 처리 (수영 중이 아닐 때만)
                    if (!isSwimming && fallValue != null) {
                        if (fallValue == 1f) {
                            fallingImage.visibility = View.GONE
                            tumbleFrame.setBackgroundColor(getResources().getColor(R.color.white))
                        } else {
                            Toast.makeText(this@MainActivity, "낙상 감지되었습니다.", Toast.LENGTH_SHORT).show()
                            showNotification("낙상 감지", "낙상이 감지되었습니다!")
                            fallingImage.visibility = View.VISIBLE
                            tumbleFrame.setBackgroundColor(getResources().getColor(R.color.tumble_color))

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(1000)
                            }
                        }
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_WRITE_UUID)) {
                val log: String = "onCharacteristicWrite " + when (status) {
                    BluetoothGatt.GATT_SUCCESS -> "OK"
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "not allowed"
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "invalid length"
                    else -> "error $status"
                }
                appendLog(log)
            } else {
                appendLog("onCharacteristicWrite unknown uuid $characteristic.uuid")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val value = descriptor.value
                    val isSubscribed = value.isNotEmpty() && value[0].toInt() != 0
                    val subscriptionText = when (isSubscribed) {
                        true -> getString(R.string.text_subscribed)
                        false -> getString(R.string.text_not_subscribed)
                    }
                    appendLog("onDescriptorWrite $subscriptionText")
                    runOnUiThread {
                        //textViewSubscription.text = subscriptionText
                    }
                } else {
                    appendLog("ERROR: onDescriptorWrite status=$status uuid=${descriptor.uuid} char=${descriptor.characteristic.uuid}")
                }

                // subscription processed, consider connection is ready for use
                lifecycleState = BLELifecycleState.Connected
            } else {
                appendLog("onDescriptorWrite unknown uuid $descriptor.characteristic.uuid")
            }
        }
    }
    //endregion

    //region BluetoothGattCharacteristic extension
    fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWriteable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    fun BluetoothGattCharacteristic.isWriteableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return (properties and property) != 0
    }
    //endregion

    //region Permissions and Settings management
    enum class AskType {
        AskOnce,
        InsistUntilSuccess
    }

    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private var permissionResultHandlers =
        mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()
    private var bleOnOffListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                BluetoothAdapter.STATE_ON -> {
                    appendLog("onReceive: Bluetooth ON")
                    if (lifecycleState == BLELifecycleState.Disconnected) {
                        bleRestartLifecycle()
                    }
                }

                BluetoothAdapter.STATE_OFF -> {
                    appendLog("onReceive: Bluetooth OFF")
                    bleEndLifecycle()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler ->
            handler(resultCode)
        } ?: runOnUiThread {
            appendLog("ERROR: onActivityResult requestCode=$requestCode result=$resultCode not handled")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler ->
            handler(permissions, grantResults)
        } ?: runOnUiThread {
            appendLog("ERROR: onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    private fun parseSensorData(dataString: String): Map<String, Float?> {
        val result = mutableMapOf<String, Float?>()
        val pairs = dataString.split(",")
        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                val (key, value) = parts
                try {
                    when (key) {
                        "Water" -> result["water"] = value.toFloat()
                        "AccelX" -> result["accelX"] = value.toFloat()
                        "AccelY" -> result["accelY"] = value.toFloat()
                        "AccelZ" -> result["accelZ"] = value.toFloat()
                    }
                } catch (e: NumberFormatException) {
                    appendLog("Error parsing value for $key: $value")
                }
            }
        }
        return result
    }

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        grantBluetoothCentralPermissions(AskType.AskOnce) { isGranted ->
            if (!isGranted) {
                completion(false, "Bluetooth permissions denied")
                return@grantBluetoothCentralPermissions
            }

            enableBluetooth(AskType.AskOnce) { isEnabled ->
                if (!isEnabled) {
                    completion(false, "Bluetooth OFF")
                    return@enableBluetooth
                }

                grantLocationPermissionIfRequired(AskType.AskOnce) { isGranted ->
                    if (!isGranted) {
                        completion(false, "Location permission denied")
                        return@grantLocationPermissionIfRequired
                    }

                    completion(true, "Bluetooth ON, permissions OK, ready")
                }
            }
        }
    }

    private fun initializeSensorViews() {
        textViewWaterSensor.text = "Water Sensor: Waiting for data..."
        textViewAccelSensor.text = "Accelerometer: Waiting for data..."
    }

    private fun enableNotifications(
        characteristic: BluetoothGattCharacteristic,
        gatt: BluetoothGatt
    ) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!gatt.writeDescriptor(cccDescriptor)) {
                appendLog("ERROR: writeDescriptor failed for ${characteristic.uuid}")
            } else {
                appendLog("Indications enabled for ${characteristic.uuid}")
            }
        } ?: appendLog("ERROR: CCCD descriptor not found for ${characteristic.uuid}")
    }

    private fun enableBluetooth(askType: AskType, completion: (Boolean) -> Unit) {
        if (bluetoothAdapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE

            // set activity result handler
            activityResultHandlers[requestCode] = { result ->
                Unit
                val isSuccess = result == Activity.RESULT_OK
                if (isSuccess || askType != AskType.InsistUntilSuccess) {
                    activityResultHandlers.remove(requestCode)
                    completion(isSuccess)
                } else {
                    // start activity for the request again
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }

            // start activity for the request
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantLocationPermissionIfRequired(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // BLUETOOTH_SCAN permission has flag "neverForLocation", so location not needed
            completion(true)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = LOCATION_PERMISSION_REQUEST_CODE

                // prepare motivation message
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Location permission required")
                builder.setMessage("BLE advertising requires location access, starting from Android 6.0")
                builder.setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissionArray(wantedPermissions, requestCode)
                }
                builder.setCancelable(false)

                // set permission result handler
                permissionResultHandlers[requestCode] = { permissions, grantResults ->
                    val isSuccess = grantResults.firstOrNull() != PackageManager.PERMISSION_DENIED
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // show motivation message again
                        builder.create().show()
                    }
                }

                // show motivation message
                builder.create().show()
            }
        }
    }

    private fun logAllServicesAndCharacteristics(gatt: BluetoothGatt) {
        appendLog("Discovered Services:")
        gatt.services.forEach { service ->
            appendLog("Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                appendLog("  Characteristic: ${characteristic.uuid}")
            }
        }
    }

    private fun grantBluetoothCentralPermissions(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            emptyArray()
        }

        if (wantedPermissions.isEmpty() || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE

                // set permission result handler
                permissionResultHandlers[requestCode] = { _ /*permissions*/, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // request again
                        requestPermissionArray(wantedPermissions, requestCode)
                    }
                }

                requestPermissionArray(wantedPermissions, requestCode)
            }
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermissionArray(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }
    //endregion
}