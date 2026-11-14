package com.soundscape.app.core.database

object CacheKeys {
    internal const val MAX_LATITUDE = 90.0
    internal const val MAX_LONGITUDE = 180.0
    fun map(south: Double, west: Double, north: Double, east: Double): String {
        require(listOf(south, west, north, east).all { it.isFinite() })
        require(south in -MAX_LATITUDE..MAX_LATITUDE && north in south..MAX_LATITUDE)
        require(west in -MAX_LONGITUDE..MAX_LONGITUDE && east in -MAX_LONGITUDE..MAX_LONGITUDE)
        return encode(listOf("map", south.toString(), west.toString(), north.toString(), east.toString()))
    }

    fun search(text: String, onlyInApp: Boolean, genres: List<Int>, moods: List<Int>): String =
        encode(listOf("search", text, onlyInApp.toString(), genres.joinToString(","), moods.joinToString(",")))

    private fun encode(parts: List<String>): String = "v1:" + parts.joinToString("") { "${it.length}:$it" }
}
