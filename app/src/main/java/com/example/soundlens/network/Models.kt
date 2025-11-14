package com.example.soundlens.network

data class IdentifyRequestS3(
    val s3_bucket: String,
    val s3_key: String,
    val request_id: String? = null
)

data class IdentifyResponse(
    val ok: Boolean? = null,
    val reason: String? = null,
    val request_id: String? = null,
    val song_id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val path: String? = null,
    val youtube_url: String? = null,
    val youtube_id: String? = null,
    val offset_frames: Int? = null,
    val matches_at_best_offset: Int? = null,
    val matches_for_song: Int? = null,
    val confidence: Double? = null,
    val clip_hashes: Int? = null
)

data class PlotRequest(val request_id: String)
data class PlotResponse(val plot_url: String?)
