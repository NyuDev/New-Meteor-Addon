package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    @Override
    protected void init() {
        windows.clear();
        windows.add(new LiveBuddyWindow(module, store, this::openChat, width, height));
    }

    private void openChat(UUID peer) {
        for (LiveWindow window : windows) {
            if (window instanceof LiveChatWindow chat && chat.peer.equals(peer)) {
                focus(window);
                return;
            }
        }

        LiveChatWindow chat = new LiveChatWindow(module, store, peer, width, height);
        windows.add(chat);
        focus(chat);
    }

    private void focus(LiveWindow window) {
        windows.remove(window);
        windows.add(window);
        for (LiveWindow other : windows) other.active = other == window;
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

            if (window instanceof LiveChatWindow chat) chat.scroll(lines);
            else if (window instanceof LiveBuddyWindow list) list.scroll(-lines);
            return true;
        }
        return false;
    }

    private boolean typed(char c) {
        if (top() instanceof LiveChatWindow chat) {
            chat.type(c);
            return true;
        }
        return false;
    }

    /** @param key GLFW code. Enter sends, backspace deletes; escape is left to close the screen. */
    private boolean pressed(int key) {
        if (top() instanceof LiveChatWindow chat) {
            if (key == 257 || key == 335) {
                chat.send();
                return true;
            }
            if (key == 259) {
                chat.backspace();
                return true;
            }
        }
        return false;
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
