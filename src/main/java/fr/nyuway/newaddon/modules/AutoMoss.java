package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * AutoMoss - bone meals moss blocks, but only when it actually converts something.
 *
 * <h2>What vanilla does</h2>
 * Bone mealing a moss block runs {@code MossBlock#performBonemeal}, which places the
 * {@code moss_patch_bonemeal} vegetation patch feature at the block <i>above</i> the moss:
 * <ul>
 *   <li>{@code MossBlock#isValidBonemealTarget} requires the block above the moss to be
 *       <b>air</b>. Any other block - not just a solid one - makes the moss an invalid
 *       target and the bone meal does nothing.</li>
 *   <li>The patch walks every column in a small radius around that origin, drops down
 *       through air, and converts the first block it lands on if it is in
 *       {@code #minecraft:moss_replaceable} (the stone and dirt families). A block with
 *       no air above it is never reached, so it never converts.</li>
 *   <li>{@code VegetationPatchFeature#placeGround} skips columns whose ground block is
 *       already moss. Those are not a gain, so this module does not count them.</li>
 * </ul>
 *
 * <h2>What this module adds</h2>
 * Bone meal is consumed whenever the patch places, even if it only grows decorative
 * vegetation on moss that is already there. So a moss block having air above it is
 * necessary but not sufficient - this module also verifies at least
 * {@link #minConversions} nearby columns would really turn into moss, and only then
 * spends an item.
 *
 * <h2>Cost</h2>
 * The search offsets are precomputed once per range change and sorted by distance, so a
 * scan walks blocks nearest-first and returns on the first usable target. Nothing is
 * allocated per tick, and the scan is skipped entirely on cooldown ticks unless the
 * highlight needs refreshing.
 */
public class AutoMoss extends Module {

    /**
     * {@code verticalRange} of the {@code moss_patch_bonemeal} feature: how far the patch
     * may travel up or down a column while looking for the surface to replace.
     */
    private static final int VERTICAL_RANGE = 5;

    /** Offsets are packed into a single int, so each component is biased into a byte. */
    private static final int PACK_BIAS = 64;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to look for moss blocks, measured from your eyes.")
        .defaultValue(4.5).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0)
        .build());

    private final Setting<Integer> patchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("patch-radius")
        .description("Horizontal radius counted as convertible. The feature rolls 1 or 2 at " +
                     "random, so radius 1 is what every bone meal is guaranteed to reach; " +
                     "2 also counts columns that only convert about half the time.")
        .defaultValue(1).min(1).max(2)
        .build());

    private final Setting<Integer> minConversions = sgGeneral.add(new IntSetting.Builder()
        .name("min-conversions")
        .description("Minimum number of blocks that must actually turn into moss before " +
                     "spending a bone meal. Raise it to trade speed for efficiency.")
        .defaultValue(1).min(1).max(8).sliderMin(1).sliderMax(8)
        .build());

    private final Setting<Boolean> stoneOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("stone-only")
        .description("Count only stone-family blocks (#base_stone_overworld). Off also counts " +
                     "the dirt family, which converts too.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between two bone meals.")
        .defaultValue(4).min(0).max(20).sliderMin(0).sliderMax(20)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face the moss block before using the bone meal.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to your previous hotbar slot after each bone meal.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand client-side. Off still sends the swing packet.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlight the moss block currently being targeted.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the highlight is drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the highlight.")
        .defaultValue(new SettingColor(89, 204, 108, 40))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the highlight.")
        .defaultValue(new SettingColor(89, 204, 108, 190))
        .visible(render::get)
        .build());

    /** Search offsets around the player, sorted nearest-first. Rebuilt when range changes. */
    private int[] offsets = new int[0];
    private int builtRadius = -1;

    /** Reused across the whole scan so a tick allocates nothing. */
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();

    private BlockPos target;
    private int timer;

    public AutoMoss() {
        super(NewAddon.CATEGORY, "auto-moss",
            "Bone meals nearby moss, but only when stone around it would really turn to moss.");
    }

    @Override
    public void onActivate() {
        target = null;
        timer = 0;
        builtRadius = -1;

        if (mc.player != null && !InvUtils.findInHotbar(Items.BONE_MEAL).found()) {
            warning("No bone meal in your hotbar.");
        }
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        boolean ready = timer <= 0;
        if (!ready) timer--;

        // On cooldown ticks the scan buys nothing unless the highlight needs refreshing.
        if (!ready && !render.get()) return;

        FindItemResult bonemeal = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemeal.found()) {
            target = null;
            return;
        }

        target = findTarget();
        if (target == null || !ready) return;

        timer = delay.get();
        useBonemeal(target);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null) return;
        event.renderer.box(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    /**
     * Returns the closest moss block worth bone mealing, or null if there is none.
     * Rejections are ordered cheapest-first: reach, then the block itself, then air above,
     * and only then the column walk that decides whether anything would convert.
     */
    private BlockPos findTarget() {
        int radius = Mth.ceil(range.get());
        if (radius != builtRadius) rebuildOffsets(radius);

        Vec3 eye = mc.player.getEyePosition();
        double maxDistSq = range.get() * range.get();
        BlockPos origin = mc.player.blockPosition();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int needed = minConversions.get();

        for (int packed : offsets) {
            int x = ox + (((packed >> 16) & 0xFF) - PACK_BIAS);
            int y = oy + (((packed >> 8) & 0xFF) - PACK_BIAS);
            int z = oz + ((packed & 0xFF) - PACK_BIAS);

            double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            scanPos.set(x, y, z);
            if (!mc.level.getBlockState(scanPos).is(Blocks.MOSS_BLOCK)) continue;

            // MossBlock#isValidBonemealTarget: the block above must be air, nothing else.
            scanPos.set(x, y + 1, z);
            if (!mc.level.getBlockState(scanPos).isAir()) continue;

            if (countConversions(x, y + 1, z, needed) >= needed) return new BlockPos(x, y, z);
        }

        return null;
    }

    /**
     * Counts how many columns around the patch origin would really become moss, stopping
     * as soon as {@code needed} of them are found.
     *
     * <p>Mirrors {@code VegetationPatchFeature#placeGroundPatch}: from the origin, walk down
     * through air, then back up out of solid, both capped at {@link #VERTICAL_RANGE}. That
     * leaves the air gap sitting on the column's surface; the block underneath it is the one
     * the feature would replace.
     *
     * @param ox x of the patch origin (the air block above the moss)
     * @param oy y of the patch origin
     * @param oz z of the patch origin
     * @param needed stop and return once this many conversions are confirmed
     */
    private int countConversions(int ox, int oy, int oz, int needed) {
        int radius = patchRadius.get();
        int found = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = ox + dx, z = oz + dz, y = oy;

                int steps = 0;
                columnPos.set(x, y, z);
                while (steps < VERTICAL_RANGE && mc.level.getBlockState(columnPos).isAir()) {
                    columnPos.set(x, --y, z);
                    steps++;
                }

                steps = 0;
                while (steps < VERTICAL_RANGE && !mc.level.getBlockState(columnPos).isAir()) {
                    columnPos.set(x, ++y, z);
                    steps++;
                }

                // Ran out of vertical range without finding the gap: nothing converts here.
                if (!mc.level.getBlockState(columnPos).isAir()) continue;

                columnPos.set(x, y - 1, z);
                if (isConvertible(mc.level.getBlockState(columnPos)) && ++found >= needed) {
                    return found;
                }
            }
        }

        return found;
    }

    /** True if the patch would replace this block with moss and that is an actual gain. */
    private boolean isConvertible(BlockState state) {
        // Already moss: placeGround leaves it alone, so it is not worth any bone meal.
        if (state.is(Blocks.MOSS_BLOCK)) return false;

        return stoneOnly.get()
            ? state.is(BlockTags.BASE_STONE_OVERWORLD)
            : state.is(BlockTags.MOSS_REPLACEABLE);
    }

    private void useBonemeal(BlockPos pos) {
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, () -> interact(pos));
        } else {
            interact(pos);
        }
    }

    private void interact(BlockPos pos) {
        // With rotation this runs a tick or more later, so re-check everything: the module
        // may have been toggled off, the moss mined, or the hotbar rearranged since.
        if (!isActive() || mc.player == null || mc.level == null) return;
        if (!mc.level.getBlockState(pos).is(Blocks.MOSS_BLOCK)) return;

        FindItemResult bonemeal = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemeal.found()) return;

        InvUtils.swap(bonemeal.slot(), swapBack.get());
        BlockUtils.interact(
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
            InteractionHand.MAIN_HAND,
            swing.get()
        );
        if (swapBack.get()) InvUtils.swapBack();
    }

    /**
     * Precomputes every offset in the search cube, ordered by distance from the player.
     *
     * <p>Each offset is packed into the low 24 bits of an int and paired with its squared
     * distance in the high bits of a long, so a single primitive sort orders the whole table
     * nearest-first with no boxing. Scans then walk the table and stop at the first hit,
     * which is by construction the closest one.
     */
    private void rebuildOffsets(int radius) {
        int side = radius * 2 + 1;
        long[] keys = new long[side * side * side];
        int i = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long distSq = dx * dx + dy * dy + dz * dz;
                    int packed = ((dx + PACK_BIAS) << 16) | ((dy + PACK_BIAS) << 8) | (dz + PACK_BIAS);
                    keys[i++] = (distSq << 24) | packed;
                }
            }
        }

        Arrays.sort(keys);

        int[] result = new int[keys.length];
        for (int j = 0; j < keys.length; j++) result[j] = (int) (keys[j] & 0xFFFFFF);

        offsets = result;
        builtRadius = radius;
    }
}
