# PorygonList privacy policy

*Applies to PorygonList for Android, version 0.1.0 and later. Last changed 10 November 2026.*

PorygonList has no server and no account, and the people who make it receive nothing from it.
There are no analytics, no advertising, no crash reporting and no tracking of any kind.

## What stays on your phone

Everything the app knows lives in its private storage on your phone:

- your lists and their items;
- what you cleared off each list, as its history;
- your recipes, and the staples the app learned from your lists;
- the name and the picture you gave yourself in the app;
- the phones you have paired with: their name, their public key, and the address and port on your
  wifi where each was last reached;
- the wifi networks you approved, with the name you gave each one. If you allowed the optional
  location permission, that name can be the wifi's own name.

Uninstalling the app deletes all of it. Your phone's private key is kept in Android's secure
keystore and never leaves the phone.

## What leaves your phone, and where it goes

Nothing goes to us or to any server. What leaves your phone goes to other phones, directly:

- **When you pair**, your name and your phone's public key. They are in the code you show or send.
  A code shown on screen also holds your phone's address on the wifi you are on, so the phone that
  scans it can send its own name and key straight back to that address, on whatever wifi you are
  both on; while it is on screen, anything that connects is shown your phone's public key. A code
  you copy and send in a message holds only your name and key, and goes wherever you send it.
- **A shared list** goes to the phones of the people on it, and to no one else: its name, its items
  with who added each and the recipe it is for, and the name and picture of everyone on it, yours
  included. Its history, your recipes and your staples stay on your phone. It is sent over your local wifi, never over the
  internet, only on a wifi you approved, and only to a phone whose key was checked when you paired.
- **On a wifi you approved**, your phone tells the local network it is there, so your paired phones
  can find it: a fixed id made from its public key, and the address and port it listens on. Anything
  on that wifi can see this, and anything that connects is shown your phone's public key; only a
  paired phone gets any further. Paired phones are also told the port to call back on.

The app knows an approved wifi by its router, its address range and its DNS servers, not by its
name. Another wifi set up the same way, such as two homes on the same model of router with its
settings unchanged, counts as the same one.

A private list never leaves your phone. When you choose to send a list as a text message instead,
it goes wherever you send that message, through the messaging app you choose.

## Permissions

- **Network access**, to reach paired phones on your local wifi.
- **Location (optional)**, only because Android will not tell an app the wifi's name without it. It
  is used to show that name. Android asks for precise and approximate location together; only
  precise shows the name. The app never reads your location, and works fully if you say no.
- **Run at startup**, so background sync on your approved wifi carries on after a restart.
- **Alarms (optional)**, for background sync, which wakes on the exact quarter hour so closed phones
  sync at the same moment. Without them it stays off, and lists sync while the app is open.
- **Foreground service**, to stay on the wifi for the half minute of a background sync. Android may
  show a quiet notification while it runs.

## Backups

PorygonList opts out of Android's backup and of copying apps from one phone to another. Your lists
are not put in your phone's cloud backup, and they do not move to a new phone. They are meant to be
thrown away, and they live only on the phones that share them.

## Changes and contact

If this policy changes, the new version will be at this address with a new date. Questions and
problems: <https://github.com/MolleWare/porygonlist/issues>.
