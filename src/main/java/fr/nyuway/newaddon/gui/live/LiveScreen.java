package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * The screen Livemessage's windows live on, after its {@code LivemessageGui}.
 *
 * <p>Windows are a list whose last element is the active one, as upstream has it: clicking a
 * window moves it to the end, so draw order and focus order are the same fact and cannot drift
 * apart.
 *
 * <h2>Two APIs this file has to straddle</h2>
 * Drawing moved at 26.1 - {@code render} to {@code extractRenderState} - and input moved
 * earlier and separately, at 1.21.10: {@code mouseClicked(double,double,int)} became
 * {@code mouseClicked(MouseButtonEvent, boolean)}, and keys and characters went the same way.
 * The two boundaries are in different places, which is why they are two conditions and not one.
 *
 * <p>Everything past these overrides is version-blind. The windows never learn that any of it
 * happened.
 */
public class LiveScreen extends Screen {

    private final LiveMessage module;
    private final LiveStore store;
    private final List<LiveWindow> windows = new ArrayList<>();

    private int mouseX;
    private int mouseY;

    public LiveScreen(LiveMessage module, LiveStore store) {
        super(Component.literal("Messages"));
        this.module = module;
        this.store = store;
    }

    /** True while {@link #init} is putting the windows up, so that is not mistaken for focusing. */
    private boolean building;

    @Override
    protected void init() {
        building = true;
        windows.clear();
        windows.add(new LiveBuddyWindow(module, store, this::openChat, width, height));

        // Every window open this session comes back with the screen; after a restart that set is
        // just the pinned ones. Either way the user need not find each person again.
        for (UUID peer : module.openPeers()) openChat(peer, false);

        // And anyone who wrote while you were not looking. Opening the menu to see who messaged
        // you should not then mean hunting through a list for them: the people with something
        // waiting are the reason the menu was opened, so they are already up.
        //
        // Opened after the remembered ones, so an unread conversation is what has focus rather
        // than whatever happened to be open when the screen was last closed.
        List<UUID> unread = module.unreadPeers();
        for (UUID peer : unread) openChat(peer, false);

        building = false;

        // Nothing came in while you were away, so put back what you were looking at. Deliberately
        // second: somebody writing to you outranks where you happened to leave the mouse, and a
        // window that jumps in front of a message you have not read yet is worse than no memory
        // at all.
        if (unread.isEmpty()) restoreFocus();
    }

    /**
     * Brings back whatever was in front when the screen was last closed.
     *
     * <p>Silent when the window is not there any more - a conversation closed since - because
     * whatever {@link #init} put in front is then a better answer than nothing.
     */
    private void restoreFocus() {
        if (!module.haveFocusMemory()) return;

        UUID peer = module.focusMemory();
        if (peer == null) {
            // The buddy list, which is the first window and a real answer in its own right.
            if (!windows.isEmpty()) focus(windows.get(0));
            return;
        }

        for (LiveWindow window : windows) {
            if (window instanceof LiveChatWindow chat && chat.peer.equals(peer)) {
                focus(window);
                return;
            }
        }
    }

    /**
     * @param picked true when the user asked for this conversation - a name clicked in the list,
     *               or the window itself clicked - which is what counts as having read it. A
     *               window the screen put up by itself keeps its count until it is looked at,
     *               so opening the menu shows you what came in instead of quietly clearing it.
     */
    private void openChat(UUID peer, boolean picked) {
        for (LiveWindow window : windows) {
            if (window instanceof LiveChatWindow chat && chat.peer.equals(peer)) {
                focus(window);
                if (picked) module.markRead(peer);
                return;
            }
        }

        LiveChatWindow chat = new LiveChatWindow(module, store, peer, width, height);
        windows.add(chat);
        focus(chat);
        module.markOpen(peer);
        if (picked) module.markRead(peer);
    }

    /** What the buddy list hands a click to: a name picked there is one the user chose. */
    private void openChat(UUID peer) {
        openChat(peer, true);
    }

    private void focus(LiveWindow window) {
        windows.remove(window);
        windows.add(window);
        for (LiveWindow other : windows) other.active = other == window;

        // Written down as it happens rather than on the way out. A screen can go away without
        // anything being told about it - another screen opening over it, the game closing - and
        // recording the last thing that actually happened cannot miss any of those.
        if (!building) {
            module.rememberFocus(window instanceof LiveChatWindow chat ? chat.peer : null);
        }
    }

