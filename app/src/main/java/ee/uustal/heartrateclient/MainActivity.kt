package ee.uustal.heartrateclient

import android.Manifest
import android.content.Intent
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
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.*
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private var deviceId = "A20ECF14"

    private lateinit var listenerDisposable: Disposable
    private var scanDisposable: Disposable? = null
    private var deviceConnected = false
    private var bluetoothEnabled = false

    private lateinit var listenerButton: Button
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var heartRateValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listenerButton = findViewById(R.id.listener_button)
        connectButton = findViewById(R.id.connect_button)
        scanButton = findViewById(R.id.scan_button)
        heartRateValue = findViewById(R.id.heart_rate_value)

        polarBleApi().setPolarFilter(true)
        polarBleApi().setApiLogger { s: String -> Log.d(API_LOGGER_TAG, s) }
        polarBleApi().setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                bluetoothEnabled = powered
                if (powered) {
                    enableAllButtons()
                    showToast("Bluetooth on")
                } else {
                    disableAllButtons()
                    showToast("Bluetooth off")

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
        })

        listenerButton.setOnClickListener {
            if (!this::listenerDisposable.isInitialized || listenerDisposable.isDisposed) {
                toggleButtonDown(listenerButton, R.string.listening_broadcast)
                startService(Intent(this, BackgroundService::class.java))

                listenerDisposable = polarBleApi().startListenForPolarHrBroadcasts(null)
                    .subscribe(
                        { polarBroadcastData: PolarHrBroadcastData ->
                            heartRateValue.text = polarBroadcastData.hr.toString()
                            Log.d(
                                TAG,
                                "Displaying HR in activity, deviceId ${polarBroadcastData.polarDeviceInfo.deviceId} HR: ${polarBroadcastData.hr} "
                            )
                        },
                        { error: Throwable ->
                            toggleButtonUp(listenerButton, R.string.listen_broadcast)
                            Log.e(TAG, "Broadcast listener failed: $error")
                        },
                        { Log.d(TAG, "Complete") }
                    )
            } else {
                toggleButtonUp(listenerButton, R.string.listen_broadcast)
                listenerDisposable.dispose()
            }
        }

        connectButton.text = getString(R.string.connect_to_device, deviceId)
        connectButton.setOnClickListener {
            try {
                if (deviceConnected) {
                    polarBleApi().disconnectFromDevice(deviceId)
                } else {
                    polarBleApi().connectToDevice(deviceId)
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

        scanButton.setOnClickListener {
            val isDisposed = scanDisposable?.isDisposed ?: true
            if (isDisposed) {
                toggleButtonDown(scanButton, R.string.scanning_devices)
                scanDisposable = polarBleApi().searchForDevice()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { polarDeviceInfo: PolarDeviceInfo ->
                            Log.d(
                                TAG,
                                "Device found, ID: " + polarDeviceInfo.deviceId +
                                        " address: " + polarDeviceInfo.address +
                                        " rssi: " + polarDeviceInfo.rssi +
                                        " name: " + polarDeviceInfo.name +
                                        " is connectable: " + polarDeviceInfo.isConnectable
                            )
                        },
                        { error: Throwable ->
                            toggleButtonUp(scanButton, "Scan devices")
                            Log.e(TAG, "Device scan failed: $error")
                        },
                        { Log.d(TAG, "Complete") }
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
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.BLUETOOTH_ADMIN
                    ),
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
                    Log.w(TAG, "Insufficient permissions")
                    showToast("Insufficient permissions")
                    return
                }
            }
            Log.d(TAG, "The required permissions are granted")
            enableAllButtons()
        }
    }

    public override fun onPause() {
        super.onPause()
        polarBleApi().backgroundEntered()
    }

    public override fun onResume() {
        super.onResume()
        polarBleApi().foregroundEntered()
    }

    public override fun onDestroy() {
        super.onDestroy()
        polarBleApi().shutDown()
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
        listenerButton.isEnabled = false
        connectButton.isEnabled = false
        scanButton.isEnabled = false
    }

    private fun enableAllButtons() {
        listenerButton.isEnabled = true
        connectButton.isEnabled = true
        scanButton.isEnabled = true
    }

}