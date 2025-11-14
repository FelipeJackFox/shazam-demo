package com.example.soundlens.network

import android.content.Context
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Network {
    private fun client(): OkHttpClient {
        val log = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(log)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun retrofit(baseUrl: String): Retrofit {
        val gson = GsonBuilder().create()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun identifyApi(ctx: Context): IdentifyApi =
        retrofit(ctx.getString(com.example.soundlens.R.string.identify_base_url))
            .create(IdentifyApi::class.java)

    fun plotApi(ctx: Context): PlotApi =
        retrofit(ctx.getString(com.example.soundlens.R.string.plot_base_url))
            .create(PlotApi::class.java)
}
