# New

[![Build](https://github.com/NyuDev/New-Meteor-Addon/actions/workflows/build.yml/badge.svg)](https://github.com/NyuDev/New-Meteor-Addon/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NyuDev/New-Meteor-Addon?sort=semver)](https://github.com/NyuDev/New-Meteor-Addon/releases/latest)
[![License](https://img.shields.io/github/license/NyuDev/New-Meteor-Addon)](LICENSE)

[Meteor Client](https://meteorclient.com) addon, one source tree for Minecraft **1.20.1 to
26.1.2** on Mojang official mappings.

## Install

Grab the jar for your version from the [latest release](https://github.com/NyuDev/New-Meteor-Addon/releases/latest),
drop it in `mods` next to Meteor Client.

## AutoMoss

Bone meals moss only when it would actually convert stone/dirt nearby - checks the real
vanilla rules (air above the moss, air above the target block, corners never convert,
already-moss doesn't count) instead of spamming.

| Setting | Default | |
| --- | --- | --- |
| `range` | 4.5 | Search radius from your eyes. |
| `patch-radius` | 1 | 1 = guaranteed reach, 2 = also counts the ~50% edge columns. |
| `min-conversions` | 1 | Minimum blocks that must convert before spending an item. |
| `stone-only` | off | Ignore the dirt family. |
| `delay` | 4 | Ticks between actions. |
| `pause-on-killaura` | on | Stop while KillAura is active. |
| `rotate` / `swap-back` / `swing` | on | Interaction behaviour. |
| `auto-refill` | on | Refill hotbar bone meal from inventory. |
| `auto-disable` | on | Turn off when totally out of bone meal. |
| `place-moss` | off | Place carried moss next to exposed stone to create work. |
| `render` / `shape-mode` / colors | on | Target highlight. |
| `debug` / `debug-interval` | off | Log scan counters to diagnose an idle module. |

**Obstructions** - `clear-obstructions` (on): breaks whatever covers usable moss, but only
instant-break blocks (grass, ferns, flowers, azalea). Carpets are skipped (not instant).

**Azalea** - `grow-azalea` (off), `azalea-interval` (30s), `azalea-spacing` (4): occasionally
grows azalea bushes into trees. Off by default since it competes with stone conversion.

**Baritone** - `baritone` (off), `search-chunks` (4), `cluster-radius` (6), `min-cluster` (4),
`rescan-cooldown` (3s), `explore` (on), `explore-distance` (64): walks to the nearest worthwhile
patch of moss, event-driven retargeting (not timer-based), sweeps outward when nothing is in
range. **Requires Meteor's Baritone fork** (`baritone-meteor`), not official Baritone - stays
idle otherwise. No Baritone dependency at build time (reflection bridge), works even on
1.20.1 where no Baritone build exists.

If nothing seems to happen, turn `debug` on and read the log line - it tells you exactly
which check is rejecting everything.

## StasisPull

Asks a stasis bot to pull you home. Acts as a **button**: bind a key, press it, one request
goes out and the module switches straight back off. Pulls are spaced 5s apart client-side.

| Setting | Default | |
| --- | --- | --- |
| `mode` | Chat | `Chat`, `Whisper`, or `Http`. |
| `notify` | on | Prints what was sent and what the bot answered. |
| `messages` | `!home` | Trigger words; one is picked at random per pull. |
| `whisper-command` | `/msg` | Whisper command (`/msg`, `/w`, `/tell`). |
| `bot-name` | | Account the whisper goes to. |
| `endpoint` | `http://localhost:6969` | Full URL of the bot's control server. |
| `secret` | | Shared secret, identical to the bot's. Masked on screen. |

The account pulled is always the one you are logged in as.

`Http` speaks [StasisBot](https://github.com/NyuDev/StasisBot)'s encrypted control channel:
an AES-256-GCM sealed `HOMEREQ` frame POSTed to `/ctl`, with a 45s replay window. Nothing
goes through the game server, so there is nothing to see, log, or rate-limit.

The secret is masked in the settings panel, but Meteor still stores every setting in plain
text in its config. Do not reuse a password there.

## AutoStasisPull

Same idea as Meteor's AutoLog, different ending: instead of dropping the connection and
leaving your body in the world, it pulls you somewhere safe and you stay logged in. Firing
is delegated to StasisPull, so whichever transport you set there is what gets used.

| Setting | Default | |
| --- | --- | --- |
| `disable-autolog` | on | Switch Meteor's AutoLog off, so you aren't pulled *and* disconnected. |
| `toggle-off` | on | Turn off after a pull instead of firing repeatedly. |
| `health` / `health-level` | on / 8 | Pull at or below this health. |
| `count-absorption` | on | Count golden-apple absorption as health. |
| `totem` / `totem-mode` | on / Remaining | `Remaining` checks supply after a pop; `PopsInWindow` counts pops in a time window. |
| `totem-remaining` | 3 | (`Remaining`) Pull when a pop leaves you with this many totems or fewer. |
| `totem-window-pops` / `totem-window-seconds` | 3 / 300 | (`PopsInWindow`) This many pops within this many seconds. |
| `players` / `player-range` | off / 16 | Pull when a non-friend gets this close. |

Both totem modes require an actual pop (vanilla `ENTITY_EVENT` id 35), never a bare inventory
scan. `Remaining` recounts your totems (hotbar, storage, armor, offhand) a couple of ticks
after the pop, since the server's slot-update packet is not guaranteed to have landed by the
time the pop event itself is processed.

Separate module rather than a patch into AutoLog's panel: hooking Meteor's own module would
have to survive twelve Meteor versions, and `AutoLog` has already moved package between them.

## StasisProtection

Refuses to be teleported into an ambush. The attack: someone finds your base, finds the
stasis chamber you left there, and fires it — you get yanked out of whatever you were doing
and dropped in front of them, on ground they prepared.

The defence is a **consent key**. Hold it and any teleport is fine, whoever is at the far
end. Let go, and an unexpected teleport landing you next to someone who is *not* on your
Meteor friends list gets answered.

| Setting | Default | |
| --- | --- | --- |
| `consent-key` | none | Hold to accept being teleported. |
| `reaction` | Pull | `Pull` (needs StasisPull configured) or `Disconnect`. |
| `notify` | on | Say what was detected and what was done. |
| `trust-own-pull` / `own-pull-grace` | on / 30s | Trust a teleport you asked StasisPull for. |
| `teleport-distance` | 32 | Blocks moved in one tick that count as a teleport. |
| `danger-range` | 8 | How close a stranger must be to count as an ambush. |
| `watch-seconds` | 2.5 | How long to keep watching after landing. |

Two details worth knowing:

- **It watches for a moment instead of deciding instantly.** The server sends your new
  position before the entities around it, so checking on the landing tick would usually see
  an empty world and never fire.
- **A pull you requested yourself is trusted**, so pulling home does not trip your own alarm
  — and neither does the escape pull this module fires, which would otherwise loop.

Teleport detection compares your position tick to tick rather than reading the position
packet, whose API was reworked in 1.21.2. A world change (portal, respawn, reconnect) resets
the tracking instead of counting as a teleport.

## Supported versions

| Minecraft | Meteor | Java |
| --- | --- | --- |
| 1.20.1 | 0.5.4 | 21 |
| 1.20.4 | 0.5.6 | 21 |
| 1.21.1 | 0.5.8 | 21 |
| 1.21.3 | 0.5.9 | 21 |
| 1.21.4 | 1.21.4 | 21 |
| 1.21.5 | 1.21.5 | 21 |
| 1.21.8 | 1.21.8 | 21 |
| 1.21.10 | 1.21.10 | 21 |
| 1.21.11 | 1.21.11 | 21 |
| 26.1 / 26.1.1 / 26.1.2 | 26.1.2 | 25 |

## Building

```bash
./gradlew :1.21.11:build       # one version
./gradlew build                # all versions
./gradlew :1.21.11:runClient   # dev client (always scope it - a bare runClient boots all 12)
./gradlew collectJars          # all versions -> build/jars/
```

Windows: `build.cmd` wraps `gradlew` with the JDK/socket settings this machine needs.

## Releasing

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Tag must match `mod_version` in `gradle.properties` or the release workflow refuses it.
Builds all 12 versions and attaches them to a GitHub release.

## License

MIT. See [LICENSE](LICENSE).
