package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.utils.Allies;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.friends.Friends;

import java.util.List;
import java.util.UUID;

/**
 * {@code .ally} - the people your group is at peace with.
 *
 * <p>An ally is a friend with a reason attached, so {@code add} friends them too and
 * {@code remove} leaves the friendship: taking the tag off makes them a plain friend, and
 * {@code .friends remove} is how somebody stops being protected. Marking an ally takes them off
 * the enemy list, since those are opposite answers to the same question.
 *
 * <p>Keyed by name, with the UUID learned the first time they are seen, so somebody you have
 * only heard of can be marked and an ally who renames stays one.
 */
public class AllyCommand extends Command {

    public AllyCommand() {
        super("ally", "Manage the New addon's ally list.", "allies");
    }

    /** Brigadier's own success value, which Meteor's Command only re-exposes on newer versions. */
    private static final int OK = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    @Override
    //? if >=26.1 {
    /*public void build(LiteralArgumentBuilder<net.minecraft.client.multiplayer.ClientSuggestionProvider> builder) {
    *///?} else {
    public void build(LiteralArgumentBuilder<net.minecraft.commands.SharedSuggestionProvider> builder) {
    //?}
        builder.then(literal("add")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                // Asked first, since the add is what puts them on the friend list.
                boolean wasFriend = Friends.get().get(name) != null;

                if (!Allies.add(name, idOf(name))) info("%s is already an ally.", name);
                else if (wasFriend) info("Marked %s an ally.", name);
                else info("Marked %s an ally, and added them as a friend.", name);
                return OK;
            })));

        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                if (Allies.remove(name)) {
                    info("%s is a plain friend again; use .friends remove to unfriend them.", name);
                } else info("%s is not an ally.", name);
                return OK;
            })));

        builder.then(literal("list").executes(ctx -> {
            List<String> names = Allies.names();
            if (names.isEmpty()) {
                info("No allies.");
                return OK;
            }

            info("Allies (%d):", names.size());
            for (String name : names) {
                // Whether they are still a friend is worth saying: the tag means nothing on its
                // own, and an ally who has been unfriended by hand is about to lose it anyway.
                info("  %s - %s, %s, %s", name,
                    idOf(name) != null ? "here" : "not on the server",
                    Allies.isKnown(name) ? "id known" : "id not known yet",
                    Friends.get().get(name) != null ? "a friend" : "NOT a friend");
            }
            return OK;
        }));

        builder.then(literal("clear").executes(ctx -> {
            Allies.clear();
            info("Cleared the ally list. Nobody was unfriended.");
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
