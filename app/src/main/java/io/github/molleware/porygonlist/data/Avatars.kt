package io.github.molleware.porygonlist.data

/**
 * Something a person can show in place of their initial.
 *
 * [id] is what is stored and sent, never [emoji]. The emoji is only how this build draws it, so a
 * drawn picture can take its place later without a phone, or a save, having to be told.
 *
 * [cost] is room for avatars that are earned rather than given: none are yet, and nothing here
 * counts anything. Zero means free to choose.
 */
data class AvatarChoice(val id: String, val emoji: String, val name: String, val cost: Int = 0) {
  val free: Boolean
    get() = cost == 0
}

/** Every avatar this build knows, in the order Settings offers them. */
object Avatars {
  /** No avatar: the initial, which is what everyone has until they choose. */
  const val NONE = ""

  val all: List<AvatarChoice> =
    listOf(
      AvatarChoice("apple", "🍎", "Apple"),
      AvatarChoice("carrot", "🥕", "Carrot"),
      AvatarChoice("avocado", "🥑", "Avocado"),
      AvatarChoice("lemon", "🍋", "Lemon"),
      AvatarChoice("mushroom", "🍄", "Mushroom"),
      AvatarChoice("broccoli", "🥦", "Broccoli"),
    )

  /** The ones anybody can choose today. */
  val free: List<AvatarChoice>
    get() = all.filter { it.free }

  /**
   * The ones to offer on this phone: free, and drawable by its fonts — [canDraw] says which. An
   * older Android has no broccoli, and offering an empty box would be offering nothing.
   */
  fun choosable(canDraw: (String) -> Boolean): List<AvatarChoice> = free.filter { canDraw(it.emoji) }

  fun byId(id: String): AvatarChoice? = all.firstOrNull { it.id == id }

  /**
   * What to draw for [id], or null to draw the initial.
   *
   * Null for an id this build does not know as well as for none at all: one sent by a newer phone,
   * for an avatar added after this build, is shown as the initial rather than as nothing. Null too
   * for an emoji this phone's fonts cannot draw, which [canDraw] says: someone on a newer phone may
   * wear one an older one would show as an empty box.
   */
  fun emojiOf(id: String, canDraw: (String) -> Boolean = { true }): String? = byId(id)?.emoji?.takeIf(canDraw)
}

/**
 * Whether this phone's fonts can draw a piece of text, asked once per text and remembered.
 *
 * [hasGlyph] is the real question — the system font's, on a phone — and it is not cheap enough to
 * ask on every frame. The answer cannot change while the app runs.
 */
class GlyphCheck(private val hasGlyph: (String) -> Boolean) {
  private val known = HashMap<String, Boolean>()

  fun canDraw(text: String): Boolean = synchronized(known) { known.getOrPut(text) { hasGlyph(text) } }
}
