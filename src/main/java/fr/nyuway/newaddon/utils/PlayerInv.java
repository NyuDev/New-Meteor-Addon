package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * Questions about, and small rearrangements of, the player's own inventory.
 *
 * <p>Split out of the modules because none of it depends on module state: every method takes
 * the client and answers from the inventory alone. Meteor's own {@code InvUtils} covers
 * finding and moving, but not counting totals, reading armour, or judging elytra condition.
 */
public final class PlayerInv {

    /** Inventory indices below this are the hotbar. */
    public static final int HOTBAR_SIZE = 9;
    /** Inventory indices below this are hotbar plus main storage, i.e. not armour or offhand. */
    public static final int MAIN_SIZE = 36;

    private PlayerInv() {
    }

    /** Total items on the player matching a predicate, counting stack sizes. */
    public static int countMatching(Minecraft mc, Predicate<ItemStack> match) {
        var inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && match.test(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Total of one item on the player, counting stack sizes. */
    public static int count(Minecraft mc, Item item) {
        return countMatching(mc, stack -> stack.is(item));
    }

    /** Remaining durability of the worn elytra, or {@link Integer#MAX_VALUE} when none is worn. */
    public static int wornElytraDurability(Minecraft mc) {
        ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (equipped.is(Items.ELYTRA)) return equipped.getMaxDamage() - equipped.getDamageValue();
        return Integer.MAX_VALUE;
    }

    /** A spare elytra with durability left, in hotbar or storage, or -1. */
    public static int findSpareElytra(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue()) > 0) return i;
        }
        return -1;
    }

    /**
     * First damaged elytra carrying Mending, or -1.
     *
     * <p>Undamaged ones and ones without Mending are skipped: throwing XP at either achieves
     * nothing, and cycling them through the armour slot would waste the whole session.
     */
    public static int findDamagedMendingElytra(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(Items.ELYTRA)) continue;
            if (stack.getDamageValue() <= 0) continue;
            if (!Enchants.hasMending(stack)) continue;
            return i;
        }
        return -1;
    }

    /** Inventory slot of a Silk Touch tool anywhere on the player, or -1. */
    public static int findSilkTouch(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (Enchants.hasSilkTouch(stack)) return i;
        }
        return -1;
    }

    /** First empty hotbar slot, or -1. */
    public static int firstEmptyHotbarSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Ensures a matching item sits on the hotbar, moving one down from storage if needed.
     *
     * @return true when it is already there or a move was issued
     */
    public static boolean moveToHotbar(Minecraft mc, Predicate<ItemStack> match) {
        FindItemResult found = InvUtils.find(match);
        if (!found.found()) return false;
        if (found.isHotbar()) return true;

        if (firstEmptyHotbarSlot(mc) == -1) return false;
        if (!openInventory(mc)) return false;

        // Shift-click, not a carry: in the player's own menu that means storage to hotbar, which
        // is the whole of the request. See Containers.quickMove for why the two-click carry
        // never landed - the second click argues with a state id the server has moved past, and
        // the item blinks back where it was.
        InvUtils.shiftClick().slotId(inventoryIndexToMenuSlot(found.slot()));
        return true;
    }

    /**
     * Puts the player's own inventory screen up, so its clicks come from somewhere.
     *
     * <p>Not needed by the protocol - the player's menu is always open server-side, and the
     * clicks are accepted either way. It is needed by anyone watching: a stream of slot clicks
     * from a client with no inventory open is not something a player can produce, and this
     * module runs on servers where that is the sort of thing people look for.
     *
     * <p>Refuses while a chest or shulker is open, because putting a screen up there would close
     * it, and the run is in the middle of using it.
     *
     * <h2>When another screen is already up</h2>
     * It used to refuse outright, which meant that alt-tabbing away - and so putting the pause
     * menu up - quietly stopped a resupply half way through, waiting for a screen that was never
     * going to arrive. Two different answers now, because the two screens are not alike:
     *
     * <ul>
     *   <li><b>The chat</b> is left exactly where it is, and the moves go ahead without a screen
     *       of their own. Closing the chat somebody is typing into, to put up an inventory nobody
     *       asked for, is a worse surprise than a missing screen - and the clicks work either
     *       way, since the player's menu is open server-side whether or not anything is drawn.</li>
     *   <li><b>Anything else</b> - the pause menu above all - is replaced. Nothing is lost by
     *       doing so, and it is what makes the run look the same whether or not you were watching
     *       it, which is the whole point of putting the screen up at all.</li>
     * </ul>
     *
     * @return true when a click will reach the slots it is aimed at
     */
    public static boolean openInventory(Minecraft mc) {
        if (!useScreen) return true;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return false;

        if (mc.screen instanceof InventoryScreen) {
            wantedAt = mc.level == null ? 0 : mc.level.getGameTime();
            return true;
        }

        // Never steal what somebody is typing into. The work still happens; only the appearance
        // is skipped, which nobody is looking at anyway with a text box in front of them.
        if (mc.screen instanceof ChatScreen) return true;

        mc.setScreen(new InventoryScreen(mc.player));

        // Belt and braces. Another mod can refuse a screen change, and a run that stops because
        // the picture did not come up would be the original bug wearing a different hat.
        wantedAt = mc.level == null ? 0 : mc.level.getGameTime();
        return true;
    }

    /**
     * Whether inventory moves put the screen up first. Static because this is static, and
     * because it is one answer for the client rather than one per module.
     */
    private static boolean useScreen = true;

    public static void setUseInventoryScreen(boolean value) {
        useScreen = value;
    }

    /** Game time of the last request for the inventory screen, so it can close itself after. */
    private static long wantedAt;

    /**
     * Closes the inventory screen once nothing has asked for it for a while.
     *
     * <p>Called once a tick by whatever is running, rather than by each move: a move does not
     * know whether another is coming, and opening and closing the screen between every click
     * would be its own kind of strange to watch.
     */
    public static void closeInventoryWhenIdle(Minecraft mc, int idleTicks) {
        if (!(mc.screen instanceof InventoryScreen)) return;
        if (mc.level != null && mc.level.getGameTime() - wantedAt < idleTicks) return;
        mc.setScreen(null);
    }

    /** Closes the inventory screen if that is what is up, leaving any other screen alone. */
    public static void closeInventory(Minecraft mc) {
        if (mc.screen instanceof InventoryScreen) mc.setScreen(null);
    }

    /**
     * Frees a hotbar slot by pushing one non-essential stack into storage.
     *
     * <p>A hotbar packed with fireworks would otherwise block placing the next chest or
     * shulker. The shulker, ender chest and Silk Touch tool are the ones worth keeping to
     * hand, so they are never the stack chosen to move.
     *
     * @return true when a slot is free, either already or after the move
     */
    public static boolean freeHotbarSlot(Minecraft mc) {
        return freeHotbarSlot(mc, null);
    }

    /**
     * Frees a hotbar slot, pushing whatever is in it down into the inventory.
     *
     * @param loans where to record the displacement, so it can be undone later. Whatever gets
     *              pushed down is the player's sword or bow, in the place their hands know;
     *              borrowing the slot without writing down where it went is how a routine that
     *              leaves no trace on the ground still leaves the hotbar rearranged.
     */
    public static boolean freeHotbarSlot(Minecraft mc, SlotLoans loans) {
        var inv = mc.player.getInventory();
        if (firstEmptyHotbarSlot(mc) != -1) return true;

        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (Containers.isShulker(stack) || stack.is(Items.ENDER_CHEST)
                || Enchants.hasSilkTouch(stack)) {
                continue;
            }

            // Where the shift-click will put it, worked out before the click rather than chosen
            // by it: the loan has to name a slot to bring the stack back from later, and vanilla
            // fills the pack from its first empty slot.
            int home = -1;
            for (int j = HOTBAR_SIZE; j < MAIN_SIZE; j++) {
                if (inv.getItem(j).isEmpty()) {
                    home = j;
                    break;
                }
            }
            if (home == -1) continue;
            if (!openInventory(mc)) return false;

            InvUtils.shiftClick().slotId(inventoryIndexToMenuSlot(i));
            if (loans != null) loans.record(mc, i, home);
            return true;
        }
        return false;
    }

    /**
     * Junk worth throwing away to make room, and nothing else.
     *
     * <p>An allow-list rather than a list of things to keep, on purpose. Deciding what is
     * worthless by rule - unenchanted, stackable, cheap - gets it wrong the one time it matters
     * and throws away a stack of obsidian on an anarchy server. Everything here is something you
     * pick up by accident and would not walk back for. If none of it is on you, nothing is
     * dropped and the routine says so instead.
     */
    private static final java.util.Set<Item> JUNK = java.util.Set.of(
        Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.GRAVEL, Items.SAND,
        Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE,
        Items.STONE, Items.END_STONE, Items.ROTTEN_FLESH, Items.STRING, Items.STICK,
        Items.FLINT, Items.BONE, Items.SEAGRASS, Items.KELP
    );

    /** First inventory index holding nothing, hotbar included, or -1 when the pack is full. */
    public static int firstEmptyInventorySlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * The smallest stack of junk on the player, or -1.
     *
     * <p>Smallest so that as little as possible is on the ground: the point is one free slot,
     * not a tidy inventory, and whatever is dropped has to survive being walked away from.
     */
    public static int findJunkSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        int best = -1;
        int bestCount = Integer.MAX_VALUE;

        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !JUNK.contains(stack.getItem())) continue;
            if (stack.isEnchanted() || stack.isDamaged()) continue;
            if (stack.getCount() < bestCount) {
                bestCount = stack.getCount();
                best = i;
            }
        }
        return best;
    }

    /**
     * The hotbar slot least likely to do something unwanted while a container is right-clicked,
     * or -1 when the hotbar is empty.
     *
     * <p>Only needed when the hand cannot be emptied at all. Vanilla opens a container whatever
     * is held - the block's use wins over the item's - so the only real hazard is a click that
     * misses the container and lands on the ground with a block in hand. A damageable tool is
     * therefore the best thing to be holding: it cannot be placed and does nothing to a chest.
     * Anything that is not a block comes next, and a block only if there is nothing else.
     */
    public static int safestHotbarSlotToHold(Minecraft mc) {
        var inv = mc.player.getInventory();
        int nonBlock = -1;
        int any = -1;

        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) return i;
            if (any == -1) any = i;

            if (stack.isDamageableItem() && !stack.is(Items.ELYTRA)) return i;
            if (nonBlock == -1 && !(stack.getItem() instanceof BlockItem)) nonBlock = i;
        }

        return nonBlock != -1 ? nonBlock : any;
    }

    /**
     * Menu slot id for an inventory index, in the player's own inventory menu.
     *
     * <p>The two numbering schemes disagree: the hotbar is indices 0-8 in the inventory but
     * slots 36-44 in the menu, while storage happens to line up. Getting this wrong moves the
     * wrong stack, so it lives in one place.
     */
    public static int inventoryIndexToMenuSlot(int inventoryIndex) {
        return inventoryIndex < HOTBAR_SIZE ? inventoryIndex + MAIN_SIZE : inventoryIndex;
    }
}
