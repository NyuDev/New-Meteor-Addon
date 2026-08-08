package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.util.ArrayList;
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

    private final LiveMessage module;
    private final LiveStore store;
    private final Consumer<UUID> onOpen;

    /** What the list shows, rebuilt each frame: section headers and the rows under them. */
    private final List<Row> rows = new ArrayList<>();

    /** One line. A header is a caption; a row with a peer is clickable. */
    private record Row(String header, UUID peer) { }

    private int scroll;

    public LiveBuddyWindow(LiveMessage module, LiveStore store, Consumer<UUID> onOpen,
                           int screenWidth, int screenHeight) {
        super(screenWidth, screenHeight, LiveColors.rgb(120, 120, 200));
        this.module = module;
        this.store = store;
        this.onOpen = onOpen;

        this.title = "Messages";
        this.closeButton = false;
        this.w = 180;
        this.h = 260;
        this.minW = 140;
        this.x = 8;
        this.y = 8;
        restorePlace("buddies", screenWidth, screenHeight);
    }

    @Override
    public void mouseReleased() {
        super.mouseReleased();
        rememberPlace("buddies");
    }

    public void scroll(int lines) {
        scroll = Math.max(0, scroll + lines);
    }

    /**
     * Recent conversations first, then everyone else on the server.
     *
     * <p>The two answer different questions - who was I talking to, and who could I talk
     * to - so they are separate sections rather than one merged list. Anyone already in a
     * conversation is left out of the server section so they appear once, not twice.
     */
    private void buildRows() {
        rows.clear();

        List<UUID> peers = store.peers();
        if (!peers.isEmpty()) {
            rows.add(new Row("Recent", null));
            for (UUID peer : peers) rows.add(new Row(null, peer));
        }

        List<UUID> others = module.onlinePlayers();
        others.removeAll(peers);
        if (!others.isEmpty()) {
            rows.add(new Row("On the server", null));
            for (UUID peer : others) rows.add(new Row(null, peer));
        }
    }

    private int visibleRows() {
        return (h - TITLEBAR - PAD * 2) / ROW;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        boolean close = super.mouseClicked(mouseX, mouseY, button);

        int shown = visibleRows();
        for (int i = 0; i < shown && i + scroll < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            if (row.peer() == null) continue;

            if (inRect(PAD, TITLEBAR + PAD + i * ROW, w - PAD * 2, ROW, mouseX, mouseY)) {
                onOpen.accept(row.peer());
                return false;
            }
        }

        return close;
    }

    @Override
    public void draw(LiveCanvas c) {
        super.draw(c);

        float alpha = openProgress();
        buildRows();

        if (rows.isEmpty()) {
            c.text("Nobody yet.", x + PAD + 2, y + TITLEBAR + PAD + 2,
                LiveCanvas.withAlpha(LiveColors.rgb(96, 96, 96), alpha));
            return;
        }

        int shown = visibleRows();
        c.clip(x + PAD, y + TITLEBAR + PAD, w - PAD * 2, shown * ROW);

        for (int i = 0; i < shown && i + scroll < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            int rowY = y + TITLEBAR + PAD + i * ROW;

            if (row.peer() == null) {
                c.text(row.header(), x + PAD + 2, rowY + 3,
                    LiveCanvas.withAlpha(LiveColors.rgb(150, 150, 150), alpha));
                continue;
            }

            UUID peer = row.peer();

            if (inRect(PAD, TITLEBAR + PAD + i * ROW, w - PAD * 2, ROW, lastMouseX, lastMouseY)) {
                c.box(x + PAD, rowY, w - PAD * 2, ROW,
                    LiveCanvas.withAlpha(LiveColors.rgb(64, 64, 64), alpha));
            }

            boolean online = module.isOnline(peer);
            c.box(x + PAD + 2, rowY + 4, 5, 5, LiveCanvas.withAlpha(
                online ? LiveColors.rgb(60, 148, 100) : LiveColors.rgb(80, 80, 80), alpha));

            // Colour means here now. Everyone offline greys out, so the list answers the
            // question it is opened to answer - who can I reach - at a glance, rather than
            // being a wall of colour where every name competes equally.
            int nameColor = online ? module.colorOf(peer) : LiveColors.rgb(110, 110, 110);

            String label = module.displayName(peer);
            if (module.isIgnored(peer)) label = "- " + label;

            c.text(label, x + PAD + 11, rowY + 3, LiveCanvas.withAlpha(nameColor, alpha));
        }

        c.unclip();
    }
}
