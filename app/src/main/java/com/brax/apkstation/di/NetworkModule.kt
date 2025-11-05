package com.brax.apkstation.di

import com.brax.apkstation.BuildConfig
import com.brax.apkstation.data.network.LunrApiService
import com.brax.apkstation.data.network.dto.UpdateCheckResponseDeserializer
import com.brax.apkstation.data.network.dto.UpdateCheckResponseDto
import com.brax.apkstation.utils.Constants
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
        return Retrofit.Builder()
            .baseUrl(Constants.API_URL)
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