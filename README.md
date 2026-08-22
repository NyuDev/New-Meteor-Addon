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

## AutoBreak

Switch it on and **right-click a block**. That block's *kind* becomes the job — not that one
block — and from then on Baritone walks you to the nearest one and it comes down, the way
AutoMoss walks to the nearest moss. A kind is picked rather than typed because you are standing
in front of the thing you want gone, and naming it would mean knowing what it is called. If
right-clicking it would do something else — a chest, a door, a crafting table — bind `pick-key`
and look at it instead.

| Setting | Default | |
| --- | --- | --- |
| `mode` | SpeedMine | `Vanilla` breaks one at a time; `SpeedMine` starts two and lets both finish. |
| `pick-key` | none | Takes the block you are looking at, instead of right-clicking it. |
| `rotate` | off | Turn to face each block. |
| `swing` | on | Swing your arm; off sends the swing as a packet instead. |
| `range` | 4.5 | How far a block can be and still be broken. |
| `hold-ms` | 200 | (SpeedMine) How long to hold each of the two blocks. |
| `grace-ms` | 500 | (SpeedMine) Margin on top of the time the block should have taken. |
| `release` | off | (SpeedMine) Let go of the button before waiting. |
| `walk` | on | Use Baritone to reach the next one. |
| `walk-radius` | 2 | How close Baritone has to get before this takes over. |
| `search-chunks` | 8 | How far to look for more. |
| `y-range` | 32 | How far up or down to consider. |
| `no-spleef` | on | Never break the block holding you up. |
| `stop-on-teleport` | on | Switch off when you are moved somewhere you did not walk to. |
| `teleport-distance` | 16 | How far in one tick counts as being moved rather than walking. |

### SpeedMine

Hold the button on one block for a moment — a fifth of a second is plenty — then on the other for
the same moment, and then **stop and wait** for both to come down on their own.

**The waiting is the technique.** Going back to poke at the blocks is what stops them from ever
getting there, so once both have been started nothing is sent at all until they break or they are
overdue. Overdue is not a number picked in advance: it is worked out from the blocks themselves —
the same figure the breaking bar is filled from, for the tool actually in your hand — plus
`grace-ms` for latency. Netherrack and obsidian are the same job to this module and nothing alike
to a pickaxe.

**Nothing is sent by hand.** No packets are built, on purpose: these are the game's own two calls,
the ones a held mouse button makes. That is the whole point, because the client working the other
block beside you is what makes the pair work, and it is watching for a player doing an ordinary
thing. For the same reason nothing **rotates by default** — a player who turns to face each block
in turn looks nothing like one who does not, and the two clients have to look alike.

It also cannot use Meteor's breaking helper, which lets go of a block on the first tick it is not
asked to break it again. That is right for one block at a time and fatal here: letting go is how
you tell the server to forget about a block, and the wait exists precisely so that it does not.
`release` is that letting-go, offered for a server that wants it, and off for the same reason.

Either half can vanish at any moment — broken by you, by the other client, or by somebody else —
so both are checked every tick. If nothing comes down after three pairs it says so once; that is
usually the wrong tool rather than the wrong timing.

**`no-spleef`** refuses any block under your feet, across your whole footprint, since standing on
the edge of two blocks means either could be the one holding you up. Everything else is fair game
— a job whose blocks happen to be the floor is common, and the only part of that floor worth
protecting is the part currently supporting somebody.

## StasisPull

Asks a stasis bot to pull you home. Pulls are spaced 5s apart client-side.

**Off means off.** While the module is switched off, nothing can ask a bot for anything: not
the keys, not the buttons, not StasisProtection's escape pull, not AutoStasisPull. One switch,
and it is the module's own.

| Setting | Default | |
| --- | --- | --- |
| `bots` | 1 | How many bots you have. Raising it adds a section to configure the new one in. |
| `notify` | on | Says a pull was requested, and what the bot answered. |
| `debug` | off | Writes the trigger word, bot name and endpoint to the log. |

### One section per bot

Each bot gets its own section, `Bot 1` to `Bot 8`, and is configured whole in it:

