package io.github.molleware.porygonlist

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.theme.PorygonListTheme

class MainActivity : ComponentActivity() {

  /**
   * A pairing link waiting to be carried to the pairing screen, if the app was opened by one.
   *
   * Held here rather than read from `intent` inside composition, because an intent is consumed
   * once and composition runs whenever it likes. Cleared by the navigation once it has acted on it.
   * The activity's own `intent` keeps the link for as long as the activity lives, so it is read
   * only on a fresh start — see [actsOnLaunchLink] — or the pairing screen came back on every
   * rotation.
   */
  private var pairLink by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val fromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
    if (actsOnLaunchLink(restored = savedInstanceState != null, fromHistory = fromHistory)) pairLink = pairLinkIn(intent)

    // The palette is light-only, so both system bars are told to draw dark icons. Without this the
    // status bar keeps the system's dark-mode appearance and renders white glyphs on a cream
    // ground, which is close to illegible.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    // The app paints its own ground edge to edge, so there is no Surface wrapper here — the shell
    // fills the window itself and shopping mode can take it dark.
    setContent {
      PorygonListTheme { MainNavigation(pairLink = pairLink, onPairLinkHandled = { pairLink = null }) }
    }
  }

  /** The app was already open when the link was tapped. Delivered here because of `singleTop`. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pairLinkIn(intent)?.let { pairLink = it }
  }

  /**
   * Only the string is taken here. It is not decoded, and nothing is trusted on the strength of it
   * arriving — a link can come from any app on the phone. Reading it is also the sort of work that
   * must not grow: this runs before the first frame.
   */
  private fun pairLinkIn(intent: Intent?): String? =
    intent
      ?.takeIf { it.action == Intent.ACTION_VIEW }
      ?.data
      ?.takeIf { it.scheme.equals(PairingCodec.LINK_SCHEME, ignoreCase = true) }
      ?.toString()
}

/**
 * Whether a start of the activity acts on the link in the intent that started it.
 *
 * Only a fresh start does. [restored] is a recreation — a rotation, a font size change, or coming
 * back after Android ended the process — and the intent is the same one already acted on: reading
 * it again threw away where the person was and opened pairing over a link already used ("Ava's
 * phone is already paired."), with a socket and a fresh token behind it. [fromHistory] is the app
 * opened again from recents, which hands back the intent the task was first started with. A link
 * tapped while the app is open arrives through `onNewIntent` instead.
 */
internal fun actsOnLaunchLink(restored: Boolean, fromHistory: Boolean): Boolean = !restored && !fromHistory
