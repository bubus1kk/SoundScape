package com.soundscape.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.soundscape.app.core.domain.model.MediaImageRequest
import com.soundscape.app.core.domain.model.MediaObjectType
import java.util.UUID

@Composable
internal fun CityPhoto(cityId: UUID, imageLoader: ImageLoader, modifier: Modifier) {
    val request = remember(cityId) { MediaImageRequest(MediaObjectType.PLACE, cityId) }
    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
