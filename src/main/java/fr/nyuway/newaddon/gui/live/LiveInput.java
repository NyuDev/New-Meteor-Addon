package fr.nyuway.newaddon.gui.live;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The reply box: a soft-wrapping text field with a caret, selection and clipboard.
 *
 * <p>A whisper is one line on the wire, so the text holds no real newlines - the wrapping is
 * purely visual, and Enter is left to the window to send rather than to break a line. The box
 * grows with the wrapped content up to a cap the window sets, then scrolls, keeping the caret in
 * view. Measuring goes through {@code mc.font} directly rather than a canvas, so the key and
 * mouse handlers - which have no canvas - can still map a caret to a pixel and back.
 */
public final class LiveInput {

    private static final int LINE = 12;

    private final Minecraft mc = Minecraft.getInstance();
    private final StringBuilder text = new StringBuilder();

    private int caret;
    private int anchor;
    private int scroll;
    /** Column to keep while moving up and down; -1 until a vertical move sets it. */
    private float goalX = -1;

    /** Cached wrap from the last {@link #wrap}: each entry is a {start, end} index range. */
    private final List<int[]> lines = new ArrayList<>();

    // Where the box was last drawn, so click and drag can read it without a canvas.
    private int originX;
    private int originY;
    private int visible;

    public String text() {
        return text.toString();
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    public void clear() {
        text.setLength(0);
        caret = anchor = scroll = 0;
        goalX = -1;
    }

    // --- editing -----------------------------------------------------------

    public void insert(String s) {
        if (s == null || s.isEmpty()) return;

        // Whitespace that would break a line becomes a space; every other control character is
        // dropped. A whisper is one line, and this also stops a stray control-key character event
        // - the codepoint some layouts send alongside Ctrl+A - from landing in the text.
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') clean.append(' ');
            else if (ch >= ' ') clean.append(ch);
        }
        if (clean.length() == 0) return;

        deleteSelection();
        text.insert(caret, clean);
        caret += clean.length();
        anchor = caret;
        goalX = -1;
    }

    private boolean deleteSelection() {
        if (anchor == caret) return false;
        int a = Math.min(anchor, caret);
        int b = Math.max(anchor, caret);
        text.delete(a, b);
        caret = anchor = a;
        return true;
    }

    public void backspace() {
        if (!deleteSelection() && caret > 0) {
            text.deleteCharAt(caret - 1);
            caret--;
            anchor = caret;
        }
        goalX = -1;
    }

    public void delete() {
        if (!deleteSelection() && caret < text.length()) {
            text.deleteCharAt(caret);
        }
        goalX = -1;
    }

    // --- caret movement ----------------------------------------------------

    private void place(int pos, boolean keepSelection) {
        caret = Math.max(0, Math.min(text.length(), pos));
        if (!keepSelection) anchor = caret;
    }

    public void left(boolean word, boolean shift) {
        if (!shift && anchor != caret) {
            place(Math.min(anchor, caret), false);
        } else {
            place(word ? prevWord(caret) : caret - 1, shift);
        }
        goalX = -1;
    }

    public void right(boolean word, boolean shift) {
        if (!shift && anchor != caret) {
            place(Math.max(anchor, caret), false);
        } else {
            place(word ? nextWord(caret) : caret + 1, shift);
        }
        goalX = -1;
    }

