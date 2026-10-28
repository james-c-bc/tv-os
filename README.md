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
| `bin/check`   | Prints the TV's model, Android version, firmware, security patch and Home activity. |

## Tests

```sh
test/run.sh
```

The tests run the scripts against a fake `adb` in `test/stub/`, so they don't
need a TV.
