package ee.uustal.heartrateclient


import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface APIService {

    @POST("/heart-rate")
    suspend fun postHeartRate(@Body requestBody: RequestBody): Response<ResponseBody>

}