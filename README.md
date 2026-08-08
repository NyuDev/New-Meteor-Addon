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
| `patch-radius` | 1 | 1 = guaranteed reach, 2 = also counts the ~50% edge columns. |
| `min-conversions` | 1 | Minimum blocks that must convert before spending an item. |
| `convert-dirt` | off | Also count the dirt family, which converts like stone. |
| `delay` | 4 | Ticks between actions. |
| `pause-on-killaura` | on | Stop while KillAura is actually fighting something. |
| `rotate` / `swap-back` / `swing` | on | Interaction behaviour. Off places blocks at the angle you are already holding. |
| `silent-rotations` | on | Face the block without turning your camera; the server sees the same rotation either way. |
| `auto-refill` | on | Refill hotbar bone meal from inventory. |
| `pause-while-using` | on | Stop while eating or drinking; a hotbar swap cancels those. |
| `pause-in-gui` | off | Also stop while any screen is open. Crafting waits either way. |
| `auto-disable` | on | Turn off when totally out of bone meal. |
| `place-moss` | off | Place carried moss next to stone no existing patch can reach. |
| `air-place` | off | Allow placing with nothing to click against. Vanilla servers reject it. |
| `scan-range` | 4.5 | How far to look, from your eyes. What is considered, not what may be touched. |
| `vanilla-reach` | on | Limit breaking and placing to the range the client allows. Bone mealing is unaffected. |
| `break-place-reach` | 3.5 | Your own limit, eyes to nearest point of the block. Shown when `vanilla-reach` is off. |
| `escape-stuck` | on | Break out when a block closes around you, head first. |
| `pause-while-stuck` | on | Do nothing else until free. |
| `craft-bone-meal` | off | Craft bone blocks into bone meal in the 2x2 grid, nothing opened. |
| `craft-below` / `craft-delay` | 64 / 20 | Craft while under this much bone meal, this many ticks apart. |
| `craft-from-bones` | on | Also use plain bones, three bone meal each. |
| `2b2t-safe` | on | Empty the grid after every craft, so a death never drops what is in it. |
| `render` / `shape-mode` / colors | on | Target highlight. |
| `debug` / `debug-interval` | on | Log scan counters to diagnose an idle module. |

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
| `void` | on | Pull the instant you are low enough to take void damage. |
| `void-y-auto` | on | Work the height out from the dimension rather than a fixed number. |
| `void-y` | -128 | Manual height, used when `void-y-auto` is off. |
| `health` / `health-level` | on / 8 | Pull at or below this health. |
| `count-absorption` | on | Count golden-apple absorption as health. |
| `totem` / `totem-mode` | on / Remaining | `Remaining` checks supply after a pop; `PopsInWindow` counts pops in a time window. |
| `totem-remaining` | 3 | (`Remaining`) Pull when a pop leaves you with this many totems or fewer. |
| `totem-window-pops` / `totem-window-seconds` | 3 / 300 | (`PopsInWindow`) This many pops within this many seconds. |
| `players` / `player-range` | off / 16 | Pull when a non-friend gets this close. |

**The void trigger is checked before every other one and is not gated by anything**, because
void damage kills straight through a totem — there is nothing to weigh up.

The height is dimension-dependent, not one fixed number: vanilla starts void damage 64 below
the world floor, so roughly **-128 in the Overworld** (floor -64) but **-64 in the Nether and
the End** (floor 0). `void-y-auto` works that out for you.

> Firing exactly on the damage line is likely **too late in practice**. A pull is a round trip
> to your bot while void damage is already ticking. If you want this to actually save you,
> turn `void-y-auto` off and set `void-y` well above the damage line — around **-40** in the
> Overworld — so it fires during the fall instead.

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
| `trust-own-pearl` / `own-pearl-grace` | on / 6s | Trust a teleport after you threw an ender pearl. |
| `teleport-distance` | 32 | Blocks moved in one tick that count as a teleport. |
| `danger-range` | 8 | How close a stranger must be to count as an ambush. |
| `watch-seconds` | 2.5 | How long to keep watching after landing. |
| `totem-trigger` | on | A totem pop during the watch window is an ambush by itself. |

A few details worth knowing:

- **Your own ender pearls do not trip it.** A pearl looks identical to a stasis pull on the
  wire - a sudden 30+ block jump - so without this every pearl throw would read as an
  ambush. Detected from the `ServerboundUseItemPacket` *you* send when right-clicking with a
  pearl in hand, not from tracking the thrown entity (whose package differs between Minecraft
  versions and would have needed per-version code). The grace window is consumed the moment
  it explains a teleport, so a second, real ambush landing seconds after your pearl lands is
  still caught.
- **A totem pop is trusted on its own**, without also needing a stranger detected nearby -
  whoever hit you hard enough to need it may be shooting from outside `danger-range`.

- **It watches for a moment instead of deciding instantly.** The server sends your new
  position before the entities around it, so checking on the landing tick would usually see
  an empty world and never fire.
- **A pull you requested yourself is trusted**, so pulling home does not trip your own alarm
  — and neither does the escape pull this module fires, which would otherwise loop.

Teleport detection compares your position tick to tick rather than reading the position
packet, whose API was reworked in 1.21.2. A world change (portal, respawn, reconnect) resets
the tracking instead of counting as a teleport.

