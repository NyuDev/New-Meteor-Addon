package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.utils.Enemies;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.friends.Friends;

import java.util.List;
import java.util.UUID;

/**
 * {@code .enemy} - the addon's enemy list, managed from chat the way BepHax did it.
 *
 * <p>Meteor ships a friend list and a command for it but nothing for the other side, so this
 * fills in the gap: {@code .enemy add}, {@code remove}, {@code list} and {@code clear}, all
 * keyed by name so someone never seen in the tab list can still be marked. The list colours
 * names in the message window and is read back by anything else that cares who an enemy is.
 *
 * <p>Adding an enemy unfriends them: the two lists are exclusive, and the colour they are drawn
 * in comes from {@code enemy-color} in Meteor's own config tab, next to {@code friend-color}.
 */
public class EnemyCommand extends Command {

    public EnemyCommand() {
        super("enemy", "Manage the New addon's enemy list.", "enemies");
    }

    /** Brigadier's own success value, which Meteor's Command only re-exposes on newer versions. */
    private static final int OK = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    // The command source type moved from SharedSuggestionProvider to ClientSuggestionProvider at
    // 26.1, so only the signature is split - the body never names the type and compiles on both.
    @Override
    //? if >=26.1 {
    /*public void build(LiteralArgumentBuilder<net.minecraft.client.multiplayer.ClientSuggestionProvider> builder) {
    *///?} else {
    public void build(LiteralArgumentBuilder<net.minecraft.commands.SharedSuggestionProvider> builder) {
    //?}
        builder.then(literal("add")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                // Asked before the add, since the add is what takes them off the friend list.
                boolean wasFriend = Friends.get().get(name) != null;

                // With their id when they are standing right there. Not required - marking
                // somebody you have only heard of is half the point of the list - but an entry
                // that has one survives them changing their name.
                if (!Enemies.add(name, idOf(name))) info("%s is already an enemy.", name);
                else if (wasFriend) info("Added %s to enemies, and off the friend list.", name);
                else info("Added %s to enemies.", name);
                return OK;
            })));

        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                if (Enemies.remove(name)) info("Removed %s from enemies.", name);
                else info("%s is not an enemy.", name);
                return OK;
            })));

        builder.then(literal("list").executes(ctx -> {
            List<String> names = Enemies.names();
            if (names.isEmpty()) {
                info("No enemies.");
                return OK;
            }

            // One per line rather than one long line, and each says what is actually known
            // about it: whether they are here, and whether the entry has an id yet. That is
            // the difference between an entry that survives a rename and one that does not,
            // and it is the first thing to look at when a colour is missing.
            info("Enemies (%d):", names.size());
            for (String name : names) {
                boolean here = idOf(name) != null;
                info("  %s - %s, %s", name,
                    here ? "here" : "not on the server",
                    Enemies.isKnown(name) ? "id known" : "id not known yet");
            }
            return OK;
        }));

        builder.then(literal("clear").executes(ctx -> {
            Enemies.clear();
            info("Cleared the enemy list.");
            return OK;
        }));
    }

    /** Their id if they are on the server right now, or null. The tab list, nothing more. */
    private static UUID idOf(String name) {
        var mc = meteordevelopment.meteorclient.MeteorClient.mc;
        if (mc.getConnection() == null) return null;

        var info = mc.getConnection().getPlayerInfo(name);
        return info == null ? null : fr.nyuway.newaddon.utils.Profiles.idOf(info.getProfile());
    }
}
