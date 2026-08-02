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
