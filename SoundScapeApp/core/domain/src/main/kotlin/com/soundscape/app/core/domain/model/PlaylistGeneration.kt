package com.soundscape.app.core.domain.model

import java.util.UUID

data class GenerationOptions(
    val count: Int = 10,
    val onlyInApp: Boolean = false,
    val genre: String? = null,
    val mood: String? = null,
    val genreIds: List<Int> = emptyList(),
    val moodIds: List<Int> = emptyList(),
)

sealed interface GenerationRequest {
    val options: GenerationOptions
    data class Quick(val prompt: String, override val options: GenerationOptions = GenerationOptions()) : GenerationRequest
    data class Advanced(val prompt: String? = null, override val options: GenerationOptions = GenerationOptions()) : GenerationRequest
    data class Place(val placeId: UUID, override val options: GenerationOptions = GenerationOptions()) : GenerationRequest
    data class Journey(val stops: List<JourneyStop>, override val options: GenerationOptions = GenerationOptions()) : GenerationRequest
}

data class GeneratedItem(val trackId: UUID, val reason: String? = null)

data class GeneratedPlaylistDraft(
    val title: String,
    val description: String,
    val items: List<GeneratedItem>,
    val warnings: List<String> = emptyList(),
)
