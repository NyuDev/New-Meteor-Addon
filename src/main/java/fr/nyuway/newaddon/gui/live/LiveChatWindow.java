package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;
import fr.nyuway.newaddon.modules.ServerStats;
import fr.nyuway.newaddon.modules.TempFriends;
import fr.nyuway.newaddon.utils.Allies;
import fr.nyuway.newaddon.utils.Enemies;
import fr.nyuway.newaddon.utils.NameLedger;
import fr.nyuway.newaddon.utils.vc.VcTypes;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    /**
     * The short version, beside "online": how long they have played and whether they have prio.
     *
     * <p>Asked once a frame and answered from a cache, which returns nothing the first time and
     * starts a request behind it - so nothing here waits on the network and the text simply
     * appears a moment later. Everything else is a click on the head away.
     */
    private void drawSummary(LiveCanvas c, float alpha, int after) {
        if (!ServerStats.showInMessages()) return;

        var stats = ServerStats.statsFor(peer);
        if (stats == null) return;

        String text = " - " + VcTypes.playtime(VcTypes.or0(stats.playtimeSeconds))
            + (Boolean.TRUE.equals(stats.prio) ? " - priority" : "");

        // Trimmed to the window rather than drawn over its edge: the name beside it can be any
        // length and the window can be dragged narrow.
        int room = x + w - 6 - after;
        while (text.length() > 4 && c.width(text) > room) {
            text = text.substring(0, text.length() - 2);
        }

        c.text(text, after, y + TITLEBAR + 26, LiveCanvas.withAlpha(VC_INFO, alpha));
    }

    /**
     * Everything the API knows, over the history, opened by clicking their head.
     *
     * <p>A panel rather than more header lines. The header is four lines tall and was already
     * full; a fifth sat over the box below it, which is what this looked like. And a panel can
     * hold what a line cannot - the UUID in full, both playtimes, the counts - without any of it
     * being in the way of the conversation, which is what the window is actually for.
     */
    private void drawInfoPanel(LiveCanvas c, int top, int height, float alpha) {
        int px = x + BOX_X + 6;
        int py = top + 6;
        int pw = w - 10 - 12;
        int ph = Math.min(height - 12, 112);

        c.box(px - 1, py - 1, pw + 2, ph + 2, LiveCanvas.withAlpha(LiveColors.rgb(80, 80, 80), alpha));
        c.box(px, py, pw, ph, LiveCanvas.withAlpha(LiveColors.rgb(22, 22, 26), alpha));

        int line = py + 6;
        int text = LiveCanvas.withAlpha(0xD8D8D8, alpha);
        int dim = LiveCanvas.withAlpha(INACTIVE, alpha);

        c.text(name(), px + 6, line, LiveCanvas.withAlpha(module.nameColor(peer), alpha));
        c.text("click the head to close", px + pw - 6 - c.width("click the head to close"),
            line, dim);
        line += 13;

        // The UUID lives here rather than in the header. It is the one thing about someone that
        // never changes, so it is worth being able to see - but not worth a line of the window
        // every second of every conversation.
        c.text(peer.toString(), px + 6, line, dim);
        line += 13;

        var stats = ServerStats.statsFor(peer);
        if (stats == null) {
            c.text(ServerStats.showInMessages()
                ? "asking 2b2t.vc..."
                : "2b2t.vc lookups are off, or you are not on 2b2t.", px + 6, line, dim);
            return;
        }

        c.text("Playtime  " + VcTypes.playtime(VcTypes.or0(stats.playtimeSeconds))
            + "   (" + VcTypes.playtime(VcTypes.or0(stats.playtimeSecondsMonth))
            + " this month)", px + 6, line, text);
        line += 11;

        c.text("First seen  " + VcTypes.date(stats.firstSeen), px + 6, line, text);
        line += 11;
        c.text("Last seen  " + VcTypes.date(stats.lastSeen), px + 6, line, text);
        line += 11;

        c.text("Joins " + VcTypes.or0(stats.joinCount)
            + "   Deaths " + VcTypes.or0(stats.deathCount)
            + "   Kills " + VcTypes.or0(stats.killCount)
            + "   Chats " + VcTypes.or0(stats.chatsCount), px + 6, line, text);
        line += 11;

        c.text(Boolean.TRUE.equals(stats.prio) ? "Priority queue" : "No priority", px + 6, line,
            LiveCanvas.withAlpha(Boolean.TRUE.equals(stats.prio) ? 0x8AD98A : 0x9A9A9A, alpha));
        line += 13;

        line = drawPastNames(c, px + 6, line, pw - 12, alpha);
        drawHistory(c, px + 6, line, pw - 12, py + ph - 4, alpha);
    }

    /**
     * Who they used to be.
     *
     * <p>From our own ledger rather than an API: Mojang stopped publishing name history years
     * ago, so the only record anyone has is the one they kept themselves. Nothing is shown for
     * somebody who has only ever had one name, which is most people.
     */
    private int drawPastNames(LiveCanvas c, int px, int line, int width, float alpha) {
        var past = NameLedger.previousNames(peer);
        if (past.isEmpty()) return line;

        String text = "Was  " + String.join(", ", past);
        while (text.length() > 6 && c.width(text) > width) {
            text = text.substring(0, text.length() - 2);
        }

        c.text(text, px, line, LiveCanvas.withAlpha(0xC9A227, alpha));
        return line + 13;
    }

    /**
     * What has happened to them lately, from 2b2t.vc: who they killed and who killed them.
     *
     * <p>Asked from the cache like everything else on this panel, so opening a profile never
     * waits on the network - the lines simply appear a moment later. Both lists are already
     * fetched by the {@code .2b2t} command, so a profile opened after one costs nothing at all.
     */
    private void drawHistory(LiveCanvas c, int px, int line, int width, int bottom, float alpha) {
        if (!ServerStats.showInMessages()) return;

        var events = ServerStats.recentEvents(peer, name());
        if (events.isEmpty()) return;

        c.text("Lately", px, line, LiveCanvas.withAlpha(INACTIVE, alpha));
        line += 11;

        for (String event : events) {
            if (line + 10 > bottom) return;

            String text = event;
            while (text.length() > 4 && c.width(text) > width) {
                text = text.substring(0, text.length() - 2);
            }
            c.text(text, px, line, LiveCanvas.withAlpha(0xA8A8A8, alpha));
            line += 10;
        }
    }

    /** Whether a click landed on the avatar block, which is what opens and closes the panel. */
    private boolean onHead(int mouseX, int mouseY) {
        return inRect(3, TITLEBAR + 3, 36, 36, mouseX, mouseY);
    }

    /** The 2b2t.vc text: dimmer than a name, distinct from the grey of the UUID. */
    private static final int VC_INFO = 0x7FA6C4;

    /**
     * True while the panel opened by clicking the head is showing.
     *
     * <p>Starts open when the list asked for a profile rather than a conversation. Taken rather
     * than read, so it applies to this opening and not to every one after it.
     */
    private boolean showingInfo;

    /** Meteor's friend colour, packed the way the icons want it. Asked for, never kept. */
    private static int friendColor() {
        var c = meteordevelopment.meteorclient.systems.config.Config.get().friendColor.get();
        return (c.r << 16) | (c.g << 8) | c.b;
    }

    private static final int BOX_X = 5;
    private static final int BOX_Y = TITLEBAR + 44;
    private static final int SCROLLBAR_W = 10;
    private static final int LINE = 12;

    private static final SimpleDateFormat DAY = new SimpleDateFormat("MMMM dd, yyyy");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("<HH:mm> ");

    /** Friend heart and ignore sign, drawn as pixels since upstream's icon sheet is not loaded. */
    private static final Icon HEART = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 6) / 2;
        int[] rows = {0b0110110, 0b1111111, 0b1111111, 0b0111110, 0b0011100, 0b0001000};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    private static final Icon BAN = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 7) / 2;
        int[] rows = {0b0011100, 0b0100010, 0b1000001, 0b1111111, 0b1000001, 0b0100010, 0b0011100};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    private static final Icon PIN = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 6) / 2;
        int[] rows = {0b0011100, 0b0111110, 0b0111110, 0b0011100, 0b0001000, 0b0001000};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    /** A speaker with a cross - mute, drawn since the sound and toast are what it silences. */
    private static final Icon MUTE = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 7) / 2;
        int[] rows = {0b0010000, 0b0110000, 0b1110101, 0b1110010, 0b1110101, 0b0110000, 0b0010000};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    /** An hourglass - a friend for now, which is a heart with an end to it. */
    private static final Icon HOURGLASS = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 7) / 2;
        int[] rows = {0b0111110, 0b1000001, 0b1001001, 0b1001111, 0b1000001, 0b1000001, 0b0111110};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    /** A shield - an ally, which is a friendship your group made rather than one you did. */
    private static final Icon SHIELD = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 7) / 2;
        int[] rows = {0b1111111, 0b1111111, 0b1111111, 0b0111110, 0b0111110, 0b0011100, 0b0001000};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    /** A skull - enemy, the opposite of the friend heart and drawn the same pixel way. */
    private static final Icon ENEMY = (c, bx, by, bw, bh, color) -> {
        int ox = bx + (bw - 7) / 2, oy = by + (bh - 7) / 2;
        int[] rows = {0b0111110, 0b1111111, 0b1001001, 0b1001001, 0b1111111, 0b0111110, 0b0101010};
        for (int r = 0; r < rows.length; r++)
            for (int i = 0; i < 7; i++)
                if ((rows[r] & (1 << (6 - i))) != 0) c.box(ox + i, oy + r, 1, 1, color);
    };

    private final LiveMessage module;
    private final LiveStore store;
    public final UUID peer;

    /** The reply box: wraps, scrolls, and carries a caret, selection and clipboard of its own. */
    private final LiveInput input = new LiveInput();

    /** True while a drag begun in the reply box is extending its selection. */
    private boolean selecting;

    /** Reply-box bounds from the last draw, so a click can tell whether it landed there. */
    private int inputTop;
    private int inputBoxH;

    /** Lines scrolled up from the bottom: 0 is the newest message, {@link #maxScroll} the oldest. */
    private int scroll;

    /** Thread size at the last draw, so a message arriving pulls the view back to the newest. */
    private int lastCount = -1;

    /** Wrapped display lines, rebuilt only when the width or the message count changes. */
    private final List<Line> wrapped = new ArrayList<>();
    private int wrapWidth = -1;

    /** Scrollbar geometry from the last draw, read by the drag that moves it. */
    private boolean draggingBar;
    private int trackTop;
    private int trackH;
    private int maxScroll;

    private static final int THEIRS = 0, MINE = 1, HEADER = 2;

    /** One drawn line: its text and what it is, coloured at draw time since the palette shifts. */
    private record Line(String text, int kind) { }

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
        showingInfo = module.takeProfileRequest(peer);

        // Header toggles, left to right: friend, enemy, mute, ignore - each in its own colour
        // while on and white while off, each with a tooltip that names what a click would do
        // rather than the state it is already in.
        // The heart and the skull take their colours from Meteor's config tab, so the icons agree
        // with the names under them and with the rest of the client.
        buttons.add(new LiveButton(60, TITLEBAR + 4, 12, 12, true, HEART,
            () -> module.isFriend(peer), LiveChatWindow::friendColor,
            () -> module.isFriend(peer) ? "Remove friend" : "Add friend",
            () -> module.toggleFriend(peer)));
        buttons.add(new LiveButton(45, TITLEBAR + 4, 12, 12, true, ENEMY,
            () -> module.isEnemy(peer), Enemies::color,
            () -> module.isEnemy(peer) ? "Remove enemy" : "Add enemy",
            () -> module.toggleEnemy(peer)));

        // A friend for now: the person you have just met and are about to do something with,
        // who should not still be on the list next month.
        // An ally sits beside the heart because it is the same answer to "can I shoot" with a
        // different reason behind it, and it is drawn in its own darker green so the two are
        // told apart at a glance rather than by hovering.
        buttons.add(new LiveButton(90, TITLEBAR + 4, 12, 12, true, SHIELD,
            () -> module.isAlly(peer), Allies::color,
            () -> module.isAlly(peer) ? "Not an ally any more" : "Mark as an ally",
            () -> module.toggleAlly(peer)));

        buttons.add(new LiveButton(75, TITLEBAR + 4, 12, 12, true, HOURGLASS,
            () -> TempFriends.isTemporary(module.displayName(peer)), 0xF2C94C,
            () -> TempFriends.isTemporary(module.displayName(peer))
                ? "Stop being a friend for now" : "Friend for now",
            () -> module.toggleTempFriend(peer)));
        buttons.add(new LiveButton(30, TITLEBAR + 4, 12, 12, true, MUTE,
            () -> module.isMuted(peer), 0x8AB4F8,
            () -> module.isMuted(peer) ? "Unmute" : "Mute (keeps the messages, drops the notification)",
            () -> module.toggleMute(peer)));
        buttons.add(new LiveButton(15, TITLEBAR + 4, 12, 12, true, BAN,
            () -> module.isIgnored(peer), 0xDC5050,
            () -> module.isIgnored(peer) ? "Unignore" : "Ignore",
            () -> module.toggleIgnore(peer)));

        // A pin by the close cross: a pinned conversation stays open when the screen is closed and
        // returns with it, so it need not be found in the list again.
        buttons.add(new LiveButton(27, 3, 11, 11, true, PIN,
            () -> module.isPinned(peer), 0xF2C94C,
            () -> module.isPinned(peer) ? "Unpin" : "Pin (reopens after a restart)",
            () -> module.setPinned(peer, !module.isPinned(peer))));
    }

    @Override
    public void mouseReleased() {
        draggingBar = false;
        selecting = false;
        super.mouseReleased();
        rememberPlace("chat:" + peer);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // The head is the way in and the way out. Claimed before the titlebar drag, or grabbing
        // the window by its avatar would toggle the panel on every move.
        if (onHead(mouseX, mouseY)) {
            showingInfo = !showingInfo;
            return false;
        }

        // The scrollbar is claimed before the window's own drag/resize, so a grab on it never
        // moves the window instead.
        if (inScrollbar(mouseX, mouseY)) {
            draggingBar = true;
            scrollTo(mouseY);
            return false;
        }

        // A click in the reply box places the caret and can begin a drag-selection, ahead of the
        // window's own drag - but not over the resize grip in the corner.
        if (inInputArea(mouseX, mouseY) && !(mouseX > x + w - 7 && mouseY > y + h - 7)) {
            input.click(mouseX, mouseY);
            selecting = true;
            return false;
        }

        boolean close = super.mouseClicked(mouseX, mouseY, button);
        // The cross closes it for good: gone from the session and unpinned, so it does not return.
        if (close) module.markClosed(peer);
        return close;
    }

    @Override
    public void mouseMoved(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        super.mouseMoved(mouseX, mouseY, screenWidth, screenHeight);
        if (draggingBar) scrollTo(mouseY);
        if (selecting) input.drag(mouseX, mouseY);
    }

    /**
     * Who this conversation is with.
     *
     * <p>Asked of the module, not of the store. The store only knows the name it last wrote down,
     * and it has written nothing about someone you have never messaged - which is exactly who you
     * reach by clicking a name in the server section. It answered with the first eight characters
     * of their UUID, and since this is also the name a reply is addressed to, the whisper went out
     * to a player who does not exist.
     */
    public String name() {
        return module.displayName(peer);
    }

    public void type(char c) {
        input.insert(String.valueOf(c));
    }

    /**
     * Handles an editing key, returning true when it was one so the screen consumes it rather
     * than letting it move focus or close - bar keys it does not know, like escape.
     */
    public boolean key(int keyCode, boolean ctrl, boolean shift) {
        switch (keyCode) {
            case 257, 335 -> send();
            case 259 -> input.backspace();
            case 261 -> input.delete();
            case 263 -> input.left(ctrl, shift);
            case 262 -> input.right(ctrl, shift);
            case 265 -> input.up(shift);
            case 264 -> input.down(shift);
            case 268 -> input.home(shift);
            case 269 -> input.end(shift);
            case 65 -> { if (!ctrl) return false; input.selectAll(); }
            case 67 -> { if (!ctrl) return false; input.copy(); }
            case 86 -> { if (!ctrl) return false; input.paste(); }
            case 88 -> { if (!ctrl) return false; input.cut(); }
            default -> { return false; }
        }
        return true;
    }

    /**
     * @return true when something was actually sent
     *
     * <p>Nothing goes to somebody who is not on the server. The server answers a whisper to an
     * absent player by saying so in chat, which is a public admission that you tried - and the
     * message is gone either way. What you typed is kept, so it is there when they come back
     * rather than lost to a keystroke.
     */
    public boolean send() {
        String text = input.text().trim();
        if (text.isEmpty()) return false;

        if (!module.isOnline(peer)) return false;

        input.clear();
        module.send(name(), text);
        scroll = 0;
        return true;
    }

    public void scroll(int lines) {
        scroll = Math.max(0, scroll + lines);
    }

    public void scrollInput(int lines) {
        input.scrollBy(lines);
    }

    public boolean inInputArea(int mx, int my) {
        return mx >= x + BOX_X && mx <= x + BOX_X + (w - 10)
            && my >= inputTop && my <= inputTop + inputBoxH;
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

        // Avatar block. Their head once the window has finished opening - the real skin when
        // they are on the server, the default skin for their UUID when they are not, ringed green
        // or grey by head() either way. A flat plate stands in only during the open fade, where a
        // head drawn opaque would sit oddly over a window still fading up.
        if (alpha >= 1f) {
            c.head(peer, x + 3, y + TITLEBAR + 3, 36);
        } else {
            c.box(x + 3, y + TITLEBAR + 3, 36, 36,
                LiveCanvas.withAlpha(LiveColors.rgb(50, 50, 50), alpha));
        }

        c.text(name(), x + 42, y + TITLEBAR + 5,
            LiveCanvas.withAlpha(module.nameColor(peer), alpha));
        c.text(peer.toString(), x + 42, y + TITLEBAR + 16, LiveCanvas.withAlpha(INACTIVE, alpha));
        // The 2b2t.vc summary rides on the presence line rather than under it. Its own line sat
        // at the very bottom of the header, half of it over the history box below - there was
        // never room for a fourth line there, and the full detail belongs in the panel anyway.
        c.text(online ? "online" : "offline", x + 42, y + TITLEBAR + 26,
            LiveCanvas.withAlpha(INACTIVE, alpha));
        drawSummary(c, alpha, x + 42 + c.width(online ? "online" : "offline"));

        // The reply box grows with its wrapped content up to a cap that still leaves the history
        // a few lines, then scrolls within itself. The history takes whatever is left above it.
        int grey64 = LiveColors.rgb(64, 64, 64);
        int inW = w - 10 - 6;
        int contentTop = y + BOX_Y;
        int contentBottom = y + h - 5;
        int maxInput = Math.max(1, Math.min(23, (contentBottom - contentTop) / LINE - 3));
        int inputLines = Math.max(1, Math.min(input.wrap(inW), maxInput));
        inputBoxH = inputLines * LINE + 4;
        inputTop = contentBottom - inputBoxH;
        int historyH = inputTop - 3 - contentTop;

        c.box(x + BOX_X - 1, contentTop - 1, w - 10 + 2, historyH + 2,
            LiveCanvas.withAlpha(grey64, alpha));
        c.box(x + BOX_X, contentTop, w - 10, historyH,
            LiveCanvas.withAlpha(LiveColors.rgb(36, 36, 36), alpha));

        // The reply box says whether it will work. Offline it is outlined red with a mark at the
        // right end, after Bephax - a box that looks ready to type into and silently drops what
        // you typed is worse than one that says no before you start.
        //
        // Read straight from the tab list each frame, so it turns red the moment they leave and
        // back the moment they return. That costs one set lookup, which is the whole reason the
        // question is asked here rather than being cached and going stale.
        c.box(x + BOX_X - 1, inputTop - 1, w - 10 + 2, inputBoxH + 2,
            LiveCanvas.withAlpha(online ? grey64 : OFFLINE_EDGE, alpha));
        c.box(x + BOX_X, inputTop, w - 10, inputBoxH,
            LiveCanvas.withAlpha(online ? LiveColors.rgb(24, 24, 24) : OFFLINE_FILL, alpha));

        if (showingInfo) drawInfoPanel(c, contentTop, historyH, alpha);
        else drawHistory(c, historyH, foreground, alpha);

        c.clip(x + BOX_X + 3, inputTop + 2, inW, inputBoxH - 4);
        input.draw(c, x + BOX_X + 3, inputTop + 2, inputLines, active, alpha);
        c.unclip();

        // At the right end of the box, over the text, because that is the one place a long
        // reply cannot push it out of.
        if (!online) {
            c.text("!", x + BOX_X + w - 10 - 6 - c.width("!"), inputTop + inputBoxH / 2 - 4,
                LiveCanvas.withAlpha(OFFLINE_MARK, alpha));
        }
    }

    /** The reply box while they are away: a red edge, a darker red fill, and a red mark. */
    private static final int OFFLINE_EDGE = 0x8A3A3A;
    private static final int OFFLINE_FILL = 0x2A1A1A;
    private static final int OFFLINE_MARK = 0xE05050;

    private void drawHistory(LiveCanvas c, int historyH, int foreground, float alpha) {
        List<LiveStore.Entry> thread = store.thread(peer);

        if (thread.isEmpty()) {
            c.text("You're chatting with " + name(), x + BOX_X + 4, y + BOX_Y + 5,
                LiveCanvas.withAlpha(LiveColors.rgb(96, 96, 96), alpha));
            return;
        }

        // Text stops short of a reserved scrollbar column, so wrapping does not shift when the
        // bar appears; re-wrap only on a resize or a new message, not every frame.
        int contentW = w - 26;
        if (thread.size() != lastCount || contentW != wrapWidth) {
            boolean grew = thread.size() > lastCount;
            buildWrapped(c, thread, contentW);
            wrapWidth = contentW;
            lastCount = thread.size();
            if (grew) scroll = 0;
        }

        int visible = Math.max(1, (historyH - 6) / LINE);
        maxScroll = Math.max(0, wrapped.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        trackTop = y + BOX_Y;
        trackH = historyH;

        int bottom = wrapped.size() - scroll;
        int top = Math.max(0, bottom - visible);

        c.clip(x + BOX_X, y + BOX_Y, w - 10, historyH);
        for (int i = top; i < bottom; i++) {
            Line line = wrapped.get(i);
            int color = line.kind() == HEADER ? LiveColors.rgb(150, 150, 150)
                : line.kind() == MINE ? 0xFFFFFF : foreground;
            c.text(line.text(), x + BOX_X + 4, y + BOX_Y + 4 + LINE * (i - top),
                LiveCanvas.withAlpha(color, alpha));
        }
        c.unclip();

        drawScrollbar(c, visible, alpha);
    }

    /** Rebuilds the wrapped lines: a date header when the day turns, then each message wrapped. */
    private void buildWrapped(LiveCanvas c, List<LiveStore.Entry> thread, int width) {
        wrapped.clear();
        String lastDay = null;
        for (LiveStore.Entry entry : thread) {
            Date when = new Date(entry.timestamp);
            String day = DAY.format(when);
            if (!day.equals(lastDay)) {
                lastDay = day;
                wrapped.add(new Line(day, HEADER));
            }
            int kind = entry.sentByMe ? MINE : THEIRS;
            for (String piece : wrap(c, CLOCK.format(when) + entry.message, width)) {
                wrapped.add(new Line(piece, kind));
            }
        }
    }

    /** Greedy word wrap to {@code width} pixels, hard-breaking a single word too long to fit. */
    private static List<String> wrap(LiveCanvas c, String text, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            out.add(text);
            return out;
        }

        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (c.width(candidate) <= width) {
                cur.setLength(0);
                cur.append(candidate);
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (c.width(word) <= width) {
                cur.append(word);
            } else {
                for (int i = 0; i < word.length(); i++) {
                    if (cur.length() > 0 && c.width(cur.toString() + word.charAt(i)) > width) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    cur.append(word.charAt(i));
                }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }

    private void drawScrollbar(LiveCanvas c, int visible, float alpha) {
        if (wrapped.size() <= visible) return;

        int barX = x + BOX_X + (w - 10) - SCROLLBAR_W;
        c.box(barX, trackTop, SCROLLBAR_W, trackH, LiveCanvas.withAlpha(LiveColors.rgb(28, 28, 28), alpha));

        int thumbH = Math.max(12, trackH * visible / wrapped.size());
        float p = maxScroll == 0 ? 0f : scroll / (float) maxScroll;
        int thumbY = trackTop + Math.round((trackH - thumbH) * (1 - p));

        boolean hot = draggingBar || inScrollbar(lastMouseX, lastMouseY);
        int g = hot ? 130 : 90;
        c.box(barX + 1, thumbY, SCROLLBAR_W - 2, thumbH,
            LiveCanvas.withAlpha(LiveColors.rgb(g, g, g), alpha));
    }

    private boolean inScrollbar(int mx, int my) {
        int barX = x + BOX_X + (w - 10) - SCROLLBAR_W;
        return mx >= barX && mx <= barX + SCROLLBAR_W && my >= trackTop && my <= trackTop + trackH;
    }

    /** Maps a cursor height on the track to a scroll: the top is the oldest, the bottom the newest. */
    private void scrollTo(int my) {
        if (trackH <= 0) return;
        float frac = Math.max(0f, Math.min(1f, (my - trackTop) / (float) trackH));
        scroll = Math.round(maxScroll * (1 - frac));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }
}
