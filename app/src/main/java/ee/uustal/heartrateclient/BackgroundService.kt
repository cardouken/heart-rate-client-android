package ee.uustal.heartrateclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.polar.sdk.api.model.PolarHrBroadcastData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import java.time.LocalDateTime

class BackgroundService : Service() {
    companion object {
        private const val TAG = "BackgroundService"
        private const val API_LOGGER_TAG = "BACKGROUND API LOGGER"
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        polarBleApi().setApiLogger { s: String -> Log.d(API_LOGGER_TAG, s) }
        polarBleApi().startListenForPolarHrBroadcasts(null)
            .subscribe { polarBroadcastData: PolarHrBroadcastData ->
                Log.d(TAG, "Received HR data in background: " + polarBroadcastData.hr + " bpm")
                postResult(polarBroadcastData)
            }
    }

    private fun postResult(polarData: PolarHrBroadcastData) {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://rdp.uustal.ee:8080")
            .build()

        val retrofitService = retrofit.create(APIService::class.java)

        val jsonObject = JSONObject()
        jsonObject.put("heartRate", polarData.hr)
        jsonObject.put("timestamp", LocalDateTime.now())
        jsonObject.put("rssi", polarData.polarDeviceInfo.rssi)
        val jsonObjectString = jsonObject.toString()

        val requestBody = jsonObjectString.toRequestBody("application/json".toMediaTypeOrNull())

        CoroutineScope(Dispatchers.IO).launch {
            requestBody.runCatching { retrofitService.postHeartRate(requestBody) }
                .onSuccess {
                    val json = GsonBuilder().create().toJson(
                        JsonParser.parseString(
                            it.body()
                                ?.string() // see https://github.com/square/retrofit/issues/3255
                        )
                    )
                    Log.d("Response: ", json)

                }.onFailure { exception ->
                    Log.e(TAG, exception.message.toString())
                }
        }
    }

    override fun onDestroy() {
        Log.d(API_LOGGER_TAG, "Stopped background service")
        super.onDestroy()
        polarBleApi().shutDown()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = createNotificationChannel()

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Heart Rate Tracker")
            .setContentText("Collecting HR data in the background")
            .build()
        startForeground(2001, notification)

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "HRBackgroundService"
        val channelName = "HR Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)

        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE

        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)

        return channelId
    }

}
