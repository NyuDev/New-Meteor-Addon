package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * One conversation, ported from Livemessage's {@code ChatWindow}.
 *
 * <p>His layout, measurement for measurement: the 36-pixel avatar block at
 * {@code (3, titlebar+3)}, green when they are online and grey 128 when they are not; the name,
 * the UUID and the online line stacked at x=42; a history box outlined in grey 64 over grey 36;
 * the input over grey 24; and the scrollbar that lightens from 64 to 96 under the cursor and
 * 128 while dragged.
 *
 * <p>Messages carry a {@code <HH:mm>} stamp and a date header when the day changes, yours drawn
 * white and theirs in their own colour - which is the whole reason a colour is derived per
 * person in the first place.
 */
public class LiveChatWindow extends LiveWindow {

    private static final int BOX_X = 5;
    private static final int BOX_Y = TITLEBAR + 44;
    private static final int INPUT_H = 13;
    private static final int SCROLLBAR_W = 10;
    private static final int LINE = 12;

    private static final SimpleDateFormat DAY = new SimpleDateFormat("MMMM dd, yyyy");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("<HH:mm> ");

    private final LiveMessage module;
    private final LiveStore store;
    public final UUID peer;

    /** Typed reply. Kept here rather than in an EditBox, whose API moved twice across these versions. */
    private final StringBuilder input = new StringBuilder();

    private int scroll;

    public LiveChatWindow(LiveMessage module, LiveStore store, UUID peer,
                          int screenWidth, int screenHeight) {
        super(screenWidth, screenHeight, module.colorOf(peer));
        this.module = module;
        this.store = store;
        this.peer = peer;
        this.minW = 280;
        this.w = 420;
        this.h = 260;
        restorePlace("chat:" + peer, screenWidth, screenHeight);

        // Upstream puts these as three icons down the right edge of the header. Same place,
        // same order - friend, ignore - drawn as short labels since icons.png is not loaded.
        buttons.add(new LiveButton(46, TITLEBAR + 4, 42, 11, true, "friend",
            "Toggle friend", () -> module.toggleFriend(peer)));
        buttons.add(new LiveButton(46, TITLEBAR + 18, 42, 11, true, "ignore",
            "Toggle ignore", () -> module.toggleIgnore(peer)));
    }

    @Override
    public void mouseReleased() {
        super.mouseReleased();
        rememberPlace("chat:" + peer);
    }

    public String name() {
        return store.nameOf(peer);
    }

    public void type(char c) {
        input.append(c);
    }

    public void backspace() {
        if (input.length() > 0) input.setLength(input.length() - 1);
    }

    /** @return true when something was actually sent */
    public boolean send() {
        String text = input.toString().trim();
        if (text.isEmpty()) return false;

        input.setLength(0);
        module.send(name(), text);
        scroll = Integer.MAX_VALUE;
        return true;
    }

    public void scroll(int lines) {
        scroll = Math.max(0, scroll + lines);
    }

