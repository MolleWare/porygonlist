<!-- The text of the merge request to https://gitlab.com/fdroid/fdroiddata that adds
     metadata/io.github.molleware.porygonlist.yml (everything in the draft from "Categories:" down).
     Title: New app: PorygonList -->

PorygonList is a shared-list app (shopping lists, to-dos) that syncs directly between paired
phones on a local wifi the user has approved. There is no account, no server and no internet
traffic: phones find each other with mDNS and exchange changes over TLS pinned to a key shown at
pairing time.

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (and does not oppose the inclusion): I am the author
* [x] All related fdroiddata and RFP issues have been referenced in this merge request: there are none
* [x] Builds with `fdroid build` and all pipelines pass

**Reproducible build.** `Binaries` and `AllowedAPKSigningKeys` are set: the APK attached to the
GitHub release of the tag is signed with the same key we use on Google Play, and F-Droid's build of
the tag matches it apart from the signature. Checked before submitting with `fdroid build` in
F-Droid's build image against the release APK.

**Dependencies.** AndroidX and Jetpack Compose only, from Google's Maven repository, and Kotlin's
standard libraries from Maven Central. No Google Play services, Firebase, analytics, crash
reporting or ads.

**Permissions.** `INTERNET` and `ACCESS_NETWORK_STATE` for the local wifi only. Location is
optional and only lets Android tell the app the wifi's name. `RECEIVE_BOOT_COMPLETED`,
`SCHEDULE_EXACT_ALARM` and `FOREGROUND_SERVICE` are for optional background sync on the quarter
hour. No anti-features that we know of.
