package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers items moved out of the way, so they can be put back where they were.
 *
 * <h2>Why this exists</h2>
 * A resupply needs hotbar slots it does not have: the ender chest, the shulker, the pickaxe
 * and the bottles all have to be reachable. Freeing a slot means pushing whatever was there
 * into the inventory - and whatever was there is your sword, or your bow, in the position your
 * hands know. Borrowing them silently and never giving them back is how a routine that
 * otherwise leaves no trace still leaves your hotbar rearranged.
 *
 * <h2>How the return works</h2>
 * Moves are replayed in reverse, newest first, so a slot borrowed twice unwinds in order. Each
 * one is checked before it is undone: the item must still be where it was put and the original
 * slot must still be free. Anything else means the world moved on - the item was used up, or
 * something else took the slot - and forcing the move would do more damage than leaving it.
 */
public final class SlotLoans {

    /**
     * One displacement: what was in {@code from} now sits in {@code to}.
     *
     * <p>The item alone identifies it, not the whole stack: the deep comparison is named
     * differently across the versions this builds for, and "is this still the sword we moved"
     * needs nothing deeper.
     */
    private record Loan(int from, int to, Item what) { }

    private final List<Loan> loans = new ArrayList<>();

    /**
     * Records a move that should eventually be undone.
     *
     * @param from inventory index the item was taken from
     * @param to   inventory index it was put in
     */
    public void record(Minecraft mc, int from, int to) {
        if (mc.player == null) return;
        ItemStack moved = mc.player.getInventory().getItem(to);
        if (moved.isEmpty()) return;
        loans.add(new Loan(from, to, moved.getItem()));
    }

    public boolean isEmpty() {
        return loans.isEmpty();
    }

    public void clear() {
        loans.clear();
    }

    /**
     * Puts one borrowed item back, newest first.
     *
     * @return true while there is still something to do, so a caller can spend a tick per move
     *         rather than firing a burst of clicks the server will not keep up with
     */
    public boolean restoreOne(Minecraft mc) {
        if (mc.player == null) {
            loans.clear();
            return false;
        }

        while (!loans.isEmpty()) {
            Loan loan = loans.remove(loans.size() - 1);

            ItemStack now = mc.player.getInventory().getItem(loan.to());
            if (now.isEmpty() || !now.is(loan.what())) continue;
            if (!mc.player.getInventory().getItem(loan.from()).isEmpty()) continue;

            InvUtils.move().from(loan.to()).to(loan.from());
            return true;
        }

        return false;
    }
}
