package fr.nyuway.newaddon.modules.moss;

import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Every knob AutoMoss exposes.
 *
 * <p>Held apart from the module so the scanning and bone mealing logic is not buried under
 * two hundred lines of builders. A configuration record, with no behaviour of its own.
 *
 * <p>Assignment order matters and is preserved: several settings gate their visibility on
 * ones declared before them.
 */
public final class MossSettings {

    public final Setting<Double> range;
    public final Setting<Integer> patchRadius;
    public final Setting<Integer> minConversions;
    public final Setting<Boolean> convertDirt;
    public final Setting<Integer> delay;
    public final Setting<Boolean> pauseOnKillAura;
    public final Setting<Boolean> rotate;
    public final Setting<Boolean> silentRotations;
    public final Setting<Boolean> swapBack;
    public final Setting<Boolean> autoRefill;
    public final Setting<Boolean> autoDisable;
    public final Setting<Boolean> swing;
    public final Setting<Boolean> placeMoss;
    public final Setting<Boolean> airPlace;
    public final Setting<Boolean> clearObstructions;
    public final Setting<Boolean> growAzalea;
    public final Setting<Integer> azaleaInterval;
    public final Setting<Integer> azaleaSpacing;
    public final Setting<Boolean> baritone;
    public final Setting<Integer> searchChunks;
    public final Setting<Integer> clusterRadius;
    public final Setting<Integer> minCluster;
    public final Setting<Integer> rescanDelay;
    public final Setting<Boolean> explore;
    public final Setting<Integer> exploreDistance;
    public final Setting<Boolean> render;
    public final Setting<ShapeMode> shapeMode;
    public final Setting<SettingColor> sideColor;
    public final Setting<SettingColor> lineColor;
    public final Setting<SettingColor> clearSideColor;
    public final Setting<SettingColor> clearLineColor;
    public final Setting<Boolean> debug;
    public final Setting<Integer> debugInterval;

