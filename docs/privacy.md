# PorygonList privacy policy

*Applies to PorygonList for Android, version 0.1.0 and later. Last changed 6 October 2026.*

PorygonList has no server and no account, and the people who make it receive nothing from it.
There are no analytics, no advertising, no crash reporting and no tracking of any kind.

## What stays on your phone

Everything the app knows lives in its private storage on your phone:

- your lists and their items;
- the name you gave yourself in the app;
- the phones you have paired with: their name and their public key;
- the wifi networks you approved, with the name you gave each one. If you allowed the optional
  location permission, that name can be the wifi's own name.

Uninstalling the app deletes all of it. The key that identifies your phone is kept in Android's
secure keystore and never leaves the phone.

## What leaves your phone, and where it goes

Only one thing, and only to one kind of place: a shared list is sent to the phones of the people you
shared it with, after you paired with them by scanning a code. It is sent:

- directly from phone to phone over your local wifi, never over the internet;
- only on a wifi network you have approved;
- encrypted, to a phone whose key was checked when you paired.

A private list never leaves your phone. When you choose to send a list as a text message instead,
it goes wherever you send that message, through the messaging app you choose.

## Permissions

- **Network access**, to reach paired phones on your local wifi.
- **Location (optional)**, only because Android will not tell an app the wifi's name without it. It
  is used to show that name. The app never reads your location, and works fully if you say no.
- **Run at startup**, so background sync on your approved wifi carries on after a restart.

## Backups

Android may include the app's data in your phone's own backup, if you have backups turned on. That
backup is handled by your phone's system and goes wherever your system sends it, not to us. Lists
restored from a backup onto a new phone are not kept, because the new phone has a new identity.

## Changes and contact

If this policy changes, the new version will be at this address with a new date. Questions and
problems: <https://github.com/MolleWare/porygonlist/issues>.
