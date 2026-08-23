package fr.nyuway.newaddon.modules.elytra;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.misc.Keybind;

/**
 * Every knob ElytraResupply exposes.
 *
 * <p>Held apart from the module so the state machine reads as behaviour rather than being
 * buried under a hundred lines of builders. Fields are public and final: this is a
 * configuration record, not something with logic of its own.
 */
public final class ResupplySettings {

    public final Setting<Integer> minFireworks;
    public final Setting<Integer> targetFireworks;
    public final Setting<Integer> minElytraDurability;
    public final Setting<Integer> xpBottles;

    public final Setting<Integer> actionDelay;
    public final Setting<Integer> containerSettle;
    public final Setting<Integer> settleTicks;
    public final Setting<Boolean> makeRoom;
    public final Setting<Boolean> openInventory;
    public final Setting<Boolean> autoTakeoff;
    public final Setting<Boolean> requireSilkTouch;
    public final Setting<Integer> voidClearance;
    public final Setting<Double> searchRadius;
    public final Setting<Boolean> hotbarFirst;
    public final Setting<Boolean> pauseOnKillAura;
    public final Setting<Boolean> debug;
    public final Setting<Boolean> disconnectWhenDone;
    public final Setting<Double> arrivalRadius;
    public final Setting<Boolean> voidGuard;
    public final Setting<Integer> voidMargin;
    public final Setting<Boolean> landOnRestart;
    public final Setting<java.util.List<String>> restartWarnings;
    public final Setting<Boolean> lookDownOnRestart;
    public final Setting<Boolean> resumeAfterRestart;
    public final Setting<Integer> resumeDelay;
    public final Setting<Boolean> verifySupplies;
    public final Setting<Integer> verifyTicks;
    public final Setting<Boolean> autoRelaunch;
    public final Setting<Integer> relaunchDelay;
    public final Setting<Boolean> climb;
    public final Setting<Integer> cruiseHeight;
    public final Setting<Integer> climbInterval;
    public final Setting<Integer> climbTimeout;
    public final Setting<Boolean> releaseOnInput;
    public final Setting<Boolean> silentRotations;
    public final Setting<Keybind> triggerKey;
    public final Setting<Boolean> useCarriedFirst;
    public final Setting<Boolean> emptyHandToOpen;
    public final Setting<Boolean> lookDown;
    public final Setting<Boolean> holdPosition;

