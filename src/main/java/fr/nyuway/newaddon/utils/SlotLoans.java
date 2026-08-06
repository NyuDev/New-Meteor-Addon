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
     * Makes one move towards putting the borrowed items back, newest first.
     *
     * <p>The slot being returned to is often not free any more: freeing it is exactly what
     * made it attractive to whatever the routine needed on the bar next, so the fireworks end
     * up sitting where the sword was. Evicting that occupant is a move of its own, and the
     * loan stays on the list until the original is actually home.
     *
     * @return true while there is still something to do, so a caller can spend a tick per move
     *         rather than firing a burst of clicks the server will not keep up with
     */
    public boolean restoreOne(Minecraft mc) {
        if (mc.player == null) {
            loans.clear();
            return false;
        }

        var inv = mc.player.getInventory();

        while (!loans.isEmpty()) {
            Loan loan = loans.get(loans.size() - 1);

            ItemStack moved = inv.getItem(loan.to());
            if (moved.isEmpty() || !moved.is(loan.what())) {
                // Used up, or moved on by something else. Nothing to put back.
                loans.remove(loans.size() - 1);
                continue;
            }

            if (!inv.getItem(loan.from()).isEmpty()) {
                int spot = spotFor(inv, loan.from());
                if (spot == -1) {
                    // Nowhere to evict to. Leaving the occupant beats shuffling blindly.
                    loans.remove(loans.size() - 1);
                    continue;
                }
                InvUtils.move().from(loan.from()).to(spot);
                return true;
            }

            loans.remove(loans.size() - 1);
            InvUtils.move().from(loan.to()).to(loan.from());
            return true;
        }

        return false;
    }

    /**
     * Somewhere to put whatever took a borrowed slot.
     *
     * <p>Another hotbar slot first: the usual occupant is the fireworks the routine just
     * fetched, and Baritone can only fly with what is on the bar. The main inventory is the
     * fallback.
     */
    private static int spotFor(net.minecraft.world.entity.player.Inventory inv, int avoid) {
        for (int i = 0; i < 9; i++) {
            if (i != avoid && inv.getItem(i).isEmpty()) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }
}
