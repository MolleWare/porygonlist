# PorygonList

Shared lists that sync between phones over the internet.

Build a list with one or more other people and have every change show up on
everyone's device, the way Syncthing keeps a folder in step across machines.

**Status: early scaffold. Nothing works yet.** The repository currently holds a
project skeleton and its build configuration. There is no list editing and no
sync. It is not usable, and there is no release to install.

## Goals

- **Fast to open.** A list app that takes a second to start is a list app you
  stop reaching for. Cold start is treated as a feature with a measured budget,
  not as something to look at later.
- **Shared, not centralised.** Lists sync between the people who hold them.
- **Free software, no Google dependencies.** No Play Services, no Firebase, no
  analytics. Intended for F-Droid first.

## Building

Requires JDK 21 and the Android SDK. `ANDROID_HOME` must point at your SDK, or
`local.properties` must set `sdk.dir`.

```sh
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (unsigned)
```

The build pins `jvmToolchain(21)` and deliberately does not use Gradle's
toolchain auto-provisioning, so a missing JDK 21 fails the build rather than
silently downloading one. Install JDK 21 and point `JAVA_HOME` at it.

## Contributing

The project is too young for its shape to be settled, so large unsolicited
changes are likely to collide with work in progress. Opening an issue to
discuss an idea first will save you effort.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

This is copyleft: you may use, modify and redistribute this code, but anything
you distribute that is derived from it must also be free software under the
same terms, with source available.
