package com.soundscape.app.presentation.generation

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import java.util.UUID
import kotlinx.coroutines.flow.map

internal interface WorkshopPersistence {
    var form: GenerationForm
    var draft: WorkshopDraft?
}

internal class SavedWorkshopState(private val saved: SavedStateHandle) : WorkshopPersistence {
    override var form: GenerationForm
        get() = decodeForm(saved["form"])
        set(value) { saved["form"] = encodeForm(value) }

    override var draft: WorkshopDraft?
        get() = decodeDraft(saved["draft"])
        set(value) {
            if (value == null) saved.remove<Bundle>("draft")
            else saved["draft"] = encodeDraft(value)
        }
}

private fun encodeForm(form: GenerationForm) = Bundle().apply {
    putString("mode", form.mode.name)
    putString("quick", form.quick)
    putString("advanced", form.advanced)
    putString("placePrompt", form.placePrompt)
    putInt("count", form.count)
    putBoolean("onlyInApp", form.onlyInApp)
    form.genre?.let { putInt("genre", it.id); putString("genreName", it.name) }
    form.mood?.let { putInt("mood", it.id); putString("moodName", it.name) }
    form.place?.let { putString("placeId", it.id.toString()); putString("placeTitle", it.title) }
    putStringArrayList("stopIds", ArrayList(form.stops.map { it.id.toString() }))
    putStringArrayList("stopTitles", ArrayList(form.stops.map(SearchHit::title)))
}

private fun decodeForm(bundle: Bundle?): GenerationForm {
    if (bundle == null) return GenerationForm()
    fun place(id: String, title: String) = SearchHit(SearchCategory.PLACE, UUID.fromString(id), title, null, null, null)
    return GenerationForm(
        GenerationMode.valueOf(bundle.getString("mode", "QUICK")),
        bundle.getString("quick", ""), bundle.getString("advanced", ""), bundle.getString("placePrompt", ""),
        bundle.getString("placeId")?.let { place(it, bundle.getString("placeTitle", "")) },
        bundle.getStringArrayList("stopIds").orEmpty().zip(bundle.getStringArrayList("stopTitles").orEmpty(), ::place),
        bundle.getInt("count", DEFAULT_TRACK_COUNT),
        bundle.getString("genreName")?.let { SearchOption(bundle.getInt("genre"), it) },
        bundle.getString("moodName")?.let { SearchOption(bundle.getInt("mood"), it) },
        bundle.getBoolean("onlyInApp"),
    )
}

private fun encodeDraft(draft: WorkshopDraft) = Bundle().apply {
    putString("id", draft.id.toString())
    putString("owner", draft.owner.toString())
    putString("title", draft.title)
    putString("description", draft.description)
    putString("visibility", draft.visibility.name)
    putString("context", draft.context)
    putStringArrayList("trackIds", ArrayList(draft.tracks.map { it.id.toString() }))
    putStringArrayList("reasons", ArrayList(draft.tracks.map { it.reason.orEmpty() }))
    putStringArrayList("availability", ArrayList(draft.tracks.map { it.availability?.name.orEmpty() }))
    putStringArrayList("warnings", ArrayList(draft.warnings))
    putBoolean("uncertain", draft.uncertain)
    putLong("revision", draft.revision ?: 0)
    putBoolean("confirmed", draft.confirmed)
}

private fun decodeDraft(bundle: Bundle?): WorkshopDraft? {
    if (bundle == null) return null
    val reasons = bundle.getStringArrayList("reasons").orEmpty()
    val availability = bundle.getStringArrayList("availability").orEmpty()
    val items = bundle.getStringArrayList("trackIds").orEmpty().mapIndexed { index, id ->
        EditableTrack(UUID.fromString(id), reasons.getOrNull(index)?.ifBlank { null },
            availability.getOrNull(index)?.takeIf(String::isNotEmpty)?.let(TrackAvailability::valueOf))
    }
    return WorkshopDraft(
        UUID.fromString(requireNotNull(bundle.getString("id"))),
        UUID.fromString(requireNotNull(bundle.getString("owner"))),
        bundle.getString("title", ""), bundle.getString("description", ""), items,
        bundle.getStringArrayList("warnings").orEmpty(), Visibility.valueOf(bundle.getString("visibility", "PRIVATE")),
        bundle.getString("context"), bundle.getBoolean("uncertain"), bundle.getLong("revision").takeIf { it > 0 },
        bundle.getBoolean("confirmed"),
    )
}