    public MossSettings(Settings settings) {
        SettingGroup sgGeneral = settings.getDefaultGroup();
        SettingGroup sgClear = settings.createGroup("Obstructions");
        SettingGroup sgAzalea = settings.createGroup("Azalea");
        SettingGroup sgBaritone = settings.createGroup("Baritone");
        SettingGroup sgRender = settings.createGroup("Render");
        SettingGroup sgDebug = settings.createGroup("Debug");

        range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("How far to look for moss blocks, measured from your eyes.")
            .defaultValue(4.5).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0)
            .build());

        patchRadius = sgGeneral.add(new IntSetting.Builder()
            .name("patch-radius")
            .description("Horizontal radius counted as convertible. The feature rolls 1 or 2 at " +
                         "random, so radius 1 is what every bone meal is guaranteed to reach; " +
                         "2 also counts columns that only convert about half the time.")
            .defaultValue(1).min(1).max(2)
            .build());

        minConversions = sgGeneral.add(new IntSetting.Builder()
            .name("min-conversions")
            .description("Minimum number of blocks that must actually turn into moss before " +
                         "spending a bone meal. Raise it to trade speed for efficiency.")
            .defaultValue(1).min(1).max(8).sliderMin(1).sliderMax(8)
            .build());

        convertDirt = sgGeneral.add(new BoolSetting.Builder()
            .name("convert-dirt")
            .description("Also count the dirt family (#dirt), which the patch converts just like " +
                         "stone. Off counts only stone (#base_stone_overworld).")
            .defaultValue(false)
            .build());

        delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks to wait between two actions.")
            .defaultValue(4).min(0).max(20).sliderMin(0).sliderMax(20)
            .build());

        pauseOnKillAura = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-killaura")
            .description("Stop while KillAura is actually fighting something, so hotbar swaps and " +
                         "rotations never fight with combat. Merely having KillAura switched on " +
                         "does not pause anything.")
            .defaultValue(true)
            .build());

        rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Face the block before acting on it.")
            .defaultValue(true)
            .build());

        silentRotations = sgGeneral.add(new BoolSetting.Builder()
            .name("silent-rotations")
            .description("Send the rotation to the server without turning your camera. The " +
                         "server sees you facing the block either way; this only decides " +
                         "whether you watch it happen.")
            .defaultValue(true)
            .visible(rotate::get)
            .build());

        swapBack = sgGeneral.add(new BoolSetting.Builder()
            .name("swap-back")
            .description("Return to your previous hotbar slot after each bone meal.")
            .defaultValue(true)
            .build());

        autoRefill = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-refill")
            .description("When the hotbar runs out of bone meal, move a stack up from your " +
                         "inventory into a free hotbar slot.")
            .defaultValue(true)
            .build());

        autoDisable = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-disable")
            .description("Turn the module off once there is no bone meal left anywhere in your " +
                         "inventory, instead of idling and scanning for nothing.")
            .defaultValue(true)
            .build());

        swing = sgGeneral.add(new BoolSetting.Builder()
            .name("swing")
            .description("Swing your hand client-side. Off still sends the swing packet.")
            .defaultValue(true)
            .build());

        placeMoss = sgGeneral.add(new BoolSetting.Builder()
            .name("place-moss")
            .description("Put moss blocks from your inventory down next to exposed stone, making " +
                         "a spot that converts where there was nothing to work with. Needs moss " +
                         "blocks on you; does nothing otherwise.")
            .defaultValue(false)
            .build());

        airPlace = sgGeneral.add(new BoolSetting.Builder()
            .name("air-place")
            .description("Allow placing a moss block with nothing to click against. A vanilla " +
                         "server rejects this, since the packet names a supporting block that is " +
                         "not there; leave it off unless you know yours accepts it.")
            .defaultValue(false)
            .visible(placeMoss::get)
            .build());

        clearObstructions = sgClear.add(new BoolSetting.Builder()
            .name("clear-obstructions")
            .description("Break the grass, carpet or plant sitting on a moss block when that moss " +
                         "would otherwise be worth bone mealing. Only blocks that break instantly " +
                         "are touched, so this never digs through real blocks.")
            .defaultValue(true)
            .build());

        growAzalea = sgAzalea.add(new BoolSetting.Builder()
            .name("grow-azalea")
            .description("Occasionally bone meal an azalea bush into an azalea tree. Off by " +
                         "default: every bone meal spent here is one not spent converting stone.")
            .defaultValue(false)
            .build());

        azaleaInterval = sgAzalea.add(new IntSetting.Builder()
            .name("azalea-interval")
            .description("Seconds between two azalea attempts. Vanilla only succeeds about 45% of " +
                         "the time, so a bush usually takes a few tries.")
            .defaultValue(15).min(1).max(300).sliderMin(5).sliderMax(120)
            .visible(growAzalea::get)
            .build());

        azaleaSpacing = sgAzalea.add(new IntSetting.Builder()
            .name("azalea-spacing")
            .description("Skip a bush when azalea leaves are already within this many blocks, so " +
                         "trees do not crowd each other. 0 disables the check.")
            .defaultValue(3).min(0).max(8).sliderMin(0).sliderMax(8)
            .visible(growAzalea::get)
            .build());

        baritone = sgBaritone.add(new BoolSetting.Builder()
            .name("baritone")
            .description("Walk to moss worth working on when nothing is in reach, turning the " +
                         "module into a bot. Needs Meteor's Baritone fork (mod id baritone-meteor); " +
                         "official Baritone will not work.")
            .defaultValue(false)
            .build());

        searchChunks = sgBaritone.add(new IntSetting.Builder()
            .name("search-chunks")
            .description("Radius in chunks that Baritone's scanner sweeps looking for moss.")
            .defaultValue(4).min(1).max(16).sliderMin(1).sliderMax(8)
            .visible(baritone::get)
            .build());

        clusterRadius = sgBaritone.add(new IntSetting.Builder()
            .name("cluster-radius")
            .description("How far around a spot still counts as the same patch of work. Bigger " +
                         "values make the bot judge a whole area rather than one block.")
            .defaultValue(6).min(1).max(16).sliderMin(2).sliderMax(12)
            .visible(baritone::get)
            .build());

        minCluster = sgBaritone.add(new IntSetting.Builder()
            .name("min-cluster")
            .description("Blocks a patch must be worth before it counts as a real patch. The bot " +
                         "goes to the nearest patch that clears this, and only falls back to lone " +
                         "blocks when there is no patch at all.")
            .defaultValue(4).min(1).max(32).sliderMin(1).sliderMax(16)
            .visible(baritone::get)
            .build());

        rescanDelay = sgBaritone.add(new IntSetting.Builder()
            .name("rescan-cooldown")
            .description("Floor on how often the search may run, in seconds. Retargeting is driven " +
                         "by events - finishing a spot, or arriving - so this only stops the " +
                         "scanner being hammered when there is genuinely nothing to find.")
            .defaultValue(3).min(1).max(30).sliderMin(1).sliderMax(15)
            .visible(baritone::get)
            .build());

        explore = sgBaritone.add(new BoolSetting.Builder()
            .name("explore")
            .description("When nothing nearby is worth working on, head somewhere new instead of " +
                         "standing still. This is what keeps the bot covering ground.")
            .defaultValue(true)
            .visible(baritone::get)
            .build());

        exploreDistance = sgBaritone.add(new IntSetting.Builder()
            .name("explore-distance")
            .description("How far to strike out when exploring, in blocks.")
            .defaultValue(64).min(16).max(256).sliderMin(32).sliderMax(160)
            .visible(() -> baritone.get() && explore.get())
            .build());

        render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Highlight the block currently being targeted.")
            .defaultValue(true)
            .build());

        shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the highlight is drawn.")
            .defaultValue(ShapeMode.Both)
            .visible(render::get)
            .build());

        sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("Fill colour of the bone meal target.")
            .defaultValue(new SettingColor(89, 204, 108, 40))
            .visible(render::get)
            .build());

        lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("Outline colour of the bone meal target.")
            .defaultValue(new SettingColor(89, 204, 108, 190))
            .visible(render::get)
            .build());

        clearSideColor = sgRender.add(new ColorSetting.Builder()
            .name("clear-side-color")
            .description("Fill colour of a block about to be cleared away.")
            .defaultValue(new SettingColor(225, 145, 55, 40))
            .visible(render::get)
            .build());

        clearLineColor = sgRender.add(new ColorSetting.Builder()
            .name("clear-line-color")
            .description("Outline colour of a block about to be cleared away.")
            .defaultValue(new SettingColor(225, 145, 55, 190))
            .visible(render::get)
            .build());

        debug = sgDebug.add(new BoolSetting.Builder()
            .name("debug")
            .description("Log what the scan is finding to the game log. On by default: when this " +
                         "module looks idle the log is the only thing that can say whether moss " +
                         "was seen at all, and which check rejected it.")
            .defaultValue(true)
            .build());

        debugInterval = sgDebug.add(new IntSetting.Builder()
            .name("debug-interval")
            .description("Ticks between two log lines (20 ticks = 1 second).")
            .defaultValue(20).min(5).max(200).sliderMin(10).sliderMax(100)
            .visible(debug::get)
            .build());

        /** Search offsets around the player, sorted nearest-first. Rebuilt when range changes. */
    }
}
