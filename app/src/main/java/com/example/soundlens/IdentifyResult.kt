package com.example.soundlens

data class IdentifyResult(
    val song_id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val path: String?,
    val youtube_url: String?,
    val youtube_id: String?,
    val offset_frames: Int,
    val matches_at_best_offset: Int?,
    val matches_for_song: Int?,
    val confidence: Double,
    val clip_hashes: Int?
)