| Setting | Default | |
| --- | --- | --- |
| `name` | `bot-1` | What to call it. Used by `.stasis` and on its button. |
| `default` | first bot | Use this one when nothing says otherwise. Ticking one unticks the others. |
| `key` | none | Pulls with this bot. Read only while the module is on. |
| `mode` | Chat | `Chat`, `Whisper`, or `Http`. |
| `messages` | `!home` | (`Chat`, `Whisper`) Trigger words; one is picked at random per pull. |
| `whisper-command` | `/msg` | (`Whisper`) `/msg`, `/w`, `/tell`. |
| `bot-name` | | (`Whisper`) Account the whisper goes to. |
| `endpoint` | `http://localhost:6969` | (`Http`) Full URL of this bot's control server. |
| `secret` | | (`Http`) Shared secret, identical to this bot's. Masked on screen. |

The mode is per bot, so one pearl can be answered by a StasisBot on a box you own over an
encrypted HTTP frame and the next by a spare account you whisper, with different trigger words.
The fields that do not apply to the chosen mode are not drawn.

The **default** bot — the ticked one — is what everything automatic uses: StasisProtection's
escape pull, and AutoStasisPull. The tick is exclusive, so choosing one is choosing rather than
adding.

Under the settings there is a row per bot with a **Pull** button, because a key you have to
bind before you can test a bot is a key you bind before you know the bot works.

A handful of things are quicker from chat, and a Meteor macro on `.stasis pull <name>` gives a
key to a ninth bot:

```
.stasis list             # each bot, how it is set up, and what any of them is missing
.stasis pull base        # fire a named one
.stasis default home     # change which one is the default
```

Meteor has no repeatable settings group, which is why the count is a slider rather than an
"add" button: the eight sections always exist, and Meteor drops a section whose settings are
all hidden, so the ones past the count are simply not drawn. Bots configured as text lines by
an earlier version are read in once, on first use, and turned into sections.

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
| `ignore-lag` | on | Do not treat a teleport that is the server catching up as a stasis pull. |
| `lag-radius` | 8 | How close to somewhere you just were still counts as being put back. |
| `lag-freeze` | 700ms | A gap this long since the last tick means the server stalled. |

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
- **Server lag is not an ambush.** A stalled server sends you back to where it last agreed you
  were, which arrives as one large jump and looks exactly like a pull. Two things separate
  them: the landing spot is somewhere you were standing within the last five seconds, and the
  ticks stopped arriving just before it. Either one is enough to let it through, which is the
  right way round - a missed rubber-band costs nothing, and a false ambush pulls you off
  whatever you were doing and burns a pearl.

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

The GUI is his, redrawn: the original is Forge 1.12.2 and draws with `GuiScreen` and
`Tessellator`, which exist on no version here, so the same windows — 17-pixel titlebars, the
same greys, the resize corner — go through one small canvas that hides the three places
Minecraft moved the API out from under them. Replies go through the server's own `/msg`, so
nobody else needs anything installed.

Conversations are keyed by UUID, not name — a rename does not split a thread, and a recycled
name does not merge two people.

Each row is the person's head, ringed green while they are on the server and grey while they
are not - offline players wear their default skin rather than vanishing - beside a name coloured
by relationship: white for a stranger, Meteor's `friend-color` for a friend, `enemy-color` for an
enemy, red for someone ignored.

The buddy list folds into four sections: **In render**, **Recent**, **On the server**, and
**Not seen in a while**. Nobody is listed twice, so they are filled in that order — whichever
fact about a person matters most decides where they sit. Being within reach comes first:
somebody standing next to you is under In render even if you were talking to them this morning,
because that section is what the list is glanced at for, and a person who is right there listed
under Recent is the right answer in the wrong place. Recent then beats the rest of the server,
because a conversation you have had beats a name you have never spoken to. Every section keeps
its unread badge, and each remembers whether you folded it shut.

A conversation carries a heart to friend them, a skull to make them an
enemy, a speaker to mute and a barred circle to ignore, each lit while its toggle is on.

A whisper slides an advancement-style toast in from the top-right, showing the sender and a
preview of what they said; the sound and the toast are independent, so either can carry the
alert alone. **Both fire whatever you are looking at** — in a chest, in the pause menu, or with
this window itself open on somebody else's conversation. Whether you are told is a setting, not
a guess about what you were doing. The toast is drawn by a mixin on the method that wraps a
whole screen's render, so it lands on top of the screen rather than behind it, which is where
the HUD would have put it.

The screen also remembers which window had focus and puts it back in front when you reopen it —
unless something arrived while you were away, in which case that conversation wins. A window
jumping in front of a message you have not read yet is worse than no memory at all.

