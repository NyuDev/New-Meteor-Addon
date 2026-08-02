# New

A [Meteor Client](https://meteorclient.com) addon, built from the official
[addon template](https://github.com/MeteorDevelopment/meteor-addon-template) and compiled
for every Minecraft version Meteor supports, from **1.20.1** to **26.1.2**.

The whole addon is written against **Mojang's official mappings**. Minecraft 26.x ships
unobfuscated, and every older version is deobfuscated with the same official mappings at
build time, so one source tree covers all twelve targets with no per-version name juggling.

## Modules

### AutoMoss

Applies bone meal to moss blocks around you, but only when it will actually convert
something. The point is not to spam bone meal; it is to never waste a single one.

#### How moss bone mealing really works

Bone mealing a moss block runs `MossBlock#performBonemeal`, which places the
`moss_patch_bonemeal` vegetation patch feature at the block *above* the moss. Three rules
come out of that, and AutoMoss checks all three:

1. **The moss needs air directly above it.** `MossBlock#isValidBonemealTarget` requires the
   block above to be air. Not "not solid" - air. A slab, a torch, water, tall grass, any of
   them make the moss an invalid target. This matters more than it sounds: wild moss is
   nearly always carpeted in grass, moss carpet or azalea, so most of it is unusable until
   something clears it. That is what `clear-obstructions` is for.

2. **Stone only converts if it has air above it too.** The patch walks each column around
   the origin, drops down through air, and replaces the first block it lands on. A block
   that is buried never gets reached, so it never converts. Checking only for "stone
   nearby" would burn bone meal on a wall of buried stone.

3. **Blocks that are already moss are not a gain.** `VegetationPatchFeature#placeGround`
   skips a column whose ground block is already the block it wants to place. AutoMoss does
   not count those, because the bone meal would still be consumed to grow decorative
   vegetation and convert nothing.

4. **The patch never touches its corner columns.** `placeGroundPatch` skips them outright,
   and only places the edge columns with `extraEdgeColumnChance` (0.75). So a moss block
   whose only nearby stone sits diagonally converts nothing, no matter how many times you
   bone meal it. AutoMoss does not count corners.

Because the patch rolls its radius and its edge columns at random, no prediction is exact.
When a target has been bone mealed four times without becoming useless, AutoMoss gives up on
it and blacklists it for a minute - otherwise the module would pour items into one block
forever, which from the outside just looks like the bot standing still.

That third point is the reason a "moss has air above it" check on its own is not enough.
Bone meal is consumed whenever the patch places, even if the only thing it does is sprout
grass on moss that was already there. So AutoMoss confirms at least `min-conversions`
columns would genuinely turn into moss before it spends an item.

Convertible blocks are the `#minecraft:moss_replaceable` tag: the stone family
(`#base_stone_overworld` - stone, granite, diorite, andesite, tuff, deepslate) and the dirt
family. Set `stone-only` if you want to count stone alone.

#### Settings

| Setting | Default | What it does |
| --- | --- | --- |
| `range` | 4.5 | How far to look for moss, measured from your eyes. |
| `patch-radius` | 1 | Horizontal radius counted as convertible. See the note below. |
| `min-conversions` | 1 | Blocks that must actually convert before spending a bone meal. |
| `stone-only` | off | Count only `#base_stone_overworld`, ignoring the dirt family. |
| `delay` | 4 | Ticks between two actions. |
| `pause-on-killaura` | on | Stop entirely while KillAura is active. |
| `rotate` | on | Face the block before acting on it. |
| `swap-back` | on | Return to your previous hotbar slot after each bone meal. |
| `swing` | on | Swing your hand client-side; off still sends the swing packet. |
| `auto-refill` | on | Move bone meal up from your inventory when the hotbar runs out. |
| `place-moss` | off | Put moss blocks from your hotbar down next to exposed stone. |

`place-moss` turns carried moss into new work: it looks for an air pocket that has a floor
under it, air above it, and stone around it that would convert, then puts a moss block there
to bone meal. This is the answer to standing in a cave where every moss block is already
surrounded by moss and nothing is worth an item.

`auto-refill` only ever targets an **empty** hotbar slot, so nothing you are carrying gets
displaced - in practice that is the slot the last stack ran out from.

**Obstructions**

| Setting | Default | What it does |
| --- | --- | --- |
| `clear-obstructions` | on | Break whatever is covering a moss block so it becomes usable. |

Only blocks that break **instantly** are touched - grass, ferns, flowers, azalea bushes.
Anything that would mean actually digging is left alone, so this will not chew through a
slab or a stone floor. A block is only cleared when the moss underneath would be worth bone
mealing afterwards, so it does not strip decoration for nothing.

Carpets are skipped on purpose. They are cheap but not instant, so the module would stall
part-way through breaking one instead of getting on with the next target.

**Azalea**

| Setting | Default | What it does |
| --- | --- | --- |
| `grow-azalea` | off | Occasionally bone meal an azalea bush into an azalea tree. |
| `azalea-interval` | 30s | Seconds between two azalea attempts. |
| `azalea-spacing` | 4 | Skip a bush when azalea leaves are already this close. 0 disables. |

Off by default, because every bone meal spent on a tree is one not spent converting stone.
Vanilla's `isBonemealSuccess` for azalea only succeeds about **45%** of the time, so a bush
normally takes a few attempts - that is vanilla, not a bug. A bush is skipped unless it has
five air blocks above it, so bone meal is not wasted under a ceiling.

When `grow-azalea` is on, azalea bushes are no longer treated as obstructions - otherwise
the two options would fight over the same block.

**Baritone**

| Setting | Default | What it does |
| --- | --- | --- |
| `baritone` | off | Walk to moss worth working on when nothing is in reach. |
| `search-chunks` | 4 | Radius in chunks that Baritone's scanner sweeps for moss. |
| `cluster-radius` | 6 | How far around a spot still counts as the same patch. |
| `min-cluster` | 4 | Blocks a patch must be worth to count as a patch. |
| `rescan-cooldown` | 3s | Floor on how often the search may run. Not the driver - see below. |
| `explore` | on | Head somewhere new when nothing nearby is worth working on. |
| `explore-distance` | 64 | How far to strike out when exploring, in blocks. |

> **This needs the Meteor fork of Baritone** (Fabric mod id `baritone-meteor`), not official
> Baritone. With official Baritone, or none at all, the option simply stays idle and the rest
> of the module works normally.

Turned on, AutoMoss becomes a bot: when there is nothing in reach it asks Baritone's own
chunk scanner for nearby moss, keeps only the positions that would actually convert
something, and heads for the **nearest patch** rather than the nearest single block. A patch
is the total work within `cluster-radius` of a spot; anything reaching `min-cluster` counts.
Lone blocks are only used as a fallback when there is no patch at all. Pathing is cancelled
the instant real work appears, so walking never fights the bone mealing.

**Retargeting is driven by events, not by a clock.** Two things hand Baritone a new
destination on the tick they happen:

- the last target in reach is finished, so the spot is done;
- Baritone arrives, or gives up on its path.

`rescan-cooldown` is only a floor to stop the scanner being hammered when there is genuinely
nothing to find. It does not pace the bot.

Two things keep it from getting stuck, which matters more than raw speed:

- A spot it walked to and found nothing at is ignored for a minute, so it does not ping-pong
  between the same dead ends.
- The search **widens** before giving up, out to 16 chunks. Striking out on a blind heading
  while there is still convertible stone a bit further away is what makes a bot look lost.
- Only then does `explore` sweep it somewhere new. It **keeps scanning while it walks** and
  abandons the sweep the moment real work turns up, so it never marches past convertible
  stone. The heading turns only 40 degrees after a leg that found nothing, so it fans
  outward rather than doubling back across ground it just covered.

The addon carries **no Baritone dependency at all** - the bridge is pure reflection against
the `baritone.api` package, which the Meteor fork leaves unminified. That is what lets the
same source compile for Minecraft 1.20.1, which has no Baritone build at all.

**Render**

| Setting | Default | What it does |
| --- | --- | --- |
| `render` | on | Highlight the block currently targeted. |
| `shape-mode` | Both | How the highlight is drawn. |
| `side-color` / `line-color` | green | The bone meal target. |
| `clear-side-color` / `clear-line-color` | orange | A block about to be cleared away. |

**Debug**

| Setting | Default | What it does |
| --- | --- | --- |
| `debug` | off | Log what the scan finds to the game log. |
| `debug-interval` | 20 | Ticks between two log lines. |

**About `patch-radius`:** the feature rolls its horizontal radius randomly between 1 and 2
per use. Radius 1 is what every bone meal is guaranteed to reach, so it is the default and
the setting that never wastes. Radius 2 also counts the outer ring, which converts roughly
half the time - faster in a dense farm, slightly wasteful.

Bone meal has to be in your hotbar. AutoMoss swaps to it, uses it, and swaps back.

#### If it looks like nothing is happening

The highlight only draws when there is a target, so "no box" and "no bone meal" are the
same symptom, not two. Turn `debug` on and watch the game log:

```
[AutoMoss] moss=12 withAir=0 obstructed=11 tooPoor=0 | target=null clear=... azalea=null
```

- `moss=0` - no moss in `range` at all.
- `withAir=0` with `obstructed` high - the moss is covered. Enable `clear-obstructions`.
- `tooPoor` high - the moss is valid but nothing around it would convert. Everything nearby
  is already moss, or the stone is buried with no air above it. Try `patch-radius` 2, or
  lower `min-conversions`.

#### Performance

The module is built so a tick costs as little as possible:

- Search offsets are precomputed once per range change and sorted by distance with a single
  primitive `long[]` sort, so scans walk outward from the player and stop at the first
  usable target - which is by construction the closest one.
- Nothing is allocated per tick. Two reusable `MutableBlockPos` cover the entire scan.
- Rejections are ordered cheapest-first: reach check, then the block, then air above, and
  only then the column walk.
- The column walk stops the moment it has confirmed `min-conversions` blocks.
- On cooldown ticks the scan is skipped entirely unless the highlight needs refreshing.
  Turn `render` off and the module does nothing at all between uses.

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
| 26.1 | 26.1.2 | 25 |
| 26.1.1 | 26.1.2 | 25 |
| 26.1.2 | 26.1.2 | 25 |

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer) and
   [Meteor Client](https://meteorclient.com/download) for your Minecraft version.
2. Drop the matching `new-<version>+mc<mc>.jar` into your `mods` folder next to Meteor.
3. Launch the game. The modules appear under the **New** category in the Meteor GUI.

The addon requires Meteor Client. It will not load on its own.

## Building

```bash
./gradlew build
```

That builds the version currently selected by Stonecutter. To build every supported
version at once:

```bash
./gradlew chiseledBuild
```

Jars land in `versions/<mc>/build/libs/`. On Windows, `build.cmd` wraps `gradlew` with the
JDK and socket settings this project needs - use `build.cmd build` the same way.

To launch a dev client with Meteor and this addon already loaded, **scope the task to one
version**:

```bash
./gradlew :1.21.11:runClient
```

A bare `runClient` propagates to every Stonecutter subproject and starts about a dozen
Minecraft clients at once.

Building the 26.x targets needs a JDK 25 installed; Gradle's toolchain resolver finds it
automatically. Everything else builds on JDK 21.

## License

MIT. See [LICENSE](LICENSE).
