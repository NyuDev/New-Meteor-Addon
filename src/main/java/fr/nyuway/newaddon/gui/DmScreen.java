package fr.nyuway.newaddon.gui;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.DmStore;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The message window: who you have talked to on the left, the conversation on the right.
 *
 * <p>Rebuilt rather than updated in place. Meteor's widgets have no notion of a list whose
 * contents changed, so a message arriving while the window is open would otherwise not show
 * until it was reopened - and a window that silently goes stale is worse than no window.
 */
public class DmScreen extends WidgetScreen {

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm");

    private final LiveMessage module;
    private final DmStore store;

    private String selected;
    private int shownCount = -1;
    /** The reply box of the open conversation, so a draft can be dropped when switching. */
    private WTextBox reply;

    public DmScreen(GuiTheme theme, LiveMessage module, DmStore store, String selected) {
        super(theme, "Messages");
        this.module = module;
        this.store = store;
        this.selected = selected;
    }

    /**
     * Called by the screen itself, and again by {@link #reload()}. Everything is rebuilt from
     * the store each time, so there is no separate path for "the list changed".
     */
    @Override
    public void initWidgets() {
        List<String> peers = store.peers();
        if (selected == null && !peers.isEmpty()) selected = peers.get(0);
        shownCount = selected == null ? -1 : store.thread(selected).size();

        WTable root = add(theme.table()).expandX().widget();

        buildPeerList(root, peers);
        buildThread(root);
    }

    @Override
    public void tick() {
        super.tick();

        // Cheap staleness check: a new message can only make the thread longer.
        int now = selected == null ? -1 : store.thread(selected).size();
        if (now != shownCount) reload();
    }

    /**
     * Throws away whatever is half-typed before the conversation changes.
     *
     * <p>Losing focus is enough to fire the send, so without this, typing a message and then
     * clicking a different name would deliver it to whoever you clicked away from - which is
     * the one mistake a messaging window must never make.
     */
    private void discardDraft() {
        if (reply != null) reply.set("");
    }

    private void buildPeerList(WTable root, List<String> peers) {
        WVerticalList side = root.add(theme.verticalList()).widget();

        side.add(theme.label("Conversations", true));

        if (peers.isEmpty()) {
            side.add(theme.label("Nothing yet."));
        } else {
            for (String peer : peers) {
                // The marker, not a colour: the theme owns colours, and a button that reads
                // differently when selected works in every one of them.
                String label = peer.equals(selected) ? "> " + peer : "  " + peer;
                WButton open = side.add(theme.button(label)).widget();
                open.action = () -> {
                    discardDraft();
                    selected = peer;
                    reload();
                };
            }
        }

        side.add(theme.horizontalSeparator());

        WTextBox newPeer = side.add(theme.textBox("", "new conversation...")).expandX().widget();
        newPeer.actionOnUnfocused = () -> {
            String name = newPeer.get().trim();
            if (name.isEmpty()) return;
            store.touch(name);
            discardDraft();
            selected = name;
            newPeer.set("");
            reload();
        };
    }

    private void buildThread(WTable root) {
        WVerticalList pane = root.add(theme.verticalList()).expandX().widget();

        if (selected == null) {
            pane.add(theme.label("Pick someone on the left, or type a name."));
            return;
        }

        pane.add(theme.label(selected, true));
        pane.add(theme.horizontalSeparator());

        List<DmStore.DmMessage> thread = store.thread(selected);
        if (thread.isEmpty()) {
            pane.add(theme.label("No messages yet."));
        } else {
            for (DmStore.DmMessage m : thread) {
                String who = m.incoming() ? selected : "you";
                pane.add(theme.label("[" + CLOCK.format(new Date(m.time())) + "] "
                    + who + ": " + m.text()));
            }
        }

        pane.add(theme.horizontalSeparator());

        reply = pane.add(theme.textBox("", "reply...")).expandX().widget();
        reply.setFocused(true);

        WTextBox box = reply;
        String to = selected;

        // Clearing the box first is what makes this safe to call twice. Meteor runs
        // actionOnUnfocused from setFocused as well as from its Enter handler, and Enter goes
        // through both - so pressing it fires this exactly twice, and the second pass has to
        // find nothing left to send.
        Runnable send = () -> {
            String text = box.get().trim();
            if (text.isEmpty()) return;
            box.set("");
            module.send(to, text);
            reload();
        };

        reply.actionOnUnfocused = send;
        pane.add(theme.button("Send")).widget().action = send;
    }
}
