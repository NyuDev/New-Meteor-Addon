package fr.nyuway.newaddon.modules.elytra;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
    public final Setting<Boolean> autoTakeoff;
    public final Setting<Boolean> requireSilkTouch;
    public final Setting<Integer> voidClearance;
    public final Setting<Double> searchRadius;
    public final Setting<Boolean> hotbarFirst;
    public final Setting<Boolean> pauseOnKillAura;
    public final Setting<Boolean> debug;
    public final Setting<Boolean> disconnectWhenDone;
    public final Setting<Double> arrivalRadius;
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
