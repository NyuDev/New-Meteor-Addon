package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.utils.Enemies;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.friends.Friends;

import java.util.List;

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

                if (!Enemies.add(name)) info("%s is already an enemy.", name);
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
            if (names.isEmpty()) info("No enemies.");
            else info("Enemies (%d): %s", names.size(), String.join(", ", names));
            return OK;
        }));

        builder.then(literal("clear").executes(ctx -> {
            Enemies.clear();
            info("Cleared the enemy list.");
            return OK;
        }));
    }
}
