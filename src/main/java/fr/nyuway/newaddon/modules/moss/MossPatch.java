package fr.nyuway.newaddon.modules.moss;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Predicts what vanilla's {@code moss_patch_bonemeal} feature would actually convert.
 *
 * <p>This is the whole reason AutoMoss does not simply spam bone meal: an item is consumed
 * whenever the patch places, even when the only thing it does is sprout decoration on moss
 * that was already there. Knowing in advance how many blocks would really change is what
 * makes the difference between farming and wasting.
 *
 * <p>Mirrors {@code VegetationPatchFeature#placeGroundPatch} closely, including three rules
 * that are easy to miss and each cost real bone meal when they were:
 * <ul>
 *   <li>Corner columns are never placed at all.</li>
 *   <li>The centre column is whatever the patch sits on, so it is never a gain.</li>
 *   <li>A column is reached by walking down through air then back up out of solid, both
 *       capped at the feature's vertical range - so a buried block is never touched.</li>
 * </ul>
 */
public final class MossPatch {

    /**
     * {@code verticalRange} of the feature: how far it may travel up or down a column while
     * looking for the surface to replace.
     */
    public static final int VERTICAL_RANGE = 5;

    private final Level level;
    private final int patchRadius;
    private final boolean stoneOnly;

    /** Reused across a count so checking a target allocates nothing. */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public MossPatch(Level level, int patchRadius, boolean stoneOnly) {
        this.level = level;
        this.patchRadius = patchRadius;
        this.stoneOnly = stoneOnly;
    }

    /**
     * How many columns around a patch origin would really become moss.
     *
     * @param ox     x of the patch origin, the block above the moss
     * @param oy     y of the patch origin
     * @param oz     z of the patch origin
     * @param needed stop and return as soon as this many are confirmed
     */
    public int countConversions(int ox, int oy, int oz, int needed) {
        int found = 0;

        for (int dx = -patchRadius; dx <= patchRadius; dx++) {
            for (int dz = -patchRadius; dz <= patchRadius; dz++) {
                if (isCorner(dx, dz) || (dx == 0 && dz == 0)) continue;

                int x = ox + dx, z = oz + dz, y = oy;

                int steps = 0;
                cursor.set(x, y, z);
                while (steps < VERTICAL_RANGE && level.getBlockState(cursor).isAir()) {
                    cursor.set(x, --y, z);
                    steps++;
                }

                steps = 0;
                while (steps < VERTICAL_RANGE && !level.getBlockState(cursor).isAir()) {
                    cursor.set(x, ++y, z);
                    steps++;
                }

                // Ran out of vertical range without finding the gap: nothing converts here.
                if (!level.getBlockState(cursor).isAir()) continue;

                cursor.set(x, y - 1, z);
                if (isConvertible(level.getBlockState(cursor)) && ++found >= needed) return found;
            }
        }

        return found;
    }

    /** The feature skips its corner columns outright, whatever the radius rolls. */
    private boolean isCorner(int dx, int dz) {
        return (dx == -patchRadius || dx == patchRadius)
            && (dz == -patchRadius || dz == patchRadius);
    }

    /** True if the patch would replace this block with moss and that is an actual gain. */
    public boolean isConvertible(BlockState state) {
        // Already moss: placeGround leaves it alone, so it is worth no bone meal at all.
        if (state.is(Blocks.MOSS_BLOCK)) return false;

        return stoneOnly
            ? state.is(BlockTags.BASE_STONE_OVERWORLD)
            : state.is(BlockTags.MOSS_REPLACEABLE);
    }
}
