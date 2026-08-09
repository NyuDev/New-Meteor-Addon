package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.PlayerInv;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * ElytraSwap - puts a fresh elytra on before the one you are wearing gives out.
 *
 * <p>Ported from BepHax, settings, defaults and behaviour alike. A long flight ends the moment
 * the elytra does, usually a long way from anywhere; swapping at ten durability left means the
 * flight simply carries on.
 *
 * <h2>How the swap is made</h2>
 * There is no packet for "put this in my chest slot". What there is, is the fact that using an
 * elytra in hand equips it and hands you back whatever was there - so the swap is: get the new
 * one onto the hotbar, hold it, right-click, and put the old one where the new one came from.
 *
 * <p>Each of those is a click the server has to agree with, so they are done one per stage with
 * ticks in between rather than in a burst. The moves are hotbar swaps - the click a number key
 * makes - because that is one packet with a destination we choose, and because a swap undoes
 * itself: the same click that fetches the new elytra puts the old one back in its place and
 * returns the borrowed hotbar slot to its owner. BepHax spent four stages and a saved copy of
 * the displaced stack doing by hand what the swap does on its own.
 *
 * <h2>Combat protection</h2>
 * Off by default, and separate: being hit while wearing an elytra means wearing no armour at
 * all, so it will put a chestplate on for a few seconds and then go back. Same staging, same
 * reasoning.
 */
public class ElytraSwap extends Module {

    /** Ticks between stages. Each one is a click, and each click wants its own round trip. */
    private static final int STAGE_TICKS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombat = settings.createGroup("Combat Protection");

