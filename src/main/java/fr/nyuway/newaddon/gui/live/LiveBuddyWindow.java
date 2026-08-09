package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The buddy list, ported from Livemessage's {@code ManeWindow}.
 *
 * <p>Two foldable sections: the people you have talked to, a plain timeline newest first, and
 * everyone else on the server, alphabetical. Each row is a head ringed green or grey by presence
 * and a name coloured by relationship. A search box along the bottom filters both live as you
 * type; a scrollbar down the right rides a list too long to fit. Clicking a row opens its chat;
 * clicking a header folds the section away.
 *
 * <p>This window has no close button, as upstream's does not: it is the way back to everything
 * else, and a messenger you can shut the contact list of is one you can get lost in.
 */
public class LiveBuddyWindow extends LiveWindow {

    /** The count of waiting messages. The one yellow in the window, so it is the one you see. */
    private static final int UNREAD = 0xF2C94C;

    private static final int ROW = 14;
    private static final int PAD = 4;
    private static final int SEARCH_H = 13;
    private static final int SCROLLBAR_W = 6;

    private final LiveMessage module;
    private final LiveStore store;
    private final Consumer<UUID> onOpen;

    /** What the list shows, rebuilt each frame: section headers and the rows under them. */
    private final List<Row> rows = new ArrayList<>();

    /** One line: a section header carrying a collapse key, or a clickable peer. */
    private record Row(String header, UUID peer, String section) { }

    /** Sections the user has folded shut, by key, so a long list can be tamed. */
    private final Set<String> collapsed = new HashSet<>();

    private int scroll;

    /** Live search text, typed into while this window is focused; filters both sections at once. */
    private final StringBuilder query = new StringBuilder();

