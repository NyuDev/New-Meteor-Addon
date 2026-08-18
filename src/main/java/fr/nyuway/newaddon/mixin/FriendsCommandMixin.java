package fr.nyuway.newaddon.mixin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.commands.FriendsCommand;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@code .friends add} take somebody who is not on the server.
 *
 * <h2>What Meteor does</h2>
 * Its {@code add} takes a {@code PlayerListEntryArgumentType}, which is the tab list: the name
 * is looked up among the players currently connected, and anything else is "player list entry
 * with name X doesn't exist". Reasonable when the argument is a person you are looking at, and
 * useless for the ordinary case of adding someone you know from yesterday.
 *
 * <p>Its own Friends tab has never had the restriction - typing a name into the box makes a
 * friend of it and asks Mojang for the UUID afterwards. So this is a gap in one of two ways of
 * doing the same thing rather than a decision, and the fix is the code from the other one.
 *
 * <h2>Adding rather than replacing</h2>
 * No cancelling, no rewriting the command: a second {@code add} literal is appended, and
 * Brigadier merges same-named nodes, so it lands on Meteor's own {@code add} node next to the
 * argument already there. Meteor's is tried first, being the older child - which is what you
 * want, because for someone who <em>is</em> on the server the tab list gives the real UUID for
 * free. Only when that parse fails does the plain string below get a turn.
 *
 * <p>The name of the argument has to differ from Meteor's for that to be true. Brigadier merges
 * nodes by name, and calling this one "player" as well would fold it into the existing argument
 * instead of adding a sibling: same type as before, only now with our command on it.
 *
 * <p>The UUID is left null and resolved from Mojang in the background, exactly as the Friends
 * tab does it. Making one up would be worse than not having it - Meteor stores the id, and a
 * wrong one is a friend who is quietly a different person.
 */
@Mixin(value = FriendsCommand.class, remap = false)
public class FriendsCommandMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "build", at = @At("TAIL"))
    private void addOfflinePlayersToo(LiteralArgumentBuilder builder, CallbackInfo info) {
        builder.then(LiteralArgumentBuilder.literal("add")
            .then(RequiredArgumentBuilder.argument("name", StringArgumentType.word())
                .executes(context -> {
                    add(StringArgumentType.getString((CommandContext<?>) context, "name"));
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })));
    }

    private static void add(String name) {
        Friend friend = new Friend(name);

        if (!Friends.get().add(friend)) {
            ChatUtils.error("Already friends with that player.");
            return;
        }

        ChatUtils.info("Added (highlight)%s (default)to friends.", name);

        // Off the game thread: this is an HTTP round trip to Mojang, and the friend is already
        // on the list without it. All it settles is the UUID and the head in the Friends tab.
        MeteorExecutor.execute(friend::updateInfo);
    }
}
