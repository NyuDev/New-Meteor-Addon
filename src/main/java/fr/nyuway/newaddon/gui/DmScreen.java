package fr.nyuway.newaddon.gui;

import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.util.UUID;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The message window: who you have talked to on the left, the conversation on the right.
 *
 * <h2>Why WindowScreen</h2>
 * {@link meteordevelopment.meteorclient.gui.WidgetScreen} is the bare canvas - widgets land in
 * the top-left corner over the game with no frame, no background and nothing to drag, which is
 * not a window so much as text sprayed across the screen. {@code WindowScreen} is what every
 * one of Meteor's own screens extends, and it supplies the frame.
 *
 * <h2>Rebuilt, not updated</h2>
 * Meteor's widgets have no notion of a list whose contents changed, so a message arriving while
 * the window is open would otherwise not appear until it was reopened. The tick compares the
 * thread length and calls {@code reload()}, which runs {@link #initWidgets()} again.
 */
public class DmScreen extends WindowScreen {

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm");

    /** Width the window asks for, so the two panes are not squeezed into a column. */
    private static final double WIDTH = 620;

    /** Width a message wraps at, leaving the conversation list its share. */
    private static final double BUBBLE_WIDTH = 380;

    /** Messages shown at once. The file keeps everything; a window does not need to. */
    private static final int SHOWN = 60;

    private final LiveMessage module;
    private final LiveStore store;

    private UUID selected;
    private int shownCount = -1;
    /** The reply box of the open conversation, so a draft can be dropped when switching. */
    private WTextBox reply;

    public DmScreen(GuiTheme theme, LiveMessage module, LiveStore store, UUID selected) {
        super(theme, "Messages");
        this.module = module;
        this.store = store;
        this.selected = selected;
    }

    @Override
    public void initWidgets() {
        List<UUID> peers = store.peers();
        if (selected == null && !peers.isEmpty()) selected = peers.get(0);
        shownCount = selected == null ? -1 : store.thread(selected).size();

        WHorizontalList body = add(theme.horizontalList()).expandX().minWidth(WIDTH).widget();

        buildPeerList(body.add(theme.verticalList()).top().widget(), peers);
        body.add(theme.verticalSeparator());
        buildThread(body.add(theme.verticalList()).top().expandX().widget());

        add(theme.horizontalSeparator()).expandX();
        buildReplyBar();
    }

    @Override
    public void tick() {
        super.tick();

        // Cheap staleness check: a new message can only make the thread longer.
        int now = selected == null ? -1 : store.thread(selected).size();
        if (now != shownCount) reload();
    }

    private void buildPeerList(WVerticalList side, List<UUID> peers) {
        side.add(theme.label("Conversations", true));
        side.add(theme.horizontalSeparator());

        if (peers.isEmpty()) {
            side.add(theme.label("Nothing yet."));
        } else {
            for (UUID peer : peers) {
                String name = store.nameOf(peer);
                // A marker rather than a colour: the theme owns colours, and a button that
                // reads differently when selected works in every one of them.
                WButton open = side.add(theme.button(peer.equals(selected) ? "> " + name : name))
                    .expandX().widget();
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

            UUID found = store.findByName(name);
            if (found == null) found = module.resolveName(name);
            if (found == null) {
                // Without a UUID there is nowhere to file it, and inventing one would put the
                // thread somewhere Livemessage itself would never look.
                newPeer.set("");
                return;
            }

            store.settingsOf(found).lastName = name;
            discardDraft();
            selected = found;
            newPeer.set("");
            reload();
        };
    }

    private void buildThread(WVerticalList pane) {
        if (selected == null) {
            pane.add(theme.label("Pick someone on the left, or type a name below it."));
            return;
        }

        pane.add(theme.label(store.nameOf(selected), true));
        pane.add(theme.horizontalSeparator());

        List<LiveStore.Entry> thread = store.thread(selected);
        if (thread.isEmpty()) {
            pane.add(theme.label("No messages yet."));
            return;
        }

        List<LiveStore.Entry> shown =
            thread.subList(Math.max(0, thread.size() - SHOWN), thread.size());

        for (LiveStore.Entry m : shown) {
            String who = m.sentByMe ? "you" : store.nameOf(selected);
            pane.add(theme.label("[" + CLOCK.format(new Date(m.timestamp)) + "] " + who + ": "
                + m.message, BUBBLE_WIDTH));
        }
    }

    private void buildReplyBar() {
        WHorizontalList bar = add(theme.horizontalList()).expandX().widget();

        if (selected == null) {
            bar.add(theme.label("No conversation selected."));
            reply = null;
            return;
        }

        String name = store.nameOf(selected);
        reply = bar.add(theme.textBox("", "reply to " + name + "...")).expandX().widget();
        reply.setFocused(true);

        WTextBox box = reply;
        String to = name;

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
        bar.add(theme.button("Send")).widget().action = send;
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
}
