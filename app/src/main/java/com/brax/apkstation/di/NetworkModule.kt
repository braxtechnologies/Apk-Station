package com.brax.apkstation.di

import com.brax.apkstation.BuildConfig
import com.brax.apkstation.data.network.LunrApiService
import com.brax.apkstation.data.network.dto.UpdateCheckResponseDeserializer
import com.brax.apkstation.data.network.dto.UpdateCheckResponseDto
import com.brax.apkstation.utils.Constants
import com.brax.apkstation.utils.SrvResolver
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Singleton holder for the dynamic base URL interceptor
     * Allows access to clearCache() from outside the module
     */
    object DynamicBaseUrlHolder {
        private const val TAG = "DynamicBaseUrl"
        
        @Volatile
        private var resolvedBaseUrl: HttpUrl? = null
        
        private val defaultBaseUrl = Constants.API_URL.toHttpUrl()
        
        /**
         * Get the current resolved URL or resolve it if not cached
         */
        fun getBaseUrl(): HttpUrl {
            return resolvedBaseUrl ?: resolveBaseUrlSync().also { 
                resolvedBaseUrl = it 
            }
        }
        
        /**
         * Resolve base URL synchronously (called on background thread by OkHttp)
         */
        private fun resolveBaseUrlSync(): HttpUrl {
            return try {
                Log.d(TAG, "Resolving API URL via SRV...")
                
                // This runBlocking is OK because OkHttp already runs on a background thread
                val url = runBlocking { 
                    SrvResolver.resolveApiUrl()
                }
                
                Log.i(TAG, "✅ Using API URL: $url")
                url.toHttpUrl()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve SRV, using default: ${defaultBaseUrl.toUrl()}", e)
                defaultBaseUrl
            }
        }
        
        /**
         * Force re-resolution of the base URL on next request
         * This clears both the interceptor cache and SRV resolver cache
         */
        fun clearCache() {
            Log.d(TAG, "Clearing API URL cache - will re-resolve on next request")
            resolvedBaseUrl = null
            SrvResolver.clearCache()
        }
        
        /**
         * Get the current cached URL (if available) without triggering resolution
         */
        fun getCurrentCachedUrl(): String? {
            return resolvedBaseUrl?.toString()
        }
        
        /**
         * Manually set the resolved base URL (used to sync caches after manual resolution)
         * @param url The resolved URL string to cache
         */
        fun setResolvedUrl(url: String) {
            try {
                resolvedBaseUrl = url.toHttpUrl()
                Log.d(TAG, "Manually set resolved URL: $url")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set resolved URL: ${e.message}", e)
            }
        }
    }

    /**
     * Interceptor that dynamically resolves the base URL via SRV on first request
     * This avoids blocking the main thread during app initialization
     */
    private class DynamicBaseUrlInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            
            // Get or resolve the base URL (lazy initialization on first request)
            val baseUrl = DynamicBaseUrlHolder.getBaseUrl()
            
            // Replace the base URL in the request
            val newUrl = originalRequest.url.newBuilder()
                .scheme(baseUrl.scheme)
                .host(baseUrl.host)
                .port(baseUrl.port)
                .build()
            
            val newRequest = originalRequest.newBuilder()
                .url(newUrl)
                .build()
            
            return chain.proceed(newRequest)
        }
    }

    /**
     * Interceptor to fix malformed JSON from the API
     * Handles cases like: "excerpt": } (missing value)
     */
    private class MalformedJsonFixInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            
            // Only process JSON responses
            val contentType = response.body?.contentType()
            if (contentType?.subtype != "json") {
                return response
            }
            
            val originalBody = response.body?.string() ?: return response
            
            // Fix malformed JSON patterns
            val fixedBody = fixMalformedJson(originalBody)
            
            val newResponseBody = fixedBody.toResponseBody(contentType)
            return response.newBuilder()
                .body(newResponseBody)
                .build()
        }
        
        private fun fixMalformedJson(json: String): String {
            var fixed = json
            
            // Pattern 1: "fieldName": } or "fieldName": ] or "fieldName": ,
            // Replace with "fieldName": null
            fixed = fixed.replace(Regex(""""([^"]+)":\s*([}\],])""")) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                val terminator = matchResult.groupValues[2]
                """"$fieldName": null$terminator"""
            }
            
            // Pattern 2: "fieldName": \n } (with newline before closing brace)
            fixed = fixed.replace(Regex(""""([^"]+)":\s*\n\s*([}\],])""")) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                val terminator = matchResult.groupValues[2]
                """"$fieldName": null$terminator"""
            }
            
            return fixed
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().apply {
            // Increased timeouts to support APK download preparation (2-3 minutes)
            // As per API docs: "if the APK is not yet cached locally, it needs to be 
            // fetched from an external source. This can take up to 2-3 minutes"
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(200, TimeUnit.SECONDS) // 3+ minutes for download endpoint
            writeTimeout(30, TimeUnit.SECONDS)
            
            // Add dynamic base URL interceptor (MUST be first to modify URLs before other interceptors)
            addInterceptor(DynamicBaseUrlInterceptor())
            
            // Add interceptor to fix malformed JSON responses
            addInterceptor(MalformedJsonFixInterceptor())
            
            // Add logging interceptor in debug builds (should be last to log fixed responses)
            if (BuildConfig.DEBUG) {
                val loggingInterceptor = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                addInterceptor(loggingInterceptor)
            }
        }.build()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setStrictness(Strictness.LENIENT) // Handle malformed JSON from API (e.g., "excerpt": without value)
            .serializeNulls() // Serialize null values
            .registerTypeAdapter(
                UpdateCheckResponseDto::class.java,
                UpdateCheckResponseDeserializer()
            ) // Handle inconsistent update response (empty arrays vs objects)
            .create()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        // Use a placeholder base URL - the actual URL will be resolved dynamically
        // by DynamicBaseUrlInterceptor on the first API request (non-blocking)
        return Retrofit.Builder()
            .baseUrl(Constants.API_URL) // Placeholder, will be replaced by interceptor
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideLunrApiService(retrofit: Retrofit): LunrApiService {
        return retrofit.create(LunrApiService::class.java)
    }
}