package io.github.molleware.porygonlist

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.molleware.porygonlist.theme.PorygonListTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // The palette is light-only, so both system bars are told to draw dark icons. Without this the
    // status bar keeps the system's dark-mode appearance and renders white glyphs on a cream
    // ground, which is close to illegible.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    // The app paints its own ground edge to edge, so there is no Surface wrapper here — the shell
    // fills the window itself and shopping mode can take it dark.
    setContent { PorygonListTheme { MainNavigation() } }
  }
}
