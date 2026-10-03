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

    @POST("api/schedules/{id}/toggle")
    suspend fun toggleSchedule(@Path("id") id: String): Response<ScheduleRes>

    @POST("api/schedules/{id}/request-delete")
    suspend fun requestDelete(@Path("id") id: String): Response<ScheduleRes>

    @POST("api/schedules/{id}/cancel-delete")
    suspend fun cancelDelete(@Path("id") id: String): Response<ScheduleRes>

    @POST("api/schedules/{id}/confirm-delete")
    suspend fun confirmDelete(@Path("id") id: String): Response<OkRes>

    @POST("api/usage/report")
    suspend fun reportUsage(@Body b: UsageReportReq): Response<OkRes>
}
