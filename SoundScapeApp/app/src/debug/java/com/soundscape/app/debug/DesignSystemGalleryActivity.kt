package com.soundscape.app.debug

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.soundscape.app.core.designsystem.ui.gallery.DesignSystemGallery

/** Separate debug launcher; production MainActivity and navigation are untouched. */
class DesignSystemGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DesignSystemGallery { dark ->
                // Explicit mode can differ from the system; keep icon contrast in sync.
                // Transparent bars use the active themed backdrop; AndroidX retains API-specific
                // platform contrast enforcement.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    ) { dark },
                )
            }
        }
    }
}
