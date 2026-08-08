package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The buddy list, ported from Livemessage's {@code ManeWindow}.
 *
 * <p>Every conversation, most recent first, each drawn in that person's own colour with a dot
 * showing whether they are online. Clicking one opens its chat window.
 *
 * <p>This window has no close button, as upstream's does not: it is the way back to everything
 * else, and a messenger you can shut the contact list of is one you can get lost in.
 */
public class LiveBuddyWindow extends LiveWindow {

    private static final int ROW = 14;
    private static final int PAD = 4;

    private final LiveStore store;
    private final Consumer<UUID> onOpen;
    private final java.util.function.Predicate<UUID> onlineCheck;

    private int scroll;

    public LiveBuddyWindow(LiveStore store, Consumer<UUID> onOpen,
                           java.util.function.Predicate<UUID> onlineCheck,
                           int screenWidth, int screenHeight) {
        super(screenWidth, screenHeight, LiveColors.rgb(120, 120, 200));
        this.store = store;
        this.onOpen = onOpen;
        this.onlineCheck = onlineCheck;

        this.title = "Messages";
        this.closeButton = false;
        this.w = 180;
        this.h = 260;
        this.minW = 140;
        this.x = 8;
        this.y = 8;
    }

    public void scroll(int lines) {
        scroll = Math.max(0, scroll + lines);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        boolean close = super.mouseClicked(mouseX, mouseY, button);

        List<UUID> peers = store.peers();
        int rows = (h - TITLEBAR - PAD * 2) / ROW;

        for (int i = 0; i < rows && i + scroll < peers.size(); i++) {
            if (inRect(PAD, TITLEBAR + PAD + i * ROW, w - PAD * 2, ROW, mouseX, mouseY)) {
                onOpen.accept(peers.get(i + scroll));
                return false;
            }
        }

        return close;
    }

    @Override
    public void draw(LiveCanvas c) {
        super.draw(c);

        float alpha = openProgress();
        List<UUID> peers = store.peers();

        if (peers.isEmpty()) {
            c.text("No conversations yet.", x + PAD + 2, y + TITLEBAR + PAD + 2,
                LiveCanvas.withAlpha(LiveColors.rgb(96, 96, 96), alpha));
            return;
        }

        int rows = (h - TITLEBAR - PAD * 2) / ROW;
        c.clip(x + PAD, y + TITLEBAR + PAD, w - PAD * 2, rows * ROW);

        for (int i = 0; i < rows && i + scroll < peers.size(); i++) {
            UUID peer = peers.get(i + scroll);
            int rowY = y + TITLEBAR + PAD + i * ROW;

            boolean hovered = inRect(PAD, TITLEBAR + PAD + i * ROW, w - PAD * 2, ROW,
                lastMouseX, lastMouseY);
            if (hovered) {
                c.box(x + PAD, rowY, w - PAD * 2, ROW,
                    LiveCanvas.withAlpha(LiveColors.rgb(64, 64, 64), alpha));
            }

            boolean online = onlineCheck.test(peer);
            c.box(x + PAD + 2, rowY + 4, 5, 5, LiveCanvas.withAlpha(
                online ? LiveColors.rgb(60, 148, 100) : LiveColors.rgb(80, 80, 80), alpha));

            c.text(store.nameOf(peer), x + PAD + 11, rowY + 3,
                LiveCanvas.withAlpha(LiveColors.windowColor(store, peer), alpha));
        }

        c.unclip();
    }
}
