package com.polar.androidblesdk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.DeviceStreamingFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.*
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import java.time.LocalDateTime
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private var deviceId = "A20ECF14"

    private val api: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(applicationContext, PolarBleApi.FEATURE_HR)
    }
    private lateinit var broadcastDisposable: Disposable
    private var scanDisposable: Disposable? = null
    private var autoConnectDisposable: Disposable? = null
    private var deviceConnected = false
    private var bluetoothEnabled = false

    private lateinit var broadcastButton: Button
    private lateinit var connectButton: Button
    private lateinit var autoConnectButton: Button
    private lateinit var scanButton: Button
    private lateinit var heartRateTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        broadcastButton = findViewById(R.id.broadcast_button)
        connectButton = findViewById(R.id.connect_button)
        autoConnectButton = findViewById(R.id.auto_connect_button)
        scanButton = findViewById(R.id.scan_button)
        heartRateTv = findViewById(R.id.heart_rate_value)


        api.setPolarFilter(false)
        api.setApiLogger { s: String -> Log.d(API_LOGGER_TAG, s) }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                bluetoothEnabled = powered
                if (powered) {
                    enableAllButtons()
                    showToast("Phone Bluetooth on")
                } else {
                    disableAllButtons()
                    showToast("Phone Bluetooth off")
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTED: " + polarDeviceInfo.deviceId)
                deviceId = polarDeviceInfo.deviceId
                deviceConnected = true
                val buttonText = getString(R.string.disconnect_from_device, deviceId)
                toggleButtonDown(connectButton, buttonText)
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTING: " + polarDeviceInfo.deviceId)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: " + polarDeviceInfo.deviceId)
                deviceConnected = false
                val buttonText = getString(R.string.connect_to_device, deviceId)
                toggleButtonUp(connectButton, buttonText)
            }

            override fun streamingFeaturesReady(
                identifier: String, features: Set<DeviceStreamingFeature>
            ) {
                for (feature in features) {
                    Log.d(TAG, "Streaming feature $feature is ready")
                }
            }

            override fun hrFeatureReady(identifier: String) {
                Log.d(TAG, "HR READY: $identifier")
                // hr notifications are about to start
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                Log.d(TAG, "uuid: $uuid value: $value")
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "BATTERY LEVEL: $level")
            }

            override fun hrNotificationReceived(identifier: String, data: PolarHrData) {
                Log.d(
                    TAG,
                    "HR value: ${data.hr} rrsMs: ${data.rrsMs} rr: ${data.rrs} contact: ${data.contactStatus} , ${data.contactStatusSupported}"
                )
            }

        })

        broadcastButton.setOnClickListener {
            if (!this::broadcastDisposable.isInitialized || broadcastDisposable.isDisposed) {
                toggleButtonDown(broadcastButton, R.string.listening_broadcast)
                broadcastDisposable = api.startListenForPolarHrBroadcasts(null)
                    .subscribe(
                        { polarBroadcastData: PolarHrBroadcastData ->
                            heartRateTv.text = polarBroadcastData.hr.toString()

                            postResult(
                                polarBroadcastData.hr,
                                polarBroadcastData.batteryStatus
                            )

                            Log.d(
                                TAG,
                                "HR BROADCAST ${polarBroadcastData.polarDeviceInfo.deviceId} HR: ${polarBroadcastData.hr} batt: ${polarBroadcastData.batteryStatus}"
                            )
                        },
                        { error: Throwable ->
                            toggleButtonUp(broadcastButton, R.string.listen_broadcast)
                            Log.e(TAG, "Broadcast listener failed. Reason $error")
                        },
                        { Log.d(TAG, "complete") }
                    )
            } else {
                toggleButtonUp(broadcastButton, R.string.listen_broadcast)
                broadcastDisposable.dispose()
            }
        }

        connectButton.text = getString(R.string.connect_to_device, deviceId)
        connectButton.setOnClickListener {
            try {
                if (deviceConnected) {
                    api.disconnectFromDevice(deviceId)
                } else {
                    api.connectToDevice(deviceId)
                }
            } catch (polarInvalidArgument: PolarInvalidArgument) {
                val attempt = if (deviceConnected) {
                    "disconnect"
                } else {
                    "connect"
                }
                Log.e(TAG, "Failed to $attempt. Reason $polarInvalidArgument ")
            }
        }

        autoConnectButton.setOnClickListener {
            if (autoConnectDisposable != null) {
                autoConnectDisposable?.dispose()
            }
            autoConnectDisposable = api.autoConnectToDevice(-50, "180D", null)
                .subscribe(
                    { Log.d(TAG, "auto connect search complete") },
                    { throwable: Throwable -> Log.e(TAG, "" + throwable.toString()) }
                )
        }

        scanButton.setOnClickListener {
            val isDisposed = scanDisposable?.isDisposed ?: true
            if (isDisposed) {
                toggleButtonDown(scanButton, R.string.scanning_devices)
                scanDisposable = api.searchForDevice()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { polarDeviceInfo: PolarDeviceInfo ->
                            Log.d(
                                TAG,
                                "polar device found id: " + polarDeviceInfo.deviceId + " address: " + polarDeviceInfo.address + " rssi: " + polarDeviceInfo.rssi + " name: " + polarDeviceInfo.name + " isConnectable: " + polarDeviceInfo.isConnectable
                            )
                        },
                        { error: Throwable ->
                            toggleButtonUp(scanButton, "Scan devices")
                            Log.e(TAG, "Device scan failed. Reason $error")
                        },
                        { Log.d(TAG, "complete") }
                    )
            } else {
                toggleButtonUp(scanButton, "Scan devices")
                scanDisposable?.dispose()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ), PERMISSION_REQUEST_CODE
                )
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    disableAllButtons()
                    Log.w(TAG, "No sufficient permissions")
                    showToast("No sufficient permissions")
                    return
                }
            }
            Log.d(TAG, "Needed permissions are granted")
            enableAllButtons()
        }
    }

    public override fun onPause() {
        super.onPause()
        api.backgroundEntered()
    }

    public override fun onResume() {
        super.onResume()
        api.foregroundEntered()
    }

    public override fun onDestroy() {
        super.onDestroy()
        api.shutDown()
    }

    private fun postResult(heartRate: Int, batteryStatus: Boolean) {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://rdp.uustal.ee:8080")
            .build()

        // Create Service
        val service = retrofit.create(APIService::class.java)

        // Create JSON using JSONObject
        val jsonObject = JSONObject()
        jsonObject.put("heartRate", heartRate)
        jsonObject.put("timestamp", LocalDateTime.now())
        jsonObject.put("battery", batteryStatus)

        // Convert JSONObject to String
        val jsonObjectString = jsonObject.toString()

        val requestBody = jsonObjectString.toRequestBody("application/json".toMediaTypeOrNull())

        CoroutineScope(Dispatchers.IO).launch {
            val response = service.postHeartRate(requestBody)

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    val prettyJson = gson.toJson(
                        JsonParser.parseString(
                            response.body()
                                ?.string() // About this thread blocking annotation : https://github.com/square/retrofit/issues/3255
                        )
                    )
                    Log.d("Response: ", prettyJson)

                } else {
                    Log.e("RETROFIT_ERROR", response.code().toString())

                }
            }
        }
    }


    private fun toggleButtonDown(button: Button, text: String? = null) {
        toggleButton(button, true, text)
    }

    private fun toggleButtonDown(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, true, getString(resourceId))
    }

    private fun toggleButtonUp(button: Button, text: String? = null) {
        toggleButton(button, false, text)
    }

    private fun toggleButtonUp(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, false, getString(resourceId))
    }

    private fun toggleButton(button: Button, isDown: Boolean, text: String? = null) {
        if (text != null) button.text = text

        var buttonDrawable = button.background
        buttonDrawable = DrawableCompat.wrap(buttonDrawable!!)
        if (isDown) {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryDarkColor))
        } else {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryColor))
        }
        button.background = buttonDrawable
    }

    private fun showToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.show()
    }

    private fun disableAllButtons() {
        broadcastButton.isEnabled = false
        connectButton.isEnabled = false
        autoConnectButton.isEnabled = false
        scanButton.isEnabled = false
    }

    private fun enableAllButtons() {
        broadcastButton.isEnabled = true
        connectButton.isEnabled = true
        autoConnectButton.isEnabled = true
        scanButton.isEnabled = true
    }

}