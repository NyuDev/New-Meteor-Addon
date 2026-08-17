package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.modules.TempFriends;
import fr.nyuway.newaddon.utils.Profiles;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;

/**
 * {@code .tempfriend} - a friend for now, from chat.
 *
 * <p>The window has a button for the people you are already talking to; this is for everyone
 * else, which on an anarchy server is most of the people you end up standing next to.
 */
public class TempFriendCommand extends Command {

    public TempFriendCommand() {
        super("tempfriend", "Add a friend for this session only.", "tf");
    }

    private static final int OK = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    @Override
    //? if >=26.1 {
    /*public void build(LiteralArgumentBuilder<net.minecraft.client.multiplayer.ClientSuggestionProvider> builder) {
    *///?} else {
    public void build(LiteralArgumentBuilder<net.minecraft.commands.SharedSuggestionProvider> builder) {
    //?}
        builder.then(literal("add")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                // Nothing said on failure: TempFriends prints why it refused, and a second
                // sentence guessing at a different reason is how a clear message becomes noise.
                String name = StringArgumentType.getString(ctx, "name");
                TempFriends.add(name, uuidOf(name));
                return OK;
            })));

        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                if (TempFriends.remove(name)) info("%s is not a friend any more.", name);
                else info("%s was not a temporary friend.", name);
                return OK;
            })));

        builder.then(literal("list").executes(ctx -> {
            List<String> names = TempFriends.names();
            if (names.isEmpty()) info("No temporary friends.");
            else info("Friends for now (%d): %s", names.size(), String.join(", ", names));
            return OK;
        }));
    }

    /** Their UUID if they are on the server, so the friend entry carries one. Null otherwise. */
    private static UUID uuidOf(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;

        var info = mc.getConnection().getPlayerInfo(name);
        return info == null ? null : Profiles.idOf(info.getProfile());
    }
}