    /** Scrollbar geometry from the last draw, read by the drag that moves it. */
    private boolean draggingBar;
    private int trackTop;
    private int trackH;
    private int maxScroll;

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
        draggingBar = false;
        super.mouseReleased();
        rememberPlace("buddies");
    }

    public void scroll(int lines) {
        scroll = Math.max(0, scroll + lines);
    }

    public void type(char c) {
        if (query.length() < 32) {
            query.append(c);
            scroll = 0;
        }
    }

    public void backspace() {
        if (query.length() > 0) {
            query.setLength(query.length() - 1);
            scroll = 0;
        }
    }

    /**
     * Recent conversations first, then everyone else on the server.
     *
     * <p>The two answer different questions - who was I talking to, and who could I talk
     * to - so they are separate sections rather than one merged list. Recent is a plain timeline,
     * newest first; the server section is alphabetical. A non-empty search filters both and
     * opens any folded section so a match is never hidden. Anyone already in a conversation is
     * dropped from the server section so they appear once, not twice.
     */
    private void buildRows() {
        rows.clear();

        String q = query.toString().trim().toLowerCase();
        boolean searching = !q.isEmpty();

        // Recent stays in its last-activity timeline; the server list is sorted by name, resolved
        // once each rather than once per comparison.
        List<UUID> peers = store.peers();
        List<UUID> others = module.onlinePlayers();
        others.removeAll(peers);

        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        for (UUID p : others) names.put(p, module.displayName(p).toLowerCase());
        others.sort(Comparator.comparing(names::get));

        if (searching) {
            peers.removeIf(p -> !matches(p, q));
            others.removeIf(p -> !names.get(p).contains(q));
        }

        if (!peers.isEmpty()) {
            rows.add(new Row("Recent (" + peers.size() + ")", null, "recent"));
            if (searching || !collapsed.contains("recent")) {
                for (UUID peer : peers) rows.add(new Row(null, peer, null));
            }
        }

        if (!others.isEmpty()) {
            rows.add(new Row("On the server (" + others.size() + ")", null, "server"));
            if (searching || !collapsed.contains("server")) {
                for (UUID peer : others) rows.add(new Row(null, peer, null));
            }
        }
    }

    private boolean matches(UUID peer, String lowerQuery) {
        return module.displayName(peer).toLowerCase().contains(lowerQuery);
    }

    private int visibleRows() {
        return Math.max(1, (h - TITLEBAR - SEARCH_H - PAD * 3) / ROW);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // The scrollbar is claimed before the window's own drag, so a grab on it never drags the
        // window instead.
        if (inScrollbar(mouseX, mouseY)) {
            draggingBar = true;
            scrollTo(mouseY);
            return false;
        }

        boolean close = super.mouseClicked(mouseX, mouseY, button);

        int shown = visibleRows();
        for (int i = 0; i < shown && i + scroll < rows.size(); i++) {
            if (!inRect(PAD, TITLEBAR + PAD + i * ROW, w - PAD * 2 - SCROLLBAR_W, ROW, mouseX, mouseY)) continue;

            Row row = rows.get(i + scroll);
            if (row.peer() != null) {
                onOpen.accept(row.peer());
            } else if (row.section() != null) {
                if (!collapsed.remove(row.section())) collapsed.add(row.section());
            }
            return false;
        }

        return close;
    }

    @Override
    public void mouseMoved(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        super.mouseMoved(mouseX, mouseY, screenWidth, screenHeight);
        if (draggingBar) scrollTo(mouseY);
    }

    @Override
    public void draw(LiveCanvas c) {
        super.draw(c);

        float alpha = openProgress();
        buildRows();

        int shown = visibleRows();
        // A collapse or a search can leave the scroll past the new, shorter end; pull it back so
        // the list is never scrolled into blank space.
        scroll = Math.min(scroll, Math.max(0, rows.size() - shown));

        trackTop = y + TITLEBAR + PAD;
        trackH = shown * ROW;
        maxScroll = Math.max(0, rows.size() - shown);

        int contentW = w - PAD * 2 - SCROLLBAR_W;

        if (rows.isEmpty()) {
            c.text(query.length() > 0 ? "No match." : "Nobody yet.", x + PAD + 2,
                y + TITLEBAR + PAD + 2, LiveCanvas.withAlpha(LiveColors.rgb(96, 96, 96), alpha));
        } else {
            c.clip(x + PAD, y + TITLEBAR + PAD, contentW, trackH);

            for (int i = 0; i < shown && i + scroll < rows.size(); i++) {
                Row row = rows.get(i + scroll);
                int rowY = y + TITLEBAR + PAD + i * ROW;
                boolean hovered = inRect(PAD, TITLEBAR + PAD + i * ROW, contentW, ROW,
                    lastMouseX, lastMouseY);

                if (row.peer() == null) {
                    if (hovered) {
                        c.box(x + PAD, rowY, contentW, ROW,
                            LiveCanvas.withAlpha(LiveColors.rgb(48, 48, 48), alpha));
                    }
                    int grey = LiveColors.rgb(150, 150, 150);
                    triangle(c, x + PAD + 2, rowY + 4, !collapsed.contains(row.section()),
                        LiveCanvas.withAlpha(grey, alpha));
                    c.text(row.header(), x + PAD + 11, rowY + 3, LiveCanvas.withAlpha(grey, alpha));
                    continue;
                }

                UUID peer = row.peer();

                if (hovered) {
                    c.box(x + PAD, rowY, contentW, ROW,
                        LiveCanvas.withAlpha(LiveColors.rgb(64, 64, 64), alpha));
                }

                if (alpha >= 1f) {
                    c.head(peer, x + PAD + 2, rowY + 3, 8);
                } else {
                    c.box(x + PAD + 2, rowY + 3, 8, 8,
                        LiveCanvas.withAlpha(LiveColors.rgb(50, 50, 50), alpha));
                }

                String label = module.displayName(peer);
                if (module.isIgnored(peer)) label = "- " + label;

                c.text(label, x + PAD + 14, rowY + 3,
                    LiveCanvas.withAlpha(module.nameColor(peer), alpha));

                // How many are waiting, in yellow after the name. Yellow because nothing else in
                // this window is: it has to be findable at a glance down a long list, which is
                // the whole reason it is here and not only inside the conversation.
                int unread = module.unread(peer);
                if (unread > 0) {
                    String badge = unread > 99 ? "99+" : Integer.toString(unread);
                    c.text(badge, x + PAD + 18 + c.width(label), rowY + 3,
                        LiveCanvas.withAlpha(UNREAD, alpha));
                }
            }

            c.unclip();
            drawScrollbar(c, shown, alpha);
        }

        drawSearch(c, alpha);
    }

    private void drawScrollbar(LiveCanvas c, int shown, float alpha) {
        if (rows.size() <= shown) return;

        int barX = x + w - PAD - SCROLLBAR_W;
        c.box(barX, trackTop, SCROLLBAR_W, trackH, LiveCanvas.withAlpha(LiveColors.rgb(28, 28, 28), alpha));

        int thumbH = Math.max(12, trackH * shown / rows.size());
        float p = maxScroll == 0 ? 0f : scroll / (float) maxScroll;
        int thumbY = trackTop + Math.round((trackH - thumbH) * p);

        boolean hot = draggingBar || inScrollbar(lastMouseX, lastMouseY);
        int g = hot ? 130 : 90;
        c.box(barX + 1, thumbY, SCROLLBAR_W - 2, thumbH, LiveCanvas.withAlpha(LiveColors.rgb(g, g, g), alpha));
    }

    private boolean inScrollbar(int mx, int my) {
        if (maxScroll <= 0) return false;
        int barX = x + w - PAD - SCROLLBAR_W;
        return mx >= barX && mx <= barX + SCROLLBAR_W && my >= trackTop && my <= trackTop + trackH;
    }

    /** Maps a cursor height on the track to a scroll: the top is the newest row, the bottom the oldest. */
    private void scrollTo(int my) {
        if (trackH <= 0) return;
        float frac = Math.max(0f, Math.min(1f, (my - trackTop) / (float) trackH));
        scroll = Math.round(maxScroll * frac);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private void drawSearch(LiveCanvas c, float alpha) {
        int sy = y + h - PAD - SEARCH_H;
        c.box(x + PAD - 1, sy - 1, w - PAD * 2 + 2, SEARCH_H + 2,
            LiveCanvas.withAlpha(LiveColors.rgb(64, 64, 64), alpha));
        c.box(x + PAD, sy, w - PAD * 2, SEARCH_H, LiveCanvas.withAlpha(LiveColors.rgb(24, 24, 24), alpha));

        c.clip(x + PAD + 1, sy, w - PAD * 2 - 2, SEARCH_H);
        magnifier(c, x + PAD + 2, sy + 3, LiveCanvas.withAlpha(LiveColors.rgb(130, 130, 130), alpha));

        int textX = x + PAD + 11;
        if (query.length() == 0 && !active) {
            c.text("Search", textX, sy + 3, LiveCanvas.withAlpha(LiveColors.rgb(110, 110, 110), alpha));
        } else {
            String shown = query + (active && System.currentTimeMillis() % 1000 < 500 ? "_" : "");
            c.text(shown, textX, sy + 3, LiveCanvas.withAlpha(0xFFFFFF, alpha));
        }
        c.unclip();
    }

    /** A small magnifying glass for the search box. */
    private static void magnifier(LiveCanvas c, int x, int y, int color) {
        int[] rows = {0b0111000, 0b1000100, 0b1000100, 0b1000100, 0b0111000, 0b0001100, 0b0000110};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(x + i, y + r, 1, 1, color);
    }

    /** A collapse chevron: pointing down when the section is open, right when it is folded. */
    private static void triangle(LiveCanvas c, int x, int y, boolean open, int color) {
        if (open) {
            c.box(x, y, 5, 1, color);
            c.box(x + 1, y + 1, 3, 1, color);
            c.box(x + 2, y + 2, 1, 1, color);
        } else {
            c.box(x, y - 1, 1, 1, color);
            c.box(x, y, 2, 1, color);
            c.box(x, y + 1, 3, 1, color);
            c.box(x, y + 2, 2, 1, color);
            c.box(x, y + 3, 1, 1, color);
        }
    }
}
