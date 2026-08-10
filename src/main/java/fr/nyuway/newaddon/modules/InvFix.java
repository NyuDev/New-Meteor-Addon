package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * 2b2tInvFix - the inventory quirks of a nineteen-year-old anarchy server, worked around.
 *
 * <p>Brought over from BepHax, which is where these were worked out. Nothing here is a cheat:
 * each one stops the client doing something the server will not accept, and which costs you a
 * kick or a ghosted item when it does it.
 *
 * <h2>Why this is a module and not just always on</h2>
 * All three are wrong anywhere else. Shift-clicking into a full container is fine on a normal
 * server, and refusing the click would be an unexplained dead input; the bundle workaround is
 * only correct against 2b2t's own reading of the packet. So they are switched on deliberately,
 * and the module stays off until you are playing there.
 *
 * <p>The work itself is in the mixins, which read these settings back through the statics below.
 * A mixin cannot hold state of its own worth speaking of and should not be deciding policy.
 */
public class InvFix extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> preventFullContainerClicks = sgGeneral.add(new BoolSetting.Builder()
        .name("prevent-full-container-clicks")
        .description("Drop a shift-click when there is nowhere for the stack to land. The " +
                     "server disagrees with the client about a move that cannot happen, and on " +
                     "2b2t that disagreement is a kick.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> fixUnstackableDrag = sgGeneral.add(new BoolSetting.Builder()
        .name("fix-unstackable-dragging")
        .description("Refuse to drag an unstackable item across slots. The drag spreads one " +
                     "item over several slots, which cannot be done with something that does " +
                     "not stack, and what you are left looking at is a ghost.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> openInterval = sgGeneral.add(new IntSetting.Builder()
        .name("container-open-interval")
        .description("Milliseconds between two container opens. 2b2t drops a connection that " +
                     "asks too fast, and clicking down a row of chests is the easiest way to " +
                     "ask too fast. Zero turns it off.")
        .defaultValue(250).min(0).max(2000).sliderRange(0, 1000)
        .build());

    /** When a container was last opened, so the next one can be made to wait its turn. */
    private static long lastOpen;

    /**
     * Whether a container may be opened right now.
     *
     * <p>Called from the mixin that sees the right-click. A refused open is a click that did
     * nothing, which is the same thing that happens when you click a chest that is not there -
     * and far better than being dropped from the server for a second.
     */
    public static boolean mayOpenContainer() {
        InvFix module = get();
        if (module == null || !module.isActive()) return true;

        int interval = module.openInterval.get();
        if (interval <= 0) return true;

        long now = System.currentTimeMillis();
        if (now - lastOpen < interval) return false;

        lastOpen = now;
        return true;
    }

    public InvFix() {
        super(NewAddon.CATEGORY, "2b2t-inv-fix",
            "Works around 2b2t's inventory handling: full-container clicks and unstackable drags.");
    }

    // --- read by the mixins -------------------------------------------------

    private static InvFix get() {
        return Modules.get() == null ? null : Modules.get().get(InvFix.class);
    }

    public static boolean shouldPreventFullContainerClicks() {
        InvFix module = get();
        return module != null && module.isActive() && module.preventFullContainerClicks.get();
    }

    public static boolean shouldFixUnstackableDrag() {
        InvFix module = get();
        return module != null && module.isActive() && module.fixUnstackableDrag.get();
    }
}