    private int prevWord(int from) {
        int i = from;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int nextWord(int from) {
        int n = text.length();
        int i = from;
        while (i < n && text.charAt(i) != ' ') i++;
        while (i < n && text.charAt(i) == ' ') i++;
        return i;
    }

    public void home(boolean shift) {
        place(lines.get(lineOf(caret))[0], shift);
        goalX = -1;
    }

    public void end(boolean shift) {
        place(lines.get(lineOf(caret))[1], shift);
        goalX = -1;
    }

    public void up(boolean shift) {
        vertical(-1, shift);
    }

    public void down(boolean shift) {
        vertical(1, shift);
    }

    private void vertical(int dir, boolean shift) {
        int line = lineOf(caret);
        if (goalX < 0) goalX = caretX(line, caret);
        int target = line + dir;
        if (target < 0 || target >= lines.size()) return;
        float saved = goalX;
        place(indexAtX(target, saved), shift);
        goalX = saved;
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
        goalX = -1;
    }

    public void copy() {
        if (anchor == caret) return;
        int a = Math.min(anchor, caret);
        int b = Math.max(anchor, caret);
        mc.keyboardHandler.setClipboard(text.substring(a, b));
    }

    public void cut() {
        copy();
        if (deleteSelection()) goalX = -1;
    }

    public void paste() {
        insert(mc.keyboardHandler.getClipboard());
    }

    // --- mouse -------------------------------------------------------------

    public void click(int mx, int my) {
        place(indexAt(mx, my), false);
        goalX = -1;
    }

    public void drag(int mx, int my) {
        place(indexAt(mx, my), true);
        goalX = -1;
    }

    /** Wheel scroll within the box; the caret is not moved. */
    public void scrollBy(int lines) {
        int max = Math.max(0, this.lines.size() - visible);
        scroll = Math.max(0, Math.min(scroll - lines, max));
    }

    private int indexAt(int mx, int my) {
        if (lines.isEmpty()) return 0;
        int li = Math.max(0, Math.min(lines.size() - 1, (my - originY) / LINE + scroll));
        return indexAtX(li, mx - originX);
    }

    // --- wrap and layout ---------------------------------------------------

    /** Rewraps to {@code width} pixels and returns the visual line count. */
    public int wrap(int width) {
        lines.clear();
        int n = text.length();
        if (n == 0) {
            lines.add(new int[]{0, 0});
            return 1;
        }
        int start = 0;
        while (start < n) {
            int end = start;
            int lastSpace = -1;
            int w = 0;
            while (end < n) {
                int cw = mc.font.width(String.valueOf(text.charAt(end)));
                if (w + cw > width && end > start) break;
                if (text.charAt(end) == ' ') lastSpace = end;
                w += cw;
                end++;
            }
            if (end < n && lastSpace >= start) end = lastSpace + 1;
            if (end == start) end++;
            lines.add(new int[]{start, end});
            start = end;
        }
        return lines.size();
    }

    private int lineOf(int pos) {
        for (int i = 0; i < lines.size(); i++) {
            int[] ln = lines.get(i);
            if (pos >= ln[0] && pos < ln[1]) return i;
        }
        return lines.size() - 1;
    }

    private float caretX(int line, int pos) {
        int[] ln = lines.get(line);
        return mc.font.width(text.substring(ln[0], Math.max(ln[0], Math.min(pos, ln[1]))));
    }

    /** The index on {@code line} whose caret sits nearest {@code x} pixels from the left. */
    private int indexAtX(int line, float x) {
        int[] ln = lines.get(line);
        int best = ln[0];
        float bestD = Math.abs(x);
        float acc = 0;
        for (int i = ln[0]; i < ln[1]; i++) {
            acc += mc.font.width(String.valueOf(text.charAt(i)));
            float d = Math.abs(x - acc);
            if (d < bestD) {
                bestD = d;
                best = i + 1;
            }
        }
        return best;
    }

    // --- drawing -----------------------------------------------------------

    public void draw(LiveCanvas c, int x, int y, int maxLines, boolean focused, float alpha) {
        originX = x;
        originY = y;

        int total = lines.size();
        visible = Math.min(total, Math.max(1, maxLines));

        int line = lineOf(caret);
        if (line < scroll) scroll = line;
        if (line >= scroll + visible) scroll = line - visible + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - visible)));

        int selMin = Math.min(anchor, caret);
        int selMax = Math.max(anchor, caret);

        for (int vi = 0; vi < visible; vi++) {
            int li = vi + scroll;
            if (li >= total) break;
            int[] ln = lines.get(li);
            int ly = y + vi * LINE;

            if (selMax > selMin && selMax > ln[0] && selMin < ln[1]) {
                int a = Math.max(selMin, ln[0]);
                int b = Math.min(selMax, ln[1]);
                int ax = x + mc.font.width(text.substring(ln[0], a));
                int bx = x + mc.font.width(text.substring(ln[0], b));
                c.box(ax, ly, Math.max(1, bx - ax), LINE - 1,
                    LiveCanvas.withAlpha(LiveColors.rgb(50, 82, 150), alpha));
            }

            c.text(text.substring(ln[0], ln[1]), x, ly + 2,
                LiveCanvas.withAlpha(focused ? 0xFFFFFF : LiveColors.rgb(170, 170, 170), alpha));

            if (focused && li == line && System.currentTimeMillis() % 1000 < 500) {
                int cx = x + mc.font.width(text.substring(ln[0], caret));
                c.box(cx, ly, 1, LINE - 1, LiveCanvas.withAlpha(0xFFFFFF, alpha));
            }
        }
    }
}
