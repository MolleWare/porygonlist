package io.github.molleware.porygonlist

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The lists you keep, and the sync banner over them. */
@Serializable data object Lists : NavKey

/** One list, open for editing. Which list is held in app state, not in the key. */
@Serializable data object ListDetail : NavKey

/** Shopping mode. */
@Serializable data object Shop : NavKey

/** The staples grid. */
@Serializable data object Staples : NavKey

/** Sharing: networks, export and import, people. */
@Serializable data object Share : NavKey

/** This phone rather than a list: your name, your id, paired phones, starting over. */
@Serializable data object Settings : NavKey

/** Showing your pairing code and taking someone else's. */
@Serializable data object PairPhone : NavKey
