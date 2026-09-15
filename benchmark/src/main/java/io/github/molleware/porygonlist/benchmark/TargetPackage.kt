package io.github.molleware.porygonlist.benchmark

/**
 * The application under measurement.
 *
 * Kept as a constant rather than read from BuildConfig so that a rename of the
 * app's application ID breaks this at one obvious place instead of silently
 * benchmarking nothing.
 */
const val TARGET_PACKAGE = "io.github.molleware.porygonlist"
