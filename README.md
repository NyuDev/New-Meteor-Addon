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
   them make the moss an invalid target.

2. **Stone only converts if it has air above it too.** The patch walks each column around
   the origin, drops down through air, and replaces the first block it lands on. A block
   that is buried never gets reached, so it never converts. Checking only for "stone
   nearby" would burn bone meal on a wall of buried stone.

3. **Blocks that are already moss are not a gain.** `VegetationPatchFeature#placeGround`
   skips a column whose ground block is already the block it wants to place. AutoMoss does
   not count those, because the bone meal would still be consumed to grow decorative
   vegetation and convert nothing.

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
| `delay` | 4 | Ticks between two bone meals. |
| `rotate` | on | Face the moss block before using the item. |
| `swap-back` | on | Return to your previous hotbar slot after each use. |
| `swing` | on | Swing your hand client-side; off still sends the swing packet. |
| `render` | on | Highlight the moss block currently targeted. |

**About `patch-radius`:** the feature rolls its horizontal radius randomly between 1 and 2
per use. Radius 1 is what every bone meal is guaranteed to reach, so it is the default and
the setting that never wastes. Radius 2 also counts the outer ring, which converts roughly
half the time - faster in a dense farm, slightly wasteful.

Bone meal has to be in your hotbar. AutoMoss swaps to it, uses it, and swaps back.

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

Building the 26.x targets needs a JDK 25 installed; Gradle's toolchain resolver finds it
automatically. Everything else builds on JDK 21.

## License

MIT. See [LICENSE](LICENSE).
