package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
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

        int free = firstEmptyHotbarSlot(mc);
        if (free == -1) return false;

        InvUtils.move().from(found.slot()).toHotbar(free);
        return true;
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
            for (int j = HOTBAR_SIZE; j < MAIN_SIZE; j++) {
                if (inv.getItem(j).isEmpty()) {
                    InvUtils.move().fromHotbar(i).to(j);
                    if (loans != null) loans.record(mc, i, j);
                    return true;
                }
            }
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
