# tv-os

Scripts for managing a TCL Android TV from a Mac over Wi-Fi ADB.

Target: TCL RT51M chassis, Android TV 11 (not Google TV), firmware
`V8-R51MT05-LF1V620.021315`, security patch March 2024.

Everything is plain bash. The only dependency is `adb`.

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
| `bin/check`   | Prints the TV's model, Android version, firmware, security patch and Home activity, and the state of every package on the disable list. |
| `bin/backup`  | Snapshots every package's state to `backups/<tv-name>/<timestamp>.txt`.      |
| `bin/restore` | Re-enables packages that were enabled in a snapshot. Never disables anything. |
| `bin/debloat` | Backs up, then disables the packages on `lists/disable.txt`. Never uninstalls. |

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

Keep the disable list conservative. Before you add a package, find out what it
does, and run the test checklist below afterwards.

### Test checklist

Run this on the TV after each debloat:

- [ ] HDMI input switching works
- [ ] HDMI-CEC controls the soundbar/console
- [ ] Chromecast casting to the TV works
- [ ] Voice search works
- [ ] A streaming app plays

## Tests

```sh
test/run.sh
```

The tests run the scripts against a fake `adb` in `test/stub/`, so they don't
need a TV.
