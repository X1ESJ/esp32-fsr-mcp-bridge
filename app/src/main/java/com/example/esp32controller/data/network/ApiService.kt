package com.example.esp32controller.data.network

import com.example.esp32controller.model.PinDashboard
import com.example.esp32controller.model.PinOperationResponse
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class DeviceStatusResponse(
    val status: Int = 0
)

data class DeviceSnapshotResponse(
    val status: Int = 0,
    val configs: List<com.example.esp32controller.model.PinConfig> = emptyList()
)

interface Esp32ApiService {
    @GET("on")
    suspend fun turnOn(): ResponseBody

    @GET("off")
    suspend fun turnOff(): ResponseBody

    @GET("status")
    suspend fun getStatus(): DeviceStatusResponse

    @GET("pins")
    suspend fun getPins(): PinDashboard

    @GET("snapshot")
    suspend fun getSnapshot(): DeviceSnapshotResponse

    @GET("fsr/changes")
    suspend fun getFsrChanges(): DeviceSnapshotResponse

    @FormUrlEncoded
    @POST("pin/config")
    suspend fun configurePin(
        @Field("pin") pin: Int,
        @Field("direction") direction: String,
        @Field("mode") mode: String,
        @Field("value") value: Int,
        @Field("label") label: String?
    ): PinOperationResponse

    @FormUrlEncoded
    @POST("pin/delete")
    suspend fun deletePinConfig(
        @Field("pin") pin: Int
    ): PinOperationResponse
}

class Esp32ApiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    fun create(ipAddress: String): Esp32ApiService {
        return Retrofit.Builder()
            .baseUrl("http://$ipAddress/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Esp32ApiService::class.java)
    }
}