    private LiveWindow top() {
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    // --- drawing -----------------------------------------------------------

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mx, int my, float delta) {
        super.render(graphics, mx, my, delta);
        paint(new LiveCanvas(graphics), mx, my);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        super.extractRenderState(graphics, mx, my, delta);
        paint(new LiveCanvas(graphics), mx, my);
    }
    *///?}

    private void paint(LiveCanvas canvas, int mx, int my) {
        mouseX = mx;
        mouseY = my;

        for (LiveWindow window : windows) {
            window.mouseMoved(mx, my, width, height);
            window.draw(canvas);
        }

        // After every window, so a tip is never painted under the one in front of it.
        for (LiveWindow window : windows) window.drawTooltips(canvas);
    }

    // --- input, shared ------------------------------------------------------

    private boolean click(int mx, int my, int button) {
        // Front to back: the window drawn last is the one the click belongs to.
        for (int i = windows.size() - 1; i >= 0; i--) {
            LiveWindow window = windows.get(i);
            if (!window.inWindow(mx, my)) continue;

            focus(window);

            // Clicking a conversation is reading it - which is the moment the count beside the
            // name in the list should go, and not before.
            if (window instanceof LiveChatWindow chat) module.markRead(chat.peer);

            if (window.mouseClicked(mx, my, button)) windows.remove(window);
            return true;
        }
        return false;
    }

    private void release() {
        for (LiveWindow window : windows) window.mouseReleased();
    }

    private boolean scroll(double amount) {
        int lines = (int) Math.signum(amount) * 3;

        for (int i = windows.size() - 1; i >= 0; i--) {
            LiveWindow window = windows.get(i);
            if (!window.inWindow(mouseX, mouseY)) continue;

            if (window instanceof LiveChatWindow chat) {
                if (chat.inInputArea(mouseX, mouseY)) chat.scrollInput(lines);
                else chat.scroll(lines);
            } else if (window instanceof LiveBuddyWindow list) {
                list.scroll(-lines);
            }
            return true;
        }
        return false;
    }

    private boolean typed(char c) {
        LiveWindow t = top();
        if (t instanceof LiveChatWindow chat) {
            chat.type(c);
            return true;
        }
        if (t instanceof LiveBuddyWindow list) {
            list.type(c);
            return true;
        }
        return false;
    }

    /**
     * @param key GLFW code. Editing keys go to the reply box.
     *
     * <p>Control and shift are read from the keys themselves rather than the event's modifier
     * field, which on the newer input API does not carry them reliably at the moment a letter is
     * pressed - which is why Ctrl+A did nothing. The physical state is always right.
     */
    private boolean pressed(int key) {
        boolean ctrl = keyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || keyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean shift = keyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || keyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        LiveWindow t = top();
        if (t instanceof LiveChatWindow chat) {
            return chat.key(key, ctrl, shift);
        }
        if (t instanceof LiveBuddyWindow list && key == 259) {
            list.backspace();
            return true;
        }
        return false;
    }

    /**
     * Whether a key is physically held. The {@code isKeyDown} overload took a window handle
     * before 1.21.10 and the window itself after, so the call is the one split this needs.
     */
    private static boolean keyDown(int glfwKey) {
        Minecraft m = Minecraft.getInstance();
        //? if <1.21.10 {
        /*return InputConstants.isKeyDown(m.getWindow().getWindow(), glfwKey);
        *///?} else {
        return InputConstants.isKeyDown(m.getWindow(), glfwKey);
        //?}
    }

    // --- input, per era -----------------------------------------------------

    //? if <1.21.10 {
    /*@Override
    public boolean mouseClicked(double mx, double my, int button) {
        return click((int) mx, (int) my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return typed(c) || super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return pressed(key) || super.keyPressed(key, scancode, modifiers);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        return click((int) event.x(), (int) event.y(), event.button())
            || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        String s = event.codepointAsString();
        return (!s.isEmpty() && typed(s.charAt(0))) || super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return pressed(event.input()) || super.keyPressed(event);
    }
    //?}

    //? if <1.20.2 {
    /*@Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        return scroll(amount) || super.mouseScrolled(mx, my, amount);
    }
    *///?} else {
    @Override
    public boolean mouseScrolled(double mx, double my, double amountX, double amountY) {
        return scroll(amountY) || super.mouseScrolled(mx, my, amountX, amountY);
    }
    //?}

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