Anything unread carries a yellow count beside the name in the list, and **opens by itself when
you open the menu** — the people who wrote to you are the reason you opened it, so you should
not then have to find them. A conversation counts as read when you pick it up, by clicking it or
its name, and not when the screen put it there. Every window remembers where it was left and how
big it was, across restarts.

**Muted and ignored are exempt from both**, in two strengths. A window that appears because
somebody wrote to you is a notification, and both settings say do not notify me about this
person — so neither opens by itself any more. Muting keeps its yellow count: you asked to be
left alone, not to be kept in the dark, so it waits in the list instead of putting itself in
front of you, which is the whole reason to mute rather than ignore. Ignoring drops the count as
well — a yellow number beside a name you have decided not to read is the one thing an ignore was
supposed to stop. The number is kept rather than cleared, so un-ignoring gives back what came in
before.

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
| `notify-sound` | on | Sound when a whisper lands with the window closed. |
| `notify-toast` | on | Advancement-style toast, top-right, sender and preview, with the window closed. |
| `incoming` / `outgoing` | vanilla + common | Detection patterns, on top of Livemessage's own `patterns/*.txt`. |

## 2b2t.vc

[api.2b2t.vc](https://api.2b2t.vc) keeps what the server itself does not: how long someone has
played, when they were first and last seen, what the queue is doing, who has priority. `.2b2t`
asks it a question at a time and prints the answer in chat.

```
.2b2t queue                     .2b2t stats <player>
.2b2t online                    .2b2t seen <player>
.2b2t time                      .2b2t playtime <player>
.2b2t limit                     .2b2t deaths | kills | chats | connections <player>
.2b2t word <word>               .2b2t prio <player>
.2b2t top playtime | month | deaths | kills
.2b2t refresh
```

`queue` also prints an ETA, worked out from the equation the API publishes rather than one
hard-coded here.

**On being a good guest.** The API is run by one person, for free, and publishes no rate limit —
which is a reason to be careful, not a licence not to be. Requests go out one at a time on one
thread with a floor on the gap between them (a second by default), every answer is cached for as
long as it can still be true, and a "no data" is cached too so the one player the API has never
heard of does not get asked about sixty times a second. Requests identify themselves with a
User-Agent naming this addon, so whoever runs the API can see what the traffic is and block it if
it ever becomes a nuisance.

The **module** is what looks things up on its own, and where the limits above are set. With it
on, the message window puts a short version beside "online" — how long they have played, whether
they have priority — and **clicking their head opens a panel** with the rest: the UUID in full,
both playtimes, first and last seen, joins, deaths, kills, chats. Click the head again to close
it. By default none of this happens unless you are actually connected to 2b2t, since it is that
server's data and nobody else's. The command works whether the module is on or off: you asked
for that one.

### Markers

`{queue}` and its friends, written into any text field and replaced with the value at the moment
it is sent — which is the point of having one on a sign, where it is written once and read for
years. A backslash sends it as written: `\{queue}` arrives as `{queue}`. `.2b2t markers` lists
them all with what each is worth right now.

```
{queue} {queue_prio} {eta}          {online} {prio_count} {bot_count}
{mctime}  {me}                      {playtime} {playtime_month}
{firstseen} {lastseen}              {deaths} {kills}
```

Chat, commands and signs. Values come from the same cache as everything else, so sending a
message never waits on a web request — a marker whose answer has not arrived yet is sent as `?`
and will be there the next time. A marker that is not one of these is left exactly as typed: a
brace someone meant to type is far likelier than a marker misspelt, and eating it is the worse
of the two mistakes.

| Setting | Default | |
| --- | --- | --- |
| `request-interval` | 1000 ms | Floor on the gap between two requests. |
| `player-cache` / `queue-cache` | 10 min / 20 s | How long an answer is kept. |
| `only-on-2b2t` / `server-host` | on / `2b2t.org` | Only look up while connected there. Matched on the end of the address. |
| `show-in-messages` | on | The line under a name in the message window. |

## ElytraSwap

Puts a fresh elytra on before the one you are wearing gives out, so a long flight is not ended
by it. Ported from BepHax, defaults and all.

There is no packet for "put this in my chest slot" — what there is, is that *using* an elytra in
hand equips it and hands back whatever was there. So the swap is: get the spare onto the hotbar,
hold it, right-click, put the worn one where the spare came from. One click per stage, five
ticks apart, because each one is something the server has to agree with.

The moves are hotbar swaps — the click a number key makes. One packet, a destination we choose,
and it undoes itself: the same exchange that fetches the spare puts the old elytra in its place
and returns the borrowed hotbar slot to its owner. Totems, gapples, pearls and chorus fruit are
never the slot borrowed.

**Combat protection** (off by default) is the other half: an elytra is no armour at all, so
being hit puts a chestplate on for a few seconds and then goes back to the elytra. Another hit
starts the clock over.

| Setting | Default | |
| --- | --- | --- |
| `durability-threshold` | 10 | Swap once the worn elytra has less than this left. |
| `only-while-flying` | off | Only swap while actually flying. |
| `pause-in-inventory` | on | Do nothing while a container is open. |
| `swap-cooldown` | 100 | Ticks before looking again after a swap. |
| `notify-swap` | on | Say in chat when one is swapped. |
| `swap-on-hit` | off | Wear a chestplate when something hits you. |
| `protection-duration` | 60 | Ticks to keep it on. Another hit restarts it. |
| `auto-swap-back` / `prioritize-netherite` | on / on | Return to the elytra after; prefer netherite over diamond. |

## Friends and enemies

Meteor ships a friend list and no opposite, so this adds one: `.enemy add <name>`, `remove`,
`list`, `clear`, kept in `meteor-client/new-addon/enemies.txt` as `Name=uuid`.

**A name to add them, an id to keep them.** A name is what you have when you mark somebody — it
is what the command takes and what chat carries, and marking a player you have only heard of is
half the point. An id is what *stays*. So the id is not required, it is learned: everyone in the
tab list is checked once a second, and the first time an enemy is seen theirs is attached to the
entry. After that the id decides. A rename is followed automatically and the entry rewritten, and
a name later taken by somebody else no longer carries the mark — the entry answers for that id
and nobody else. `.enemy list` says, per entry, whether they are here and whether their id is
known yet.

**The two are exclusive.** Making someone an enemy takes them off Meteor's friend list, and
friending someone takes them off the enemy list — however it was done, including `.friend add`
and Meteor's own Friends tab, which a watcher reconciles once a second. Nobody is both, so no
part of the client has to guess which colour you meant.

**The enemy list is sent to your other clients whole, on join.** Friends have a sync command —
one line, and the other client re-reads Meteor's list itself. Enemies live in a file only this
addon knows about, so there is nothing to point anybody at, and only changes made while both
clients were running ever crossed: everyone marked before that stayed unknown on the other side
indefinitely, while sitting in `.enemy list` here. FriendSync's `enemy-sync-on-join` now replays
the list, paced like everything else. An add for somebody already marked is harmless.

**`.friends add` takes people who are offline.** Meteor's takes a tab-list entry, so a name
that is not connected right now comes back as "player list entry with name X doesn't exist" —
even though its own Friends tab has never had the restriction and resolves the UUID from Mojang
afterwards. A mixin appends a second `add` that takes a plain name; Brigadier merges it onto
Meteor's own node and tries Meteor's first, so someone who *is* on the server still gets their
real UUID from the tab list for free, and only the names that would have failed reach ours.

Its colour sits with Meteor's own, in **Config → Visual → `enemy-color`**, right under
`friend-color`, and it is read fresh each frame: change the swatch and the names and the skull
follow immediately. Enemies are coloured **in the tab list** too, the way Meteor colours friends
— which is the one place you look before deciding whether to land.

## Allies

`.ally add <name>`, `remove`, `list`, `clear`, kept in `meteor-client/new-addon/allies.txt` the
same way the enemy list is: `Name=uuid`, ids learned from the tab list, renames followed.

**An ally is a friend, with a reason.** Not a third relationship — an ally *is* on Meteor's
friend list, and everything that protects a friend protects them, because that is the point of
the arrangement. What this list adds is why. A friend is somebody you know; an ally is somebody
your group has an agreement with, who you may never have spoken to and may not speak to again.
Both mean do not shoot. They do not mean the same thing when you are deciding whether to say
where you are.

So `add` friends them as well, and `remove` leaves the friendship — the opposite of an ally is a
plain friend, not a stranger, and `.friends remove` is how somebody stops being protected.
Marking an ally takes them off the enemy list, and losing the friendship by any route drops the
tag, since a tag on somebody who is not a friend is a claim about nothing. Not during a
FriendBypass, though: that empties the list on purpose and puts it back, which is the one case
where an ally who is momentarily not a friend is exactly what was meant.

Its colour sits in **Config → Visual → `ally-color`**, beside `friend-color` and `enemy-color`,
and defaults to the friend green darker. It applies everywhere the friend colour does — the tab
list, tracers, ESP, nametags, and the message window — and is decided *before* Meteor's own
friend branch, since otherwise an ally would simply be drawn as a friend and the distinction
would exist everywhere except where you look at it. A conversation carries a shield beside the
heart to mark one.

Nothing extra is sent to your other clients, and nothing needs to be: an ally is a friend, so
FriendSync's ordinary `;friend add` already tells them what they need in order to hold fire. The
tag itself is local.

## FriendBypass

KillAura, aim assists and everything else that respects friends will not touch one, which is
right until the moment you have agreed to fight. This takes the friend list aside for the
duration and puts it back after.

| Setting | Default | |
| --- | --- | --- |
| `announce` | on | Say how many were set aside and how many came back. |
| `silence-sync` | off | Keep the bypass to Meteor and tell no other client about it. |
| `restore-on-death` | on | Switch off and put everyone back when you die. |

**Only the friends who are actually on the server** are set aside, and anyone who logs in while
it is on is caught on the next tick. A friend list is years of people; a fight is the handful in
front of you, and they are the only entries that change anything.

**What you do by hand wins.** The list is not frozen: friend someone during a bypass and they
stay a friend afterwards, unfriend someone and they stay off. Only the people this module
removed, and who have not been touched since, are put back.

`silence-sync` is off because the other clients protect friends too — a bypass they are not
told about works in exactly one of the places it needs to. FriendSync paces what it sends, so a
list of fifty leaves steadily rather than all at once, which is the difference between a bypass
and a kick for spam.

## TempFriends

Somebody you have just met and are about to do something with is a friend for the next hour,
not the next year, but a friend list has only one kind of entry. `.tempfriend add <name>` (or
the button in LiveMessage's right-click menu) makes one that expires.

| Setting | Default | |
| --- | --- | --- |
| `forget-after` | 15 min | Minutes out of render before they are dropped. The clock restarts each time they are seen. |
| `maximum-minutes` | 0 | Longest one lasts however often they are seen. 0 is no limit. |
| `drop-on-leave` | on | Drop them all when you leave the server. |
| `announce` | on | Say when one is added and when one runs out. |

The list is written to `meteor-client/new-addon/temp-friends.txt` and read back at startup, so a
crash cannot leave strangers on the friend list for ever. Adding someone permanently keeps them,
and unfriending by hand ends it: anything you do yourself outranks the note kept here.

The module has to be **on** to add one — the timers live in its tick handler, so a temporary
friend added while it is off would be an ordinary friend under a name that says otherwise. Every
refusal says why, in chat.

## ChatProtect

A base is only a base until somebody types where it is, and that mistake is made in a hurry, in
a whisper meant for one person, and cannot be taken back once the packet is gone. So the message
simply does not go.

There is no "are you sure" on purpose: a confirmation gets answered in the same second and with
the same haste that typed the coordinates. Turning the module off to send is a deliberate second
thought rather than a reflex.

Every route out is covered — the chat box, commands (so `/msg` and `/w` are caught), and the
reply box in the message window, which sends through the client rather than the chat screen and
would otherwise walk straight past.

The judging is not one enormous regular expression. Numbers are found with a small pattern, then
a run of them standing together with nothing but separators between counts as a location when it
carries an x/y/z label or holds a number too big to be a count of something. `x3 y4 z5` is a
location; `i got 3 4 5 diamonds` is a sentence.

| Setting | Default | |
| --- | --- | --- |
| `block-coordinates` | on | Refuse messages that read like a location. |
| `numbers-in-a-row` | 2 | Two catches an x and z pair, which is how most are given. |
| `magnitude` | 100 | A number this big counts on its own; smaller ones need a label. |
| `check-chat` / `check-commands` / `check-messages` | on | Chat, slash commands, and the LiveMessage reply box. |
| `allowed-commands` | Baritone's | Never checked: they take coordinates by design and talk to your own client. |

## 2b2tInvFix

Two things the client does that 2b2t will not accept, stopped before it does them. Brought over
from BepHax, where they were worked out. Neither is a cheat: each one prevents a desync you pay
for with a kick or a ghosted item.

- **`prevent-full-container-clicks`** — a shift-click is dropped when the half of the window it
  would move the stack into has neither an empty slot nor a matching stack with room. Cancelled
  at the click, not at the packet: the client acts first and tells the server after, so stopping
  only the packet would leave the client showing a move the server never heard of.
- **`fix-unstackable-dragging`** — a drag carrying something unstackable is swallowed. A drag
  spreads the cursor stack over the slots it crosses, which a pickaxe cannot do; what you are
  left looking at is a ghost.

Off by default, and deliberately so: both are wrong on a normal server, where a shift-click into
a full container is simply a shift-click into a full container.

## FriendSync

Keeps one friend list across the clients you switch between. There is no shared file and no
event to hook, so it goes over the one channel every client already reads — chat. A change to
Meteor's list fires a template with `{name}` filled in; a template starting with Meteor's own
prefix is run locally instead of sent, so a client command never leaks to the server.

The enemy list rides along on the same channel, and changes to either one are sent as their own
command.

The **sync** goes out once, a few seconds after joining a world, and only then. It is the right
thing for catching a client up with what it missed while it was closed — but a sync of this kind
adds what it finds and cannot know about anyone you *removed*, so a removal is never sent that
way. That is what `on-remove` is for.

| Setting | Default | |
| --- | --- | --- |
| `on-add` / `on-remove` | `;friend add {name}` / `;friend remove {name}` | A friend added or removed. |
| `on-enemy-add` / `on-enemy-remove` | `;enemy add {name}` / `;enemy remove {name}` | The same for the enemy list. |
| `on-sync` | `;friend sync meteor` | Makes the other client re-read the whole list, on join only. |
| `sync-on-join` | on | Send it a few seconds after joining a world. |
| `log` | off | Say in chat what was sent. |

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
| `settle-ticks` | 20 | Ticks of standing genuinely still before anything is placed. Touching the ground is not the same as having stopped. |
| `make-room` | on | Drop one junk stack at your feet when the pack is full, so a broken shulker has somewhere to go. |
| `open-inventory` | on | Put the inventory screen up before rearranging the hotbar, the way a player would. |
| `require-silk-touch` | on | Refuse to place the chest without one, or it breaks to obsidian. |
| `void-clearance` | 2 | Solid blocks required under the spot before placing anything. |
| `search-radius` | 4 | How far around you to look for somewhere to set up. |
| `fireworks-to-hotbar` | on | Put fireworks on the bar, where Baritone can actually use them. |
| `trigger-key` | unbound | Start a resupply on the spot, while the module is on. |
| `arrival-radius` | 150 | How close to the destination counts as arrived, for `disconnect-when-done`. |
| `auto-relaunch` / `relaunch-delay` | on / 30 | Get airborne again after an accidental landing short of the destination. |
| `climb` | on | After taking off again, keep re-issuing the same goal until you are at cruising height. |
| `cruise-height` | 120 | The Y to climb to before leaving the flight alone. |
| `climb-interval` | 20 | Ticks between two goals while climbing. |
| `climb-timeout` | 900 | Ticks of climbing before flying on at whatever height was reached. |
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

### Climbing back to cruising height

Baritone's elytra process gains altitude when it is handed its destination afresh: it plans the
next leg from where it is *now*, and from low down the only way through is up. Left alone after a
resupply it settles into whatever height that first plan happened to find — which is ground level,
and a flight at ground level meets every hill between here and there.

So after taking off again the module hands it **the same goal** once a second until Y reaches
`cruise-height`, then stops. The repetition is the whole mechanism, and it is also the reason to
stop: a solver asked to replan every second never gets to finish thinking about the long way.
The climb also ends if you land, or after `climb-timeout` — a ceiling or a mountain can make the
target height unreachable, and never arriving is not a reason to never leave.

### The chat and the pause menu do not stop it

Opening chat or pressing Escape used to stall a resupply half way through. The inventory moves
put the player's own inventory screen up first — not needed by the protocol, only so that a
stream of slot clicks comes from somewhere a player could have produced it — and they refused to
run while any other screen was up, waiting for a screen that was never coming. Same for the
bone-meal crafting grid, which asked for no screen at all rather than asking the one question
that matters, which is whether the open *menu* is still the player's own.

Both now carry on. The clicks work either way, so when a screen you opened is up the appearance
is skipped and the work is done — closing the chat somebody is typing into, to put up an
inventory nobody asked for, would be the worse surprise. A chest or shulker being open still
stops them, because that is a menu the run is in the middle of using.

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
