# tv-os

Scripts for managing a TCL Android TV from a Mac over Wi-Fi ADB.

Target: TCL RT51M chassis, Android TV 11 (not Google TV), firmware
`V8-R51MT05-LF1V620.021315`, security patch March 2024.

Everything is plain bash, and the only dependency is `adb`, except for our
Home app in `home/`, which also needs Java and the Android SDK to build (see
[Home app](#home-app)).

## Setup

### 1. Install adb

```sh
brew install --cask android-platform-tools
adb version
```

### 2. Turn on developer mode on the TV

This needs someone with the remote; a script can't do it.

1. On the TV, open **Settings → Device Preferences → About**.
2. Select **Build** 7 times. The TV says you're now a developer.
3. Go back to **Settings → Device Preferences → Developer options**.
4. Turn on **USB debugging**.
5. Turn on **Network debugging** if your TV has it (some firmware calls it
   **ADB over network**). The RT51M firmware has no such toggle: on that TV,
   USB debugging alone makes it listen for ADB over Wi-Fi on port 5555.

To check the TV is listening, run `nc -vz <tv-ip> 5555` on the Mac. It should
say "succeeded".

The TV's IP is in **Settings → Network & Internet** → your Wi-Fi network.

### 3. Give the TV a fixed IP

The scripts find the TV by IP, so the IP must not change. In your router's
admin page (often `192.168.1.1` or `192.168.0.1`), find the **DHCP reservation**
setting. Routers also call it **static lease**, **address reservation** or
**fixed IP**. Reserve the TV's current IP for its MAC address. The TV's MAC
address is in **Settings → Device Preferences → About → Status**.
Restart the TV and check that it keeps the same IP.

### 4. Tell the scripts about the TV

```sh
cp tvs.conf.example tvs.conf
```

Edit `tvs.conf` and put in your TV's IP. It holds one TV per line
(`<name> <ip>`), so you can add more TVs later. `tvs.conf` is git-ignored.

### 5. Connect

```sh
bin/connect
```

The first time, the TV shows an **Allow USB debugging?** prompt. Tick
**Always allow from this computer** and choose **OK**, then run `bin/connect`
again.

If it can't reach the TV, check that the Mac is on the same Wi-Fi network as
the TV and not on a phone hotspot: `ipconfig getifaddr en0` (or `en1`) should
print an address in the same range as the TV's.

## Scripts

Each script uses the first TV in `tvs.conf`. To use a different TV, pass its
name, e.g. `bin/check bedroom`.

| Script        | What it does                                                                 |
| ------------- | ---------------------------------------------------------------------------- |
| `bin/connect` | Connects to the TV over Wi-Fi ADB (port 5555).                               |
| `bin/check`   | Prints the TV's model, Android version, firmware, security patch, Home activity and whether launcherx and setupwraith are enabled, and the state of every package on the disable list. |
| `bin/backup`  | Snapshots every package's state to `backups/<tv-name>/<timestamp>.txt`.      |
| `bin/restore` | Re-enables packages that were enabled in a snapshot. Never disables anything. |
| `bin/debloat` | Backs up, then disables the packages on `lists/disable.txt`. Never uninstalls. |
| `bin/launcher` | Makes our Home app the Home screen by disabling the stock launcher, rolling back if Home doesn't land on it. |
| `bin/sideload` | Installs or updates every APK in `apks/`.                                    |
| `bin/home`    | Builds our Home app and installs or updates it on the TV.                    |

## Backup and restore

Take a backup before changing anything on the TV, and commit it:

```sh
bin/backup
git add backups && git commit -m "Back up living-room before debloat"
```

A snapshot lists every package on the TV, one per line, with its state:

- `enabled`: installed and enabled
- `disabled`: disabled with `pm disable-user --user 0`
- `uninstalled`: still on the system but removed with `pm uninstall --user 0`

Snapshots hold only package names, so they're safe to commit. Git history then
records what the TV looked like before each change.

To undo changes, restore a snapshot:

```sh
bin/restore                                        # first TV's latest snapshot
bin/restore bedroom                                # bedroom's latest snapshot
bin/restore backups/living-room/2026-10-28-134500.txt  # a specific snapshot
```

Restore re-enables every package that was `enabled` in the snapshot but is
disabled now (`pm enable`) or uninstalled now (`cmd package install-existing`).
It never disables anything, so it only undoes disables, and running it twice
does nothing the second time. Packages installed since the snapshot are left
alone.

If restore re-enables the stock launcher (`com.google.android.apps.tv.launcherx`),
it's the Home screen again: it registers Home at a higher priority than our
app.

## Debloat

```sh
bin/debloat
git add backups && git commit -m "Back up living-room before debloat"
```

Debloat turns off ads, recommendation rows, telemetry and TCL promo apps. It
works from two lists, one package per line, each with a comment saying what
it is:

- `lists/disable.txt`: what debloat disables
- `lists/never-touch.txt`: what it must never disable: HDMI inputs, HDMI-CEC,
  Chromecast built-in, Google Assistant and voice, the Play Store, TCL system
  services and the TCL firmware updater

Debloat refuses to run if a package is on both lists. Otherwise it takes a
backup with `bin/backup`, then disables each listed package that's enabled
with `pm disable-user --user 0`. It never uninstalls anything, and it leaves
listed packages that are already disabled, uninstalled or not on the TV. When
there's nothing to disable it takes no backup and changes nothing.

A TCL firmware update can re-enable packages. After each one, run `bin/check`
to see which packages on the disable list are back on, then run
`bin/debloat` again. Firmware updates stay on: the updater is on the
never-touch list.

To undo a debloat, run `bin/restore` with the snapshot debloat took.

The disable list includes the Google TV screensaver
(`com.google.android.apps.tv.dreamx`). Point the screensaver at Android's
basic one so the TV doesn't go on asking for the disabled one:

```sh
adb -s <tv-ip>:5555 shell settings put secure screensaver_components com.android.dreams.basic/.Colors
```

To make menus feel quicker, halve the animation speeds:

```sh
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s <tv-ip>:5555 shell settings put global $k 0.5
done
```

Keep the disable list conservative. Before you add a package, find out what it
does, and run the test checklist below afterwards.

### Test checklist

Run this on the TV after each debloat:

- [ ] HDMI input switching works
- [ ] HDMI-CEC controls the soundbar/console
- [ ] Chromecast casting to the TV works
- [ ] Voice search works
- [ ] A streaming app plays

Once our Home app is the Home screen (see below), also check:

- [ ] Home button lands on our Home app
- [ ] After a reboot, Home still lands on our Home app
- [ ] HDMI tiles switch to the right input
- [ ] The Settings tile opens Android TV Settings

## Home screen

Our [Home app](#home-app) replaces the stock Android TV launcher and its ad
and recommendation rows.

On this firmware a default Home can't be chosen while the stock launcher is
enabled. `com.google.android.apps.tv.launcherx` registers Home at priority 2
and `com.google.android.tungsten.setupwraith` at priority 1, and installed
apps are capped at 0, so `cmd package set-home-activity` says Success and is
ignored. The only way for our app to own Home is to disable both.

1. Install our Home app with `bin/home`.
2. Run `bin/launcher`. It:
   1. checks our Home app is installed and enabled, and refuses, changing
      nothing, if it isn't
   2. takes a backup with `bin/backup`, if there's anything to disable
   3. disables launcherx and setupwraith with `pm disable-user --user 0`
   4. presses Home and checks our app comes to the front
   5. if it doesn't, re-enables both straight away and fails, so the TV is
      never left on a broken Home
3. Reboot the TV (`adb -s <tv-ip>:5555 reboot`), run `bin/connect`, and press
   Home on the remote. It should land on our Home app.
4. Commit the backup and run the test checklist above.

`bin/launcher` is safe to run again: it only disables what's enabled, and
always checks Home.

If our app is ever missing or disabled, Android falls back to TV Settings'
FallbackHome (priority -1000), so there's still a Home and adb still works.
Run `bin/home` to reinstall it.

Disabling launcherx loses the notifications panel and the profile/account
switcher. Voice search, Google Assistant, Chromecast built-in and the remote's
app buttons are unaffected.

A firmware update may re-enable launcherx and take Home back. `bin/check`
shows whether launcherx and setupwraith are enabled; if either is, run
`bin/launcher` again.

To go back to the stock launcher, run `bin/restore` with the snapshot
`bin/launcher` took. It re-enables launcherx and setupwraith, and launcherx
wins Home again by priority.

## Sideload apps

For apps that aren't on the Play Store, put their `.apk` files in `apks/` at
the repo root and run:

```sh
mkdir -p apks
cp ~/Downloads/SmartTube.apk apks/
bin/sideload
```

It installs each APK with `adb install -r` and prints `ok` or `FAILED` with
the reason for each one. `-r` updates apps that are already installed, so to
update an app, replace its APK in `apks/` and run `bin/sideload` again.
Sideloaded apps show up in the TV's app list (**Settings → Apps**, and on
the Home screen).

`apks/` is git-ignored, so APKs are never committed. Keep the downloaded
files somewhere else too if you want them after a fresh clone.

An update fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when the new APK is
signed with a different key than the installed app, e.g. it came from a
different source. Uninstall the app on the TV, then run `bin/sideload` again.

## Home app

`home/` holds our own minimal Home app, "Home" (`io.github.james_c_bc.home`):
a row of input and Settings tiles, then every installed TV app as its
banner, five per row, in the order [`home/config/apps.txt`](#app-order)
gives, on a dark background. Apps without a
banner show their icon and name. D-pad moves between tiles and Select opens
one. The grid updates when apps are installed or removed, and Back does
nothing.

The top row replaces the stock launcher's input row:

- An **HDMI** tile for each HDMI input, which switches the TV straight to
  it. Home finds the inputs on the TV (`com.tcl.tvinput`'s pass-through
  inputs, by their "HDMI 1", "HDMI 2"… labels) rather than hard-coding
  them, and the tiles show even with nothing plugged in. This model's
  chassis has a fourth HDMI port, but the firmware gives it no input (TCL's
  own TV app and source menu don't offer it either), so this TV shows
  HDMI 1–3.
- A **Settings** tile, which opens Android TV Settings. The remote's
  settings button still opens TCL's picture and sound menu.

It's Kotlin with plain Android views, targeting Android 11, and it's built
with the Gradle wrapper in `home/`. It registers as both a Home activity and
a normal TV app, so it also opens from the stock launcher's app list.
`bin/launcher` makes it the Home screen (see [Home screen](#home-screen)).

The firmware won't let Projectivy be the default Home: launcherx and
setupwraith register Home at higher priority than any installed app can.
That's why this app exists.

### App order

`home/config/apps.txt` sets the order of apps on Home and hides ones you
never use. It holds one package per line, with an optional `# comment`:

```
com.netflix.ninja                 # Netflix
com.google.android.youtube.tv     # YouTube
-com.tcl.guard                    # TCL Guard: hidden
```

- Listed packages come first, in file order.
- Every other installed app follows, alphabetically.
- A line starting with `-` hides that package.
- Listed packages that aren't installed are skipped, and blank lines are
  ignored.

The file is built into the app, so after editing it, run `bin/home` to
rebuild and reinstall Home. To find an app's package, run
`adb -s <tv-ip>:5555 shell pm list packages`, or look it up in the app's
Play Store URL (`?id=...`).

Hiding an app only takes it off Home: it's still installed, and still opens
from **Settings → Apps**. To turn it off altogether, put it on the
[disable list](#debloat).

### Setup

Building it needs two things the other scripts don't:

1. Java 17:

   ```sh
   brew install --cask temurin@17
   ```

   Homebrew's `openjdk@17` formula works too.

2. The Android SDK platform and build tools, installed with `sdkmanager`:

   ```sh
   brew install --cask android-commandlinetools
   sdkmanager "platforms;android-36" "build-tools;35.0.0"
   ```

   `bin/home` finds an SDK in `$ANDROID_HOME`, `~/Library/Android/sdk` (Android
   Studio's) or Homebrew's `android-commandlinetools`.

### Build and install

```sh
bin/home
```

It builds the app, signs it, and installs it with `adb install -r`. Run it
again after changing the app to update it.

The app is signed with a key in `~/.config/tcl-os/home.keystore`, with its
password in `home.keystore.password` next to it. The first run creates both.
Neither is ever in the repo. The app stores nothing, so if the key is lost,
`bin/home` can't update the installed app: uninstall it with
`adb -s <tv-ip>:5555 uninstall io.github.james_c_bc.home`, then run
`bin/home` again.

### Unit tests

```sh
cd home && ./gradlew test
```

They run on the Mac's JVM and cover the logic that isn't UI (the order of
the grid and reading `apps.txt`, and picking the HDMI inputs by port). Unlike `bin/home`, Gradle doesn't go looking for Java and the
SDK, so point it at them first if `java -version` isn't 17 or `ANDROID_HOME`
is unset:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## Tests

```sh
test/run.sh
```

The tests run the scripts against a fake `adb` in `test/stub/`, so they don't
need a TV. `bin/home` runs against a fake Gradle and `keytool` too, so they
don't need Java or the SDK either. The Home app has its own
[unit tests](#unit-tests).
