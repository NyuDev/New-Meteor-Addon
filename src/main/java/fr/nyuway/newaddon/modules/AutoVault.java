package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.Reach;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * AutoVault - spends a trial key only on the roll worth having.
 *
 * <h2>What a vault tells you before you pay</h2>
 * A vault in a trial chamber spins an item above itself, and that item is the one it is about to
 * give you. It changes every second or so until somebody puts a key in. So the whole game is
 * patience: stand in front of one, wait for the display to come round to a heavy core, and only
 * then use the key. This does the waiting and the timing, which are the two parts a person is
 * bad at - a second of reaction time is most of the window.
 *
 * <p>Ominous and ordinary vaults take different keys, and which one it is is written in the
 * block itself, so the right key goes in without being told which. A vault already unlocking or
 * throwing out its loot is left alone: the key would be spent on nothing.
 *
 * <h2>What it is not</h2>
 * It does not open vaults on its own, walk to them, or hoard keys. It clicks one vault, once,
 * when what it is showing is on the list - and puts the hotbar back the way it found it.
 *
 * <p>Vaults arrived in 1.21, so on the two older versions this addon builds for the module loads
 * and says so rather than pretending to work.
 */
public class AutoVault extends Module {

    /** Which vaults to bother with. */
    public enum Which {
        /** Both kinds, whichever key each one needs. */
        Any,
        /** Only ominous vaults, the ones an ominous key opens. */
        Ominous,
        /** Only ordinary vaults. */
        Normal
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Item>> wanted = sgGeneral.add(new ItemListSetting.Builder()
        .name("wanted")
        .description("Use a key only when the vault is showing one of these. The heavy core is " +
                     "the reason anybody farms ominous vaults; add the enchanted book, or a " +
                     "trident, or whatever this trip is actually for.")
        // The item itself only exists from 1.21, and a default that names it will not compile
        // against the two older versions this addon still builds for.
        //? if >=1.21 {
        .defaultValue(List.of(Items.HEAVY_CORE))
        //?} else {
        /*.defaultValue(List.of())
        *///?}
        .build());

