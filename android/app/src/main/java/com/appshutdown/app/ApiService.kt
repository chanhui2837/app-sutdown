package com.appshutdown.app

import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("api/auth/signup")
    suspend fun signup(@Body b: AuthReq): Response<AuthRes>

    @POST("api/auth/login")
    suspend fun login(@Body b: AuthReq): Response<AuthRes>

    @GET("api/schedules")
    suspend fun getSchedules(): Response<SchedulesRes>

    @POST("api/schedules")
    suspend fun createSchedule(@Body b: Map<String, @JvmSuppressWildcards Any>): Response<ScheduleRes>

    @PUT("api/schedules/{id}")
    suspend fun updateSchedule(
        @Path("id") id: String,
        @Body b: Map<String, @JvmSuppressWildcards Any>
    ): Response<ScheduleRes>

    // 즉시 삭제 (엄격모드 OFF일 때만 앱에서 호출)
    @DELETE("api/schedules/{id}")
    suspend fun deleteSchedule(@Path("id") id: String): Response<OkRes>

    @POST("api/usage/report")
    suspend fun reportUsage(@Body b: UsageReportReq): Response<OkRes>
}
