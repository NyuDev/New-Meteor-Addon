package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.modules.InvFix;
import fr.nyuway.newaddon.utils.Stacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops a shift-click that has nowhere to land, for {@link InvFix}.
 *
 * <p>Cancelled here rather than at the packet, because the client acts first and tells the
 * server afterwards: stopping only the packet would leave the client showing a move the server
 * never heard about. Nothing happens at all this way, which is what a player would see if they
 * had not clicked.
 *
 * <h2>The one version split</h2>
 * The method was renamed at 26.1 - {@code handleInventoryMouseClick} to
 * {@code handleContainerInput} - and {@code ClickType} became {@code ContainerInput} with it.
 * Nothing else about it moved between 1.20.1 and 26.1.2; the signature is otherwise identical on
 * every version this builds for, which was checked against the jars rather than assumed.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    /** Player inventory slots shown by any container menu: three rows and the hotbar. */
    private static final int PLAYER_SLOTS = 36;

    //? if <26.1 {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true, require = 0)
    private void newAddon$blockFullContainerClick(int containerId, int slotId, int button,
                                                  net.minecraft.world.inventory.ClickType type,
                                                  Player player, CallbackInfo info) {
        if (type != net.minecraft.world.inventory.ClickType.QUICK_MOVE) return;
        if (newAddon$wouldGoNowhere(containerId, slotId)) info.cancel();
    }
    //?} else {
    /*@Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true, require = 0)
    private void newAddon$blockFullContainerClick(int containerId, int slotId, int button,
                                                  net.minecraft.world.inventory.ContainerInput type,
                                                  Player player, CallbackInfo info) {
        if (type != net.minecraft.world.inventory.ContainerInput.QUICK_MOVE) return;
        if (newAddon$wouldGoNowhere(containerId, slotId)) info.cancel();
    }
    *///?}

    /**
     * Whether this shift-click has no destination: the half of the window it would move the
     * stack into has neither an empty slot for it nor a matching stack with room left.
     */
    private boolean newAddon$wouldGoNowhere(int containerId, int slotId) {
        if (!InvFix.shouldPreventFullContainerClicks()) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu.containerId != containerId) return false;
        if (slotId < 0 || slotId >= menu.slots.size()) return false;

        ItemStack stack = menu.getSlot(slotId).getItem();
        if (stack.isEmpty()) return false;

        int playerStart = menu.slots.size() - PLAYER_SLOTS;

        // A stack in the player's half goes to the container, and the other way round. The
        // player's own inventory menu has no container half, and there playerStart is 9 - the
        // crafting grid and armour - which is a half it will never be shift-clicked into
        // anyway, since those slots take almost nothing.
        return slotId >= playerStart
            ? newAddon$isFull(menu, stack, 0, playerStart, true)
            : newAddon$isFull(menu, stack, playerStart, menu.slots.size(), false);
    }

    /**
     * Whether a range of slots has nowhere to put this stack.
     *
     * @param checkMayPlace ask each slot whether it accepts the item at all. Worth doing for the
     *                      container half, where a furnace fuel slot or a beacon will refuse
     *                      most things; the player's own slots take anything.
     */
    private boolean newAddon$isFull(AbstractContainerMenu menu, ItemStack stack,
                                    int from, int to, boolean checkMayPlace) {
        for (int i = from; i < to; i++) {
            Slot slot = menu.getSlot(i);
            if (checkMayPlace && !slot.mayPlace(stack)) continue;

            ItemStack there = slot.getItem();
            if (there.isEmpty()) return false;
            if (Stacks.same(there, stack) && Stacks.hasRoom(there)) return false;
        }
        return true;
    }
}