    @Override
    public void draw(LiveCanvas c) {
        boolean online = module.isOnline(peer);
        title = "[DM] " + name();

        // Re-read every frame: friending someone should recolour the window at once rather
        // than on the next open.
        primaryColor = module.colorOf(peer);

        super.draw(c);

        float alpha = openProgress();
        int foreground = active ? primaryColor : INACTIVE;

        // Avatar block. Upstream draws the real skin face here; without a skin fetch the
        // colour still carries the one thing it is read for - whether they are on.
        c.box(x + 3, y + TITLEBAR + 3, 36, 36,
            LiveCanvas.withAlpha(online ? LiveColors.rgb(60, 148, 100) : INACTIVE, alpha));

        String label = name();
        if (module.isFriend(peer)) label += " (friend)";
        if (module.isIgnored(peer)) label += " (ignored)";
        c.text(label, x + 42, y + TITLEBAR + 5, LiveCanvas.withAlpha(0xFFFFFF, alpha));
        c.text(peer.toString(), x + 42, y + TITLEBAR + 16, LiveCanvas.withAlpha(INACTIVE, alpha));
        c.text(online ? "online" : "offline", x + 42, y + TITLEBAR + 26,
            LiveCanvas.withAlpha(INACTIVE, alpha));

        int historyH = h - (BOX_Y + 10 + INPUT_H);
        int grey64 = LiveColors.rgb(64, 64, 64);

        c.box(x + BOX_X - 1, y + BOX_Y - 1, w - 10 + 2, historyH + 2,
            LiveCanvas.withAlpha(grey64, alpha));
        c.box(x + BOX_X, y + BOX_Y, w - 10, historyH,
            LiveCanvas.withAlpha(LiveColors.rgb(36, 36, 36), alpha));

        int inputY = y + h - INPUT_H - 5;
        c.box(x + BOX_X - 1, inputY - 1, w - 10 + 2, INPUT_H + 2,
            LiveCanvas.withAlpha(grey64, alpha));
        c.box(x + BOX_X, inputY, w - 10, INPUT_H,
            LiveCanvas.withAlpha(LiveColors.rgb(24, 24, 24), alpha));

        drawHistory(c, historyH, foreground, alpha);

        String shown = input + (System.currentTimeMillis() % 1000 < 500 ? "_" : "");
        c.text(shown, x + BOX_X + 3, inputY + 3,
            LiveCanvas.withAlpha(active ? 0xFFFFFF : INACTIVE, alpha));
    }

    private void drawHistory(LiveCanvas c, int historyH, int foreground, float alpha) {
        List<LiveStore.Entry> thread = store.thread(peer);

        if (thread.isEmpty()) {
            c.text("You're chatting with " + name(), x + BOX_X + 4, y + BOX_Y + 5,
                LiveCanvas.withAlpha(LiveColors.rgb(96, 96, 96), alpha));
            return;
        }

        // Drawn newest-last from the bottom up, so the latest message is always the one in
        // view - upstream scrolls from an index, but the effect people rely on is this.
        int lines = Math.max(1, (historyH - 8) / LINE);
        int end = Math.max(0, thread.size() - scroll);
        int start = Math.max(0, end - lines);
        if (start >= thread.size()) start = Math.max(0, thread.size() - 1);

        c.clip(x + BOX_X, y + BOX_Y, w - 10, historyH);

        String lastDay = null;
        int row = 0;

        for (int i = start; i < end && row < lines; i++) {
            LiveStore.Entry entry = thread.get(i);
            Date when = new Date(entry.timestamp);

            String day = DAY.format(when);
            if (!day.equals(lastDay)) {
                lastDay = day;
                // Upstream draws these grey 64, which on its grey 36 history box is very
                // nearly invisible - as the first screenshot showed. Lifted to 150: still
                // clearly a separator rather than a message, but readable.
                c.text(day, x + BOX_X + 4, y + BOX_Y + 5 + LINE * row,
                    LiveCanvas.withAlpha(LiveColors.rgb(150, 150, 150), alpha));
                row++;
                if (row >= lines) break;
            }

            String text = CLOCK.format(when) + entry.message;
            c.text(text, x + BOX_X + 4, y + BOX_Y + 5 + LINE * row,
                LiveCanvas.withAlpha(entry.sentByMe ? 0xFFFFFF : foreground, alpha));
            row++;
        }

        c.unclip();

        if (thread.size() > lines) {
            int barH = Math.max(10, historyH * lines / thread.size());
            int span = historyH - barH;
            int offset = scroll >= thread.size() ? 0
                : span - (span * scroll / Math.max(1, thread.size() - lines));
            offset = Math.max(0, Math.min(span, offset));

            c.box(x + BOX_X + w - 10 - SCROLLBAR_W, y + BOX_Y + offset, SCROLLBAR_W, barH,
                LiveCanvas.withAlpha(LiveColors.rgb(64, 64, 64), alpha));
        }
    }
}