    private final Setting<Integer> durabilityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Swap the elytra when its durability drops below this.")
        .defaultValue(10).min(1).max(100).sliderRange(1, 50)
        .build());

    private final Setting<Boolean> onlyWhileFlying = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-flying")
        .description("Only swap while actually flying.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseInInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-in-inventory")
        .description("Do nothing while a container is open, so the two sides cannot disagree " +
                     "about what is where.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> swapCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("swap-cooldown")
        .description("Ticks to wait after a swap before looking again.")
        .defaultValue(100).min(20).max(200).sliderRange(20, 200)
        .build());

    private final Setting<Boolean> notifySwap = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-swap")
        .description("Say in chat when an elytra is swapped.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swapOnHit = sgCombat.add(new BoolSetting.Builder()
        .name("swap-on-hit")
        .description("Put a chestplate on when something hits you. An elytra is no armour at " +
                     "all, and a fight found mid-flight is a fight you are naked for.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> hitProtectionDuration = sgCombat.add(new IntSetting.Builder()
        .name("protection-duration")
        .description("Ticks to keep the chestplate on after being hit. Another hit starts it over.")
        .defaultValue(60).min(20).max(200).sliderRange(20, 200)
        .visible(swapOnHit::get)
        .build());

    private final Setting<Boolean> autoSwapBack = sgCombat.add(new BoolSetting.Builder()
        .name("auto-swap-back")
        .description("Put the elytra back on once that time is up.")
        .defaultValue(true)
        .visible(swapOnHit::get)
        .build());

    private final Setting<Boolean> prioritizeNetherite = sgCombat.add(new BoolSetting.Builder()
        .name("prioritize-netherite")
        .description("Prefer a netherite chestplate over a diamond one.")
        .defaultValue(true)
        .visible(swapOnHit::get)
        .build());

    /** Ticks left before another swap may be considered. */
    private int cooldown;

    /** The swap in progress: which stage, how long it has been on it, and the two slots. */
    private int stage;
    private int stageTicks;
    /** Inventory slot the replacement came from, which is where the worn one goes. */
    private int fromSlot = -1;
    /** Hotbar slot borrowed to hold it, given back by the same swap that took it. */
    private int hotbarSlot = -1;

    /** True while a chestplate is on for protection, so the durability half stays out of it. */
    private boolean protecting;
    private int protectionTicks;
    private int lastHurt;

    /** What the protection swap is doing, mirroring the stages above. */
    private int guardStage;
    private int guardSlot = -1;
    /** The elytra taken off for protection, so the right one goes back on. */
    private ItemStack storedElytra = ItemStack.EMPTY;

    public ElytraSwap() {
        super(NewAddon.CATEGORY, "elytra-swap",
            "Swaps in a fresh elytra before the one you are wearing runs out.");
    }

    @Override
    public void onActivate() {
        reset();
        cooldown = 0;
        protecting = false;
        protectionTicks = 0;
        lastHurt = 0;
        storedElytra = ItemStack.EMPTY;
    }

    private void reset() {
        stage = 0;
        stageTicks = 0;
        fromSlot = -1;
        hotbarSlot = -1;
        guardStage = 0;
        guardSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (swapOnHit.get()) protection();

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // A container open means clicks are going somewhere else entirely. Anything half done
        // is dropped rather than finished against the wrong menu.
        if (pauseInInventory.get() && mc.player.containerMenu != mc.player.inventoryMenu) {
            reset();
            return;
        }

        // The protection half owns the chest slot while it is running.
        if (protecting || guardStage != 0) return;

        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.is(Items.ELYTRA)) return;
        if (onlyWhileFlying.get() && !mc.player.isFallFlying()) return;

        if (stage != 0) {
            advance();
            return;
        }

        if (left(worn) <= durabilityThreshold.get()) begin();
    }

    /** Durability a stack has left. */
    private static int left(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    /** Picks the best spare and starts the swap, or does nothing if there is no spare worth it. */
    private void begin() {
        int best = -1;
        int bestLeft = durabilityThreshold.get();

        for (int i = 0; i < PlayerInv.MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.is(Items.ELYTRA)) continue;

            // Strictly better than the threshold, or the swap would be for an elytra about to
            // need swapping itself - which is a loop, not a resupply.
            if (left(stack) > bestLeft) {
                bestLeft = left(stack);
                best = i;
            }
        }

        if (best == -1) return;

        fromSlot = best;
        stage = 1;
        stageTicks = 0;
    }

    /**
     * One step of the swap per {@link #STAGE_TICKS}.
     *
     * <p>Three stages: bring it to hand, wear it, put the old one back. The first and third are
     * the same click - a hotbar swap exchanges the two slots, so doing it twice leaves the
     * hotbar exactly as it was found with the worn elytra where the spare used to be.
     */
    private void advance() {
        if (++stageTicks < STAGE_TICKS) return;
        stageTicks = 0;

        switch (stage) {
            case 1 -> {
                if (fromSlot < PlayerInv.HOTBAR_SIZE) {
                    // Already on the bar; nothing to borrow and nothing to give back.
                    hotbarSlot = fromSlot;
                    stage = 2;
                    return;
                }

                hotbarSlot = borrowSlot();
                InvUtils.quickSwap()
                    .fromId(hotbarSlot)
                    .toId(PlayerInv.inventoryIndexToMenuSlot(fromSlot));
                stage = 2;
            }
            case 2 -> {
                ItemStack held = mc.player.getInventory().getItem(hotbarSlot);
                if (!held.is(Items.ELYTRA)) {
                    // The swap did not land. Nothing has been taken off yet, so stopping here
                    // costs nothing and beats right-clicking whatever is actually in hand.
                    warning("The spare elytra did not reach my hand; leaving it alone.");
                    reset();
                    return;
                }

                InvUtils.swap(hotbarSlot, false);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
                stage = 3;
            }
            case 3 -> {
                if (fromSlot >= PlayerInv.HOTBAR_SIZE) {
                    // The same exchange, undone: the worn-out elytra now in hand goes back to
                    // where the spare came from, and the stack that was on the bar comes home.
                    InvUtils.quickSwap()
                        .fromId(hotbarSlot)
                        .toId(PlayerInv.inventoryIndexToMenuSlot(fromSlot));
                }

                if (notifySwap.get()) {
                    ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
                    if (worn.is(Items.ELYTRA)) info("Swapped elytra: %d durability.", left(worn));
                }

                reset();
                cooldown = swapCooldown.get();
            }
            default -> reset();
        }
    }

    /**
     * A hotbar slot to borrow, preferring one holding nothing.
     *
     * <p>Never a totem, a gapple, a pearl or a chorus fruit: those are on the bar because the
     * moment you need one you have no time to look for it, and a swap that moves them is a swap
     * that gets someone killed. Falls back to the first slot when every one of them is precious,
     * since the stack comes straight back either way.
     */
    private int borrowSlot() {
        for (int i = 0; i < PlayerInv.HOTBAR_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        for (int i = 0; i < PlayerInv.HOTBAR_SIZE; i++) {
            if (!essential(mc.player.getInventory().getItem(i))) return i;
        }
        return 0;
    }

    /** Things worth never moving off the bar, whatever else is going on. */
    private static boolean essential(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.TOTEM_OF_UNDYING
            || item == Items.GOLDEN_APPLE
            || item == Items.ENCHANTED_GOLDEN_APPLE
            || item == Items.ENDER_PEARL
            || item == Items.CHORUS_FRUIT;
    }

    // --- combat protection ---------------------------------------------------

    /**
     * Wears a chestplate for a few seconds after being hit.
     *
     * <p>Driven by {@code hurtTime}, which the server sets on every hit and which counts down on
     * its own: a rise means a new hit rather than the same one still being read.
     */
    private void protection() {
        int hurt = mc.player.hurtTime;

        if (hurt > 0 && hurt > lastHurt) {
            lastHurt = hurt;

            if (!protecting && guardStage == 0
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                int chestplate = bestChestplate();
                if (chestplate != -1) {
                    storedElytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).copy();
                    guardSlot = chestplate;
                    guardStage = 1;
                    stageTicks = 0;
                    protecting = true;
                    protectionTicks = hitProtectionDuration.get();
                    if (notifySwap.get()) info("Hit; wearing a chestplate.");
                }
            } else if (protecting) {
                // Hit again while covered: start the clock over rather than surfacing mid-fight.
                protectionTicks = hitProtectionDuration.get();
            }
        }

        if (hurt < lastHurt) lastHurt = hurt;

        if (guardStage != 0) {
            guard();
            return;
        }

        if (!protecting) return;

        if (--protectionTicks > 0) return;
        if (!autoSwapBack.get()) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            protecting = false;
            return;
        }

        int elytra = findStoredElytra();
        if (elytra == -1) {
            protecting = false;
            return;
        }

        guardSlot = elytra;
        guardStage = 1;
        stageTicks = 0;
        if (notifySwap.get()) info("Clear; back to the elytra.");
    }

    /** The chestplate swap, and the swap back. Same three stages, same reasoning. */
    private void guard() {
        if (++stageTicks < STAGE_TICKS) return;
        stageTicks = 0;

        switch (guardStage) {
            case 1 -> {
                if (guardSlot < PlayerInv.HOTBAR_SIZE) {
                    hotbarSlot = guardSlot;
                    guardStage = 2;
                    return;
                }
                hotbarSlot = borrowSlot();
                InvUtils.quickSwap()
                    .fromId(hotbarSlot)
                    .toId(PlayerInv.inventoryIndexToMenuSlot(guardSlot));
                guardStage = 2;
            }
            case 2 -> {
                InvUtils.swap(hotbarSlot, false);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
                guardStage = 3;
            }
            default -> {
                if (guardSlot >= PlayerInv.HOTBAR_SIZE) {
                    InvUtils.quickSwap()
                        .fromId(hotbarSlot)
                        .toId(PlayerInv.inventoryIndexToMenuSlot(guardSlot));
                }
                guardStage = 0;
                guardSlot = -1;
                hotbarSlot = -1;

                // Covered means the elytra half stays out of the way; back on the elytra means
                // it can look again.
                protecting = !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            }
        }
    }

    /** The best chestplate carried, or -1. */
    private int bestChestplate() {
        int best = -1;
        int bestValue = 0;

        for (int i = 0; i < PlayerInv.MAIN_SIZE; i++) {
            int value = chestplateValue(mc.player.getInventory().getItem(i));
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    /** What a chestplate is worth wearing; 0 for anything that is not one. */
    private int chestplateValue(ItemStack stack) {
        Item item = stack.getItem();

        if (item == Items.NETHERITE_CHESTPLATE) return prioritizeNetherite.get() ? 1000 : 400;
        if (item == Items.DIAMOND_CHESTPLATE) return 300;
        if (item == Items.IRON_CHESTPLATE) return 200;
        if (item == Items.CHAINMAIL_CHESTPLATE) return 150;
        if (item == Items.GOLDEN_CHESTPLATE) return 100;
        if (item == Items.LEATHER_CHESTPLATE) return 50;
        return 0;
    }

    /**
     * The elytra that was taken off, by how worn it is.
     *
     * <p>Damage within a few points of what was stored, because a couple of ticks of flight can
     * have gone by. Any elytra at all as a fallback: wearing the wrong one beats wearing none
     * because the right one could not be told apart.
     */
    private int findStoredElytra() {
        for (int i = 0; i < PlayerInv.MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA)
                && Math.abs(stack.getDamageValue() - storedElytra.getDamageValue()) <= 5) {
                return i;
            }
        }
        for (int i = 0; i < PlayerInv.MAIN_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.ELYTRA)) return i;
        }
        return -1;
    }
}