    private final Setting<Which> which = sgGeneral.add(new EnumSetting.Builder<Which>()
        .name("vaults")
        .description("Which vaults to spend keys on. The kind is read off the block, so Any " +
                     "uses the right key for each without being told.")
        .defaultValue(Which.Any)
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far a vault can be and still be used. Past the server's own reach " +
                     "nothing happens, whatever this says.")
        .defaultValue(4.5).min(1).max(6).sliderRange(1, 6)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turn to face the vault before using it. On: a key going into a vault " +
                     "behind you is the sort of thing that is looked at.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> clickAfter = sgGeneral.add(new IntSetting.Builder()
        .name("click-after")
        .description("Ticks to wait after the wanted item appears before using the key. The " +
                     "display arrives before the server has moved on to it, so a click on the " +
                     "very tick it appears is answered with the roll before it - which is the " +
                     "one you were not waiting for. One tick is usually enough; a laggy server " +
                     "wants more.")
        .defaultValue(1).min(0).max(40).sliderRange(0, 10)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between two uses. The vault takes a moment to answer, and clicking " +
                     "again inside it spends a second key on the same roll.")
        .defaultValue(20).min(1).max(200).sliderRange(5, 60)
        .build());

    private final Setting<Boolean> restoreSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-slot")
        .description("Put the hotbar selection back after using a key.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Say in chat what was showing when a key went in.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Outline vaults in range, and what they are showing.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Lines)
        .visible(render::get)
        .build());

    private final Setting<SettingColor> matchColor = sgRender.add(new ColorSetting.Builder()
        .name("match-color")
        .description("A vault showing something on the list.")
        .defaultValue(new SettingColor(80, 255, 120))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> waitColor = sgRender.add(new ColorSetting.Builder()
        .name("waiting-color")
        .description("A vault showing something else.")
        .defaultValue(new SettingColor(120, 120, 140, 120))
        .visible(render::get)
        .build());

    /** Ticks until another key may go in. */
    private int cooldown;

    /** The hotbar slot in hand before a key was picked up, or -1. */
    private int homeSlot = -1;

    /** Kept for the outline, so the render does not repeat the search. */
    private BlockPos drawMatch;
    private BlockPos drawWaiting;

    /** What each vault was showing last tick, so the moment it changes can be told from the rest. */
    private final java.util.Map<BlockPos, Item> lastShown = new java.util.HashMap<>();

    /** A key waiting to go in: where, which kind, what it was showing, and how long left. */
    private BlockPos pendingPos;
    private boolean pendingOminous;
    private ItemStack pendingShowing = ItemStack.EMPTY;
    private int pendingTicks;

    public AutoVault() {
        super(NewAddon.CATEGORY, "auto-vault",
            "Puts a trial key in only when the vault is showing what you came for.");
    }

    @Override
    public void onActivate() {
        cooldown = 0;
        homeSlot = -1;
        drawMatch = null;
        drawWaiting = null;
        pendingPos = null;
        pendingShowing = ItemStack.EMPTY;
        lastShown.clear();

        //? if <1.21 {
        /*warning("Vaults do not exist on this version; nothing will happen.");
        *///?}
    }

    @Override
    public void onDeactivate() {
        putSlotBack();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        drawMatch = null;
        drawWaiting = null;

        // The map is one entry per vault ever looked at; a chamber is a few dozen and a session
        // is many chambers, so it is emptied whenever nothing is pending rather than grown.
        if (lastShown.size() > 256 && pendingPos == null) lastShown.clear();

        if (cooldown > 0) {
            cooldown--;

            // The slot goes back on the tick after the click rather than the same one: swapping
            // away before the use has been sent is how a key gets spent as a lump of iron.
            putSlotBack();
            return;
        }

        // A key that was lined up a moment ago and is now due.
        if (pendingPos != null) {
            drawMatch = pendingPos;
            if (--pendingTicks > 0) return;

            BlockPos pos = pendingPos;
            boolean ominous = pendingOminous;
            ItemStack showing = pendingShowing;
            pendingPos = null;
            pendingShowing = ItemStack.EMPTY;

            if (stillUsable(pos)) use(pos, ominous, showing);
            return;
        }

        //? if >=1.21 {
        scanAndUse();
        //?}
    }

    /** Whether a vault lined up a moment ago is still worth the key. */
    private boolean stillUsable(BlockPos pos) {
        //? if >=1.21 {
        if (!Reach.canReach(mc, pos, false, range.get())) return false;

        var state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.VaultBlock)) return false;

        var phase = state.getValue(net.minecraft.world.level.block.VaultBlock.STATE);
        return phase == net.minecraft.world.level.block.entity.vault.VaultState.ACTIVE
            || phase == net.minecraft.world.level.block.entity.vault.VaultState.INACTIVE;
        //?} else {
        /*return false;
        *///?}
    }

    //? if >=1.21 {
    /**
     * Looks over the vaults in reach and uses a key on the first one showing something wanted.
     *
     * <p>A sweep of the reach cube rather than a walk over the loaded block entities: the client
     * keeps no list of those that can be read from here. The cube is small - reach is six blocks
     * at the very most - and the block state is checked before anything asks for an entity, so
     * all but a handful of the positions cost one array lookup and nothing else.
     */
    private void scanAndUse() {
        int r = net.minecraft.util.Mth.ceil(range.get());
        BlockPos feet = mc.player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);

                    var state = mc.level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof net.minecraft.world.level.block.VaultBlock)) {
                        continue;
                    }

                    BlockPos pos = cursor.immutable();
                    if (!Reach.canReach(mc, pos, false, range.get())) continue;
                    if (!(mc.level.getBlockEntity(pos)
                        instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity vault)) {
                        continue;
                    }

                    if (consider(pos, state, vault)) return;
                }
            }
        }
    }

    /** One vault, looked at. Returns true when a key went in and the sweep should stop. */
    private boolean consider(BlockPos pos,
                             net.minecraft.world.level.block.state.BlockState state,
                             net.minecraft.world.level.block.entity.vault.VaultBlockEntity vault) {
        {

            // Already opening, or throwing its loot out. A key put in now buys nothing.
            var phase = state.getValue(net.minecraft.world.level.block.VaultBlock.STATE);
            if (phase != net.minecraft.world.level.block.entity.vault.VaultState.ACTIVE
                && phase != net.minecraft.world.level.block.entity.vault.VaultState.INACTIVE) {
                return false;
            }

            boolean ominous = state.getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS);
            if (which.get() == Which.Ominous && !ominous) return false;
            if (which.get() == Which.Normal && ominous) return false;

            ItemStack showing = vault.getSharedData().getDisplayItem();
            if (showing.isEmpty()) return false;

            Item now = showing.getItem();
            Item before = lastShown.put(pos, now);

            if (!wanted.get().contains(now)) {
                if (drawWaiting == null) drawWaiting = pos;
                return false;
            }

            drawMatch = pos;

            // Only the tick it appears on, not every tick it sits there. The wait below is
            // measured from the change, so counting it from a later tick would be waiting for
            // the server to catch up to something it had already passed.
            if (before == now) return false;

            if (clickAfter.get() <= 0) {
                use(pos, ominous, showing);
                return true;
            }

            // Lined up rather than used. The display reaches the client before the server has
            // moved on to it, so a click on the tick it appears is answered with the roll
            // before it - the one you were not waiting for. Waiting a moment lets the server
            // arrive at the item that is already on screen, and what the screen says by then
            // does not matter: it is the roll behind it that is being bought.
            pendingPos = pos;
            pendingOminous = ominous;
            pendingShowing = showing.copy();
            pendingTicks = clickAfter.get();
            return true;
        }
    }
    //?}

    /**
     * Puts the right key in hand and clicks the vault.
     *
     * <p>Which key is read off the block rather than guessed: an ominous vault takes an ominous
     * key and refuses the other, and spending the wrong one is a wasted trip to the hotbar at
     * exactly the moment the display is about to change.
     */
    private void use(BlockPos pos, boolean ominous, ItemStack showing) {
        // The keys are as new as the vaults they open, so the older versions get a stand-in.
        // Unreachable there - the sweep that calls this is itself only built from 1.21 - but it
        // still has to compile.
        //? if >=1.21 {
        Item key = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
        //?} else {
        /*Item key = Items.AIR;
        *///?}

        FindItemResult held = InvUtils.findInHotbar(key);
        if (!held.found()) {
            if (notify.get()) {
                // Named through a stack rather than through the item: Item.getName lost its
                // no-argument form part way through the versions this builds for, and a stack's
                // hover name reads the same on all of them.
                warning("A vault is showing %s and there is no %s in the hotbar.",
                    showing.getHoverName().getString(),
                    key.getDefaultInstance().getHoverName().getString());
            }
            cooldown = delay.get() * 4;
            return;
        }

        if (!held.isMainHand()) {
            if (homeSlot == -1) homeSlot = held.slot();
            InvUtils.swap(held.slot(), false);
        }

        Interactions.interact(mc, pos, !rotate.get(), true);
        cooldown = delay.get();

        if (notify.get()) info("Vault was showing %s.", showing.getHoverName().getString());
    }

    private void putSlotBack() {
        if (homeSlot == -1 || !restoreSlot.get() || mc.player == null) {
            homeSlot = -1;
            return;
        }

        InvUtils.swap(homeSlot, false);
        homeSlot = -1;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (drawWaiting != null) {
            event.renderer.box(drawWaiting, waitColor.get(), waitColor.get(), shapeMode.get(), 0);
        }
        if (drawMatch != null) {
            event.renderer.box(drawMatch, matchColor.get(), matchColor.get(), shapeMode.get(), 0);
        }
    }
}
