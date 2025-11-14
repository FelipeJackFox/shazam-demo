package com.example.soundlens.network

import retrofit2.http.Body
import retrofit2.http.POST

interface IdentifyApi {
    @POST("default/shazam-indexer")
    suspend fun identify(@Body body: IdentifyRequestS3): IdentifyResponse
}

interface PlotApi {
    @POST("default/plot")
    suspend fun createPlot(@Body body: PlotRequest): PlotResponse
}
