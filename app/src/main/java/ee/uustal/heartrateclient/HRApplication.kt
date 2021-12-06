package ee.uustal.heartrateclient

import android.app.Application
import android.content.Context
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl

class HRApplication : Application() {

    val api: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(applicationContext, PolarBleApi.ALL_FEATURES)
    }
}

fun Context.polarBleApi() = (applicationContext as HRApplication).api

