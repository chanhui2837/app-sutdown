package com.appshutdown.app

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object AuthManager {
    private const val PREF = "appshutdown_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_URL = "base_url"
    // 에뮬레이터 기본값. 실기기는 PC IP로 Main/로그인 화면에서 변경 가능 (예: http://192.168.0.5:3001/)
    const val DEFAULT_URL = "http://10.0.2.2:3001/"

    fun getBaseUrl(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setBaseUrl(ctx: Context, url: String) {
        var u = url.trim()
        if (!u.endsWith("/")) u += "/"
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_URL, u).apply()
        ApiClient.reset()
    }

    fun saveToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
        ApiClient.reset()
    }

    fun getToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun logout(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
        ApiClient.reset()
    }

    fun isLoggedIn(ctx: Context): Boolean = !getToken(ctx).isNullOrEmpty()
}

object ApiClient {
    @Volatile private var api: ApiService? = null

    fun get(ctx: Context): ApiService {
        val appCtx = ctx.applicationContext
        return api ?: synchronized(this) {
            api ?: build(appCtx).also { api = it }
        }
    }

    fun reset() { api = null }

    private fun build(ctx: Context): ApiService {
        val authInterceptor = Interceptor { chain ->
            val token = AuthManager.getToken(ctx)
            val req = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else chain.request()
            chain.proceed(req)
        }
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(log)
            // Render 무료 플랜 콜드스타트(50초+) 대응
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(AuthManager.getBaseUrl(ctx))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
