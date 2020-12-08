package com.betomorrow.gradle.appcenter.infra

import org.gradle.api.Project
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AppCenterAPIFactory(
    private val project: Project
) {

    fun create(apiToken: String, debug: Boolean): AppCenterAPI {
        val client = createHttpClient(debug, apiToken)

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.appcenter.ms/v0.1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(AppCenterAPI::class.java)
    }

    fun createUploadApi(uploadDomain: String, apiToken: String, debug: Boolean): UploadAPI {
        val client = createHttpClient(debug, apiToken)

        val retrofit = Retrofit.Builder()
            .baseUrl(uploadDomain)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(UploadAPI::class.java)
    }

    private fun createHttpClient(debug: Boolean, apiToken: String) =
        OkHttpBuilder(project)
            .logger(debug)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-API-Token", apiToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
}
