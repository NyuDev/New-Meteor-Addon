package fr.nyuway.newaddon.compat;

import fr.nyuway.newaddon.NewAddon;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection bridge to the <b>Meteor fork</b> of Baritone.
 *
 * <p>Compiled without a single Baritone class on the classpath, on purpose. Meteor publishes
 * Baritone from 1.20.2 upward, but this addon also targets 1.20.1, so a normal dependency
 * would break that node outright. Reflection keeps all twelve versions building from one
 * source and makes Baritone genuinely optional at runtime rather than a hard requirement.
 *
 * <p>Only the {@code baritone.api} package is touched. The Meteor fork minifies its
 * internals but leaves that package's names intact, so these lookups are stable; nothing
 * here reaches into anything that gets renamed.
 *
 * <p>Every entry point is null-safe and returns a neutral value when Baritone is absent or
 * when the API has moved, so callers never need to guard beyond {@link #isUsable()}.
 */
public final class BaritoneBridge {

    /** Fabric mod id of the Meteor fork. Official Baritone uses {@code baritone}. */
    private static final String MOD_ID = "baritone-meteor";

    private static boolean resolved;
    private static boolean usable;
    private static boolean warned;

    private static Object provider;
    private static Method mGetPrimaryBaritone;
    private static Method mGetWorldScanner;
    private static Method mGetPlayerContext;
    private static Method mScanChunkRadius;
    private static Method mGetCustomGoalProcess;
    private static Method mSetGoalAndPath;
    private static Method mGetPathingBehavior;
    private static Method mCancelEverything;
    private static Method mIsPathing;
    private static Method mGetGoal;
    private static Class<?> goalRenderPosClass;
    private static Method mGetGoalPos;
    private static Method mGetElytraProcess;
    private static Method mElytraIsActive;
    private static Method mElytraDestination;
    private static Method mElytraPathTo;
    private static Constructor<?> cGoalNear;
    private static Constructor<?> cGoalBlock;
    private static Constructor<?> cGoalXZ;

    private BaritoneBridge() {
    }

    /** True when the Meteor fork is installed. Says nothing about the API resolving. */
    public static boolean isPresent() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** True when the fork is installed and every API handle resolved. */
    public static boolean isUsable() {
        if (!isPresent()) return false;
        if (!resolved) resolve();
        return usable;
    }

    /**
     * Asks Baritone for nearby positions of a block, using its own chunk scanner rather
     * than a brute-force sweep of the world.
     *
     * @param block          block to look for
     * @param max            maximum positions to return
     * @param yThreshold     how far in Y from the player to still consider
     * @param chunkRadius    search radius in chunks
     * @return positions found, nearest-ish first, or an empty list
     */
    @SuppressWarnings("unchecked")
    public static List<BlockPos> scanFor(Block block, int max, int yThreshold, int chunkRadius) {
        if (!isUsable()) return List.of();

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object scanner = mGetWorldScanner.invoke(provider);
            Object ctx = mGetPlayerContext.invoke(baritone);
            Object result = mScanChunkRadius.invoke(
                scanner, ctx, List.of(block), max, yThreshold, chunkRadius);
            return result == null ? List.of() : (List<BlockPos>) result;
        } catch (Throwable t) {
            fail("scan failed", t);
            return List.of();
        }
    }

    /**
     * Sends Baritone walking to within {@code radius} blocks of {@code pos}.
     *
     * <p>A radius of zero uses {@code GoalBlock} rather than a zero-radius {@code GoalNear}.
     * While the elytra process is running, {@code CustomGoalProcess.setGoal} hands the goal
     * straight to it, and it accepts only {@code GoalXZ} or {@code GoalBlock} - anything else
     * throws, which is not something a caller asking to walk one block can be expected to know.
     */
    public static boolean pathTo(BlockPos pos, int radius) {
        if (!isUsable()) return false;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object process = mGetCustomGoalProcess.invoke(baritone);
            Object goal = radius <= 0
                ? cGoalBlock.newInstance(pos)
                : cGoalNear.newInstance(pos, radius);
            mSetGoalAndPath.invoke(process, goal);
            return true;
        } catch (Throwable t) {
            fail("pathTo failed", t);
            return false;
        }
    }

    /**
     * Sends Baritone walking to a horizontal coordinate, ignoring height. Used to cover
     * ground when there is nothing worth pathing to precisely.
     */
    public static boolean exploreTo(int x, int z) {
        if (!isUsable()) return false;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object process = mGetCustomGoalProcess.invoke(baritone);
            mSetGoalAndPath.invoke(process, cGoalXZ.newInstance(x, z));
            return true;
        } catch (Throwable t) {
            fail("exploreTo failed", t);
            return false;
        }
    }

    /**
     * Where Baritone is headed according to its own goal, or null.
     *
     * <p>A second, independent source of the trip destination. The elytra process is not the
     * only thing that knows where we are going, and relying on it alone meant that when it
     * reported inactive - as it does on some builds even mid-glide - the destination was
     * never captured and the whole module sat idle without a word.
     *
     * <p>Only goals that carry a concrete position answer; a bare "go this direction" goal
     * has nothing useful to give.
     */
    public static BlockPos currentGoalPos() {
        if (!isUsable()) return null;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object behavior = mGetPathingBehavior.invoke(baritone);
            Object goal = mGetGoal.invoke(behavior);
            if (goal == null || !goalRenderPosClass.isInstance(goal)) return null;
            return (BlockPos) mGetGoalPos.invoke(goal);
        } catch (Throwable t) {
            fail("currentGoalPos failed", t);
            return null;
        }
    }

    /** True while Baritone's elytra process is flying us somewhere. */
    public static boolean isElytraActive() {
        if (!isUsable()) return false;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object elytra = mGetElytraProcess.invoke(baritone);
            return Boolean.TRUE.equals(mElytraIsActive.invoke(elytra));
        } catch (Throwable t) {
            fail("isElytraActive failed", t);
            return false;
        }
    }

    /** Where the elytra process is currently headed, or null. */
    public static BlockPos elytraDestination() {
        if (!isUsable()) return null;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object elytra = mGetElytraProcess.invoke(baritone);
            return (BlockPos) mElytraDestination.invoke(elytra);
        } catch (Throwable t) {
            fail("elytraDestination failed", t);
            return null;
        }
    }

    /** Sends the elytra process back to a destination, resuming an interrupted flight. */
    public static boolean elytraPathTo(BlockPos pos) {
        if (!isUsable()) return false;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object elytra = mGetElytraProcess.invoke(baritone);
            mElytraPathTo.invoke(elytra, pos);
            return true;
        } catch (Throwable t) {
            fail("elytraPathTo failed", t);
            return false;
        }
    }

    /** Stops whatever Baritone is currently doing. */
    public static void cancel() {
        if (!isUsable()) return;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            mCancelEverything.invoke(mGetPathingBehavior.invoke(baritone));
        } catch (Throwable t) {
            fail("cancel failed", t);
        }
    }

    /** True while Baritone is actively following a path. */
    public static boolean isPathing() {
        if (!isUsable()) return false;

        try {
            Object baritone = mGetPrimaryBaritone.invoke(provider);
            Object behavior = mGetPathingBehavior.invoke(baritone);
            return Boolean.TRUE.equals(mIsPathing.invoke(behavior));
        } catch (Throwable t) {
            fail("isPathing failed", t);
            return false;
        }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;

        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> scannerClass = Class.forName("baritone.api.cache.IWorldScanner");
            Class<?> ctxClass = Class.forName("baritone.api.utils.IPlayerContext");
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Class<?> goalXZClass = Class.forName("baritone.api.pathing.goals.GoalXZ");
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Class<?> customGoalClass = Class.forName("baritone.api.process.ICustomGoalProcess");
            Class<?> pathingClass = Class.forName("baritone.api.behavior.IPathingBehavior");
            Class<?> elytraClass = Class.forName("baritone.api.process.IElytraProcess");
            Class<?> processClass = Class.forName("baritone.api.process.IBaritoneProcess");

            provider = api.getMethod("getProvider").invoke(null);
            if (provider == null) {
                fail("Baritone provider is null", null);
                return;
            }

            mGetPrimaryBaritone = providerClass.getMethod("getPrimaryBaritone");
            mGetWorldScanner = providerClass.getMethod("getWorldScanner");
            mGetPlayerContext = baritoneClass.getMethod("getPlayerContext");
            mScanChunkRadius = scannerClass.getMethod(
                "scanChunkRadius", ctxClass, List.class, int.class, int.class, int.class);
            mGetCustomGoalProcess = baritoneClass.getMethod("getCustomGoalProcess");
            mSetGoalAndPath = customGoalClass.getMethod("setGoalAndPath", goalClass);
            mGetPathingBehavior = baritoneClass.getMethod("getPathingBehavior");
            mCancelEverything = pathingClass.getMethod("cancelEverything");
            mIsPathing = pathingClass.getMethod("isPathing");
            mGetGoal = pathingClass.getMethod("getGoal");
            goalRenderPosClass = Class.forName("baritone.api.utils.interfaces.IGoalRenderPos");
            mGetGoalPos = goalRenderPosClass.getMethod("getGoalPos");
            mGetElytraProcess = baritoneClass.getMethod("getElytraProcess");
            mElytraIsActive = processClass.getMethod("isActive");
            mElytraDestination = elytraClass.getMethod("currentDestination");
            mElytraPathTo = elytraClass.getMethod("pathTo", BlockPos.class);
            cGoalNear = goalNearClass.getConstructor(BlockPos.class, int.class);
            cGoalXZ = goalXZClass.getConstructor(int.class, int.class);
            cGoalBlock = goalBlockClass.getConstructor(BlockPos.class);

            usable = true;
        } catch (Throwable t) {
            fail("could not bind to the Baritone API", t);
        }
    }

    /**
     * Reports a failure, and decides whether it is worth giving up on Baritone entirely.
     *
     * <p>{@link InvocationTargetException} means the binding resolved and Baritone's own method
     * threw - a rejected argument, a bad state, something this call got wrong. That says
     * nothing about the other twelve bindings, and latching {@code usable} off for it took one
     * unacceptable goal and silently killed every Baritone feature for the rest of the session.
     *
     * <p>Anything else is the binding itself being wrong, which will not fix itself, so that
     * still latches.
     */
    private static void fail(String what, Throwable t) {
        if (t instanceof InvocationTargetException) {
            NewAddon.LOG.warn("[Baritone] {} - refused by Baritone: {}",
                what, String.valueOf(t.getCause()));
            return;
        }

        usable = false;
        if (warned) return;
        warned = true;
        NewAddon.LOG.warn("[Baritone] {} - Baritone control disabled. "
            + "This needs Meteor's Baritone fork (mod id {}), not official Baritone.",
            what, MOD_ID, t);
    }
}
