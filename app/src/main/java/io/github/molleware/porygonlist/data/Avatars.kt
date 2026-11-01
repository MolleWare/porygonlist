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

  fun byId(id: String): AvatarChoice? = all.firstOrNull { it.id == id }

  /**
   * What to draw for [id], or null to draw the initial.
   *
   * Null for an id this build does not know as well as for none at all: one sent by a newer phone,
   * for an avatar added after this build, is shown as the initial rather than as nothing.
   */
  fun emojiOf(id: String): String? = byId(id)?.emoji
}
