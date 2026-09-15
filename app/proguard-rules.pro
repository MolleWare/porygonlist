# R8 rules for the release build.
#
# Deliberately near-empty: AGP already supplies rules for Android, Compose and
# kotlinx.serialization via proguard-android-optimize.txt and the consumer
# rules bundled with each library. Add rules here only when R8 is demonstrably
# stripping something needed at runtime -- every keep rule costs shrinking and
# some startup optimisation, so justify each one.
#
# When adding a rule, note WHY, so it can be re-evaluated later.

# Keep line numbers in stack traces, and map them back via mapping.txt.
# Without this, release crash reports are unreadable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