    public ResupplySettings(Settings settings) {
        SettingGroup sgGeneral = settings.getDefaultGroup();
        SettingGroup sgTriggers = settings.createGroup("Triggers");

        minFireworks = sgTriggers.add(new IntSetting.Builder()
            .name("min-fireworks")
            .description("Resupply once you are down to this many fireworks.")
            .defaultValue(8).min(0).max(128).sliderMin(0).sliderMax(64)
            .build());

        targetFireworks = sgTriggers.add(new IntSetting.Builder()
            .name("target-fireworks")
            .description("How many fireworks to carry away. Several stacks: one is barely a leg " +
                         "of a long crossing, and the whole point is not to land again shortly.")
            .defaultValue(320).min(1).max(1024).sliderMin(64).sliderMax(640)
            .build());

        minElytraDurability = sgTriggers.add(new IntSetting.Builder()
            .name("min-elytra-durability")
            .description("Mend the elytra once its remaining durability drops below this.")
            .defaultValue(80).min(1).max(400).sliderMin(10).sliderMax(300)
            .build());

        xpBottles = sgTriggers.add(new IntSetting.Builder()
            .name("xp-bottles")
            .description("How many XP bottles to take out for a mending session. Leftovers go " +
                         "back in the shulker, so taking plenty costs nothing.")
            .defaultValue(256).min(1).max(1024).sliderMin(64).sliderMax(640)
            .build());

        actionDelay = sgGeneral.add(new IntSetting.Builder()
            .name("action-delay")
            .description("Ticks between two actions. Firing container clicks and placements as " +
                         "fast as the client allows looks nothing like a player and gives the " +
                         "server no time to answer.")
            .defaultValue(4).min(0).max(40).sliderMin(0).sliderMax(20)
            .build());

        containerSettle = sgGeneral.add(new IntSetting.Builder()
            .name("container-settle")
            .description("Ticks to wait after a container opens before reading it. Contents arrive " +
                         "in a packet after the menu itself, so reading straight away sees an " +
                         "empty chest.")
            .defaultValue(10).min(0).max(60).sliderMin(2).sliderMax(30)
            .build());

        settleTicks = sgGeneral.add(new IntSetting.Builder()
            .name("settle-ticks")
            .description("Ticks of standing genuinely still before anything is placed. Touching " +
                         "the ground is not the same as having stopped: an elytra landing slides " +
                         "for a while afterwards, and a chest placed during that slide is left " +
                         "behind. Twenty ticks is a second.")
            .defaultValue(20).min(0).max(200).sliderMin(0).sliderMax(60)
            .build());

        openInventory = sgGeneral.add(new BoolSetting.Builder()
            .name("open-inventory")
            .description("Put the inventory screen up before rearranging the hotbar, the way a " +
                         "player would. Costs nothing on the wire - your own inventory is always " +
                         "open server-side - but a run of slot clicks from a client with no " +
                         "inventory showing is not something a player can produce.")
            .defaultValue(true)
            .build());

        makeRoom = sgGeneral.add(new BoolSetting.Builder()
            .name("make-room")
            .description("Throw away one stack of junk when the pack is full, so a broken " +
                         "shulker has somewhere to go. Dropped straight down, at your feet, and " +
                         "picked back up once a slot frees. Only cobblestone, dirt and the like " +
                         "are ever dropped; with none of that on you, nothing is.")
            .defaultValue(true)
            .build());

        autoTakeoff = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-takeoff")
            .description("Jump and open the elytra after resupplying. Baritone is handed the " +
                         "destination but will not get you off the ground by itself.")
            .defaultValue(true)
            .build());

        requireSilkTouch = sgGeneral.add(new BoolSetting.Builder()
            .name("require-silk-touch")
            .description("Refuse to place the ender chest unless a Silk Touch pickaxe is on you. " +
                         "Without one the chest breaks into obsidian and is lost.")
            .defaultValue(true)
            .build());

        voidClearance = sgGeneral.add(new IntSetting.Builder()
            .name("void-clearance")
            .description("Solid blocks that must sit under the spot before anything is placed, so " +
                         "nothing you drop falls into the void or lava.")
            .defaultValue(2).min(1).max(16).sliderMin(1).sliderMax(8)
            .build());

        searchRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("search-radius")
            .description("How far around you to look for somewhere to set up. Must stay within " +
                         "reach, since the blocks have to be clicked.")
            .defaultValue(4.0).min(1.5).max(5.0).sliderMin(2.0).sliderMax(5.0)
            .build());

        hotbarFirst = sgGeneral.add(new BoolSetting.Builder()
            .name("fireworks-to-hotbar")
            .description("Put fireworks in the hotbar before the main inventory. Baritone can only " +
                         "fly with what it can reach, so a stack buried in storage is no use.")
            .defaultValue(true)
            .build());

        pauseOnKillAura = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-killaura")
            .description("Freeze wherever it is while KillAura is fighting, and pick up from the " +
                         "same phase afterwards.")
            .defaultValue(true)
            .build());

        triggerKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("trigger-key")
            .description("Press to start a resupply on the spot, without waiting to be landed " +
                         "by a shortage. Does nothing while the module is off.")
            .defaultValue(Keybind.none())
            .build());

        useCarriedFirst = sgGeneral.add(new BoolSetting.Builder()
            .name("use-carried-first")
            .description("Spend the XP bottles and fireworks you already have before opening " +
                         "anything. Often there is enough on you to finish the trip, and then " +
                         "no ender chest is placed at all.")
            .defaultValue(true)
            .build());

        emptyHandToOpen = sgGeneral.add(new BoolSetting.Builder()
            .name("empty-hand-to-open")
            .description("Hold an empty slot before opening a container. Some servers refuse " +
                         "the open outright when your hand is full.")
            .defaultValue(true)
            .build());

        lookDown = sgGeneral.add(new BoolSetting.Builder()
            .name("look-down")
            .description("Point at the ground whenever nothing else needs looking at. In the " +
                         "End that is the difference between a quiet resupply and a crowd of " +
                         "endermen; off, your view is left alone entirely.")
            .defaultValue(false)
            .build());

        holdPosition = sgGeneral.add(new BoolSetting.Builder()
            .name("hold-position")
            .description("Walk back to the block you set up on if something shoves you off it. " +
                         "The routine's own moves are not fought.")
            .defaultValue(true)
            .build());

        debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Log every phase transition and the detail that chat leaves out.")
            .defaultValue(false)
            .build());

        disconnectWhenDone = sgGeneral.add(new BoolSetting.Builder()
            .name("disconnect-when-done")
            .description("Disconnect once the trip ends or when unable to continue (waits until landed).")
            .defaultValue(false)
            .build());

        arrivalRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("arrival-radius")
            .description("How close to the destination counts as having arrived. Only used to " +
                         "decide whether a trip is finished; landing anywhere else is just a " +
                         "landing.")
            .defaultValue(150.0).min(8.0).max(2000.0).sliderRange(32.0, 512.0)
            .visible(disconnectWhenDone::get)
            .build());

        voidGuard = sgGeneral.add(new BoolSetting.Builder()
            .name("void-guard")
            .description("Put down on solid ground before the flight sinks into the void, then " +
                         "carry on. Baritone's elytra process will happily glide out under the " +
                         "islands, and below the damage line nothing saves you.")
            .defaultValue(true)
            .build());

        voidMargin = sgGeneral.add(new IntSetting.Builder()
            .name("void-margin")
            .description("Blocks above the void damage line at which to break off and land. " +
                         "The gap has to cover the descent itself, not just the moment of " +
                         "noticing.")
            .defaultValue(32).min(4).max(128).sliderRange(8, 64)
            .visible(voidGuard::get)
            .build());

        SettingGroup sgRestart = settings.createGroup("Restart");

        landOnRestart = sgRestart.add(new BoolSetting.Builder()
            .name("land-on-restart")
            .description("Put down as soon as the server announces a restart. Being in the air " +
                         "when it goes is the one moment where nothing can be done about " +
                         "anything - the ground is where a flight survives a restart.")
            .defaultValue(true)
            .build());

        restartWarnings = sgRestart.add(new StringListSetting.Builder()
            .name("restart-warnings")
            .description("Lines that mean a restart is coming. Matched anywhere in the message, " +
                         "so the countdown lines all match the one entry.")
            .defaultValue(java.util.List.of("Server restarting in"))
            .visible(landOnRestart::get)
            .build());

        lookDownOnRestart = sgRestart.add(new BoolSetting.Builder()
            .name("look-down")
            .description("Look at the ground once, after landing and coming to a stop. Once, " +
                         "not held: a client that keeps forcing a pitch is doing something no " +
                         "idle player does.")
            .defaultValue(true)
            .visible(landOnRestart::get)
            .build());

        resumeAfterRestart = sgRestart.add(new BoolSetting.Builder()
            .name("resume-after-restart")
            .description("Take off and carry on once the server is back and the world has " +
                         "settled. The destination is kept through the restart - the limbo the " +
                         "server parks you in is the same dimension, so nothing about the trip " +
                         "has actually changed.")
            .defaultValue(true)
            .visible(landOnRestart::get)
            .build());

        resumeDelay = sgRestart.add(new IntSetting.Builder()
            .name("resume-delay")
            .description("Ticks of a settled world before flying on. Long enough that the " +
                         "chunks are there and the server has stopped moving you about; every " +
                         "further world change starts it again.")
            .defaultValue(200).min(20).max(2400).sliderRange(40, 600)
            .visible(resumeAfterRestart::get)
            .build());

        verifySupplies = sgGeneral.add(new BoolSetting.Builder()
            .name("verify-supplies")
            .description("Count the fireworks again a moment after the run finishes, and go " +
                         "back for more if they are not there. 2b2t rolls an inventory back now " +
                         "and again: the client shows the stack it took, the server never " +
                         "agreed, and the difference only shows up as a flight that ends the " +
                         "moment it starts.")
            .defaultValue(true)
            .build());

        verifyTicks = sgGeneral.add(new IntSetting.Builder()
            .name("verify-ticks")
            .description("How long to wait before that second count. Long enough for a rollback " +
                         "to have arrived - it comes as an ordinary inventory update, a round " +
                         "trip after the click that caused it.")
            .defaultValue(40).min(5).max(200).sliderRange(10, 100)
            .visible(verifySupplies::get)
            .build());

        autoRelaunch = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-relaunch")
            .description("Get back in the air when you end up on the ground short of the " +
                         "destination. Baritone's elytra process does not take off by itself, " +
                         "so an accidental landing otherwise just leaves you standing there.")
            .defaultValue(true)
            .build());

        SettingGroup sgClimb = settings.createGroup("Climb");

        climb = sgClimb.add(new BoolSetting.Builder()
            .name("climb")
            .description("After taking off again, keep re-issuing the same flight goal until you " +
                         "are up at cruising height. Baritone's elytra process gains altitude " +
                         "when it is handed its destination afresh, so repeating the goal it " +
                         "already has is what lifts it - and once it is high the repetition " +
                         "stops, because a flight in the open sky does not need it.")
            .defaultValue(true)
            .build());

        cruiseHeight = sgClimb.add(new IntSetting.Builder()
            .name("cruise-height")
            .description("The Y to climb to before leaving the flight alone. Around 120 is high " +
                         "enough to be clear of everything that gets in the way and low enough " +
                         "to still be going somewhere.")
            .defaultValue(120).min(64).max(320).sliderRange(80, 200)
            .visible(climb::get)
            .build());

        climbInterval = sgClimb.add(new IntSetting.Builder()
            .name("climb-interval")
            .description("Ticks between two goals while climbing. Twenty is one a second; much " +
                         "faster is not more lift, it is only more work for Baritone's solver.")
            .defaultValue(20).min(2).max(200).sliderRange(5, 60)
            .visible(climb::get)
            .build());

        climbTimeout = sgClimb.add(new IntSetting.Builder()
            .name("climb-timeout")
            .description("Ticks of climbing before giving up and flying on at whatever height " +
                         "was reached. A ceiling, a mountain or a headwind can make the target " +
                         "height unreachable, and never arriving is not a reason to never leave.")
            .defaultValue(900).min(100).max(6000).sliderRange(200, 2400)
            .visible(climb::get)
            .build());

        relaunchDelay = sgGeneral.add(new IntSetting.Builder()
            .name("relaunch-delay")
            .description("Ticks on the ground before relaunching. Long enough that clipping a " +
                         "block mid-flight is not mistaken for having landed.")
            .defaultValue(30).min(5).max(200).sliderRange(10, 100)
            .visible(autoRelaunch::get)
            .build());

        releaseOnInput = sgGeneral.add(new BoolSetting.Builder()
            .name("release-on-input")
            .description("Turn the module off the instant you press a movement key mid-routine, " +
                         "handing full control straight back to you.")
            .defaultValue(true)
            .build());

        silentRotations = sgGeneral.add(new BoolSetting.Builder()
            .name("silent-rotations")
            .description("Send the rotation to the server without turning your camera. Every " +
                         "interaction faces its block either way - this only decides whether " +
                         "you watch it happen.")
            .defaultValue(true)
            .build());
    }
}