## LiveMessage

Whispers kept as conversations instead of lines that scroll away. This is rebane2001's
[Livemessage](https://github.com/rebane2001/livemessage) (Unlicense) brought over to Meteor:
its storage layout, keyed the way it keys it, in the place it puts it —
`meteor-client/livemessage/{messages,settings,patterns}`, one `<player-uuid>.jsonl` per
conversation. Existing history opens straight away, and this can run alongside another
Livemessage port without the two disagreeing.

Its GUI could not come over: the original is Forge 1.12.2 and draws with `GuiScreen` and
`Tessellator`, which do not exist on any version here. That layer is Meteor widgets instead.
Replies go through the server's own `/msg`, so nobody else needs anything installed.

Conversations are keyed by UUID, not name — a rename does not split a thread, and a recycled
name does not merge two people.

There is no flag on the wire saying "this is a whisper" — vanilla renders the line from a
translation key and every server with its own format sends something else. So detection is by
pattern, and the patterns are settings: an unrecognised server needs one line added, not a new
build. Group 1 is the other person, group 2 is the text.

| Setting | Default | |
| --- | --- | --- |
| `open-key` | unbound | Opens the message window, while the module is on. |
| `send-command` | `/msg` | How a reply is sent. |
| `hide-from-chat` | off | Keep matched whispers out of the chat feed. |
| `announce` | on | Say in chat when a new conversation starts. |
| `incoming` / `outgoing` | vanilla + common | Detection patterns, on top of Livemessage's own `patterns/*.txt`. |

## ElytraResupply

Baritone's elytra process lands when it runs out of fireworks or the elytra wears out — on a
long crossing that means coming back to a stranded character. This notices the landing, sets
up on the spot, tops back up, clears every trace, and sends Baritone on to the same
destination.

What you already carry is spent first, so a bag with XP bottles in it often finishes the job
with nothing placed at all. Only what is still missing opens storage: place an ender chest →
take out a shulker → place it → take XP bottles and fireworks → throw the bottles at your
feet to mend the elytra → put leftovers back in the same shulker → break it → return it to
the slot it came from → break the ender chest with Silk Touch → pick it up.

| Setting | Default | |
| --- | --- | --- |
| `min-fireworks` / `target-fireworks` | 8 / 192 | Resupply below the first, carry away the second. |
| `min-elytra-durability` | 80 | Mend once remaining durability drops below this. |
| `xp-bottles` | 128 | Bottles to take out per mending session; leftovers go back. |
| `require-silk-touch` | on | Refuse to place the chest without one, or it breaks to obsidian. |
| `void-clearance` | 2 | Solid blocks required under the spot before placing anything. |
| `search-radius` | 4 | How far around you to look for somewhere to set up. |
| `fireworks-to-hotbar` | on | Put fireworks on the bar, where Baritone can actually use them. |
| `trigger-key` | unbound | Start a resupply on the spot, while the module is on. |
| `arrival-radius` | 150 | How close to the destination counts as arrived, for `disconnect-when-done`. |
| `auto-relaunch` / `relaunch-delay` | on / 30 | Get airborne again after an accidental landing short of the destination. |
| `void-guard` / `void-margin` | on / 32 | Land on solid ground before the flight sinks past the void damage line, then carry on. |
| `use-carried-first` | on | Spend what you already have before opening anything. |
| `empty-hand-to-open` | on | Hold an empty slot to open a container; some servers require it. |
| `look-down` | off | Point at the ground when nothing else needs looking at. |
| `hold-position` | on | Walk back to the setup block when something shoves you off it. |
| `pause-on-killaura` | on | Freeze mid-run while fighting, resume from the same phase. |
| `action-delay` | 4 | Ticks between actions. Instant clicks look nothing like a player. |
| `container-settle` | 10 | Ticks to wait after a container opens before reading it. |
| `auto-takeoff` | on | Jump and open the elytra after resupplying. |
| `disconnect-when-done` | off | Disconnect once the trip ends, or when it cannot continue. |
| `release-on-input` | on | Hand control back the instant you press a movement key. |
| `silent-rotations` | on | Face each block without turning your camera. See below. |
| `debug` | **on** | Logs every phase transition. Leave it on until you trust it. |

The Silk Touch tool is found **anywhere in your inventory**, pulled into the hotbar when the
chest needs breaking, and put back in its original slot afterwards.

Dyed shulker boxes are matched too — each colour is a separate item, so checking for plain
`SHULKER_BOX` alone makes a full ender chest read as empty.

**Requires Meteor's Baritone fork**, and the elytra must carry **Mending** — XP bottles only
repair through that enchantment.

#### Playing legit

Every interaction - opening the chest, breaking it, breaking the shulker - faces the block
first and only acts once that rotation is in flight. Placements pick the face turned toward
the player rather than always the top. A server that tracks whether your rotation actually
points at what you click will not see anything else here.

`silent-rotations` decides only whether your own camera turns to match - the server is told
the same rotation either way, so this is comfort, not the thing that makes it legit.

Every step is a server round trip (a block must appear, a container must open, an item must
be collected), so this is a state machine with a timeout per phase. On any timeout it runs
its cleanup path rather than stopping where it is, so a failure does not leave your ender
chest sitting in the open.

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
