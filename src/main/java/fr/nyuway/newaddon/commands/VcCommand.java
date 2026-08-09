package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.modules.ServerStats;
import fr.nyuway.newaddon.utils.vc.Markers;
import fr.nyuway.newaddon.utils.vc.VcApi;
import fr.nyuway.newaddon.utils.vc.VcTypes;
import meteordevelopment.meteorclient.commands.Command;

import java.util.List;
import java.util.Map;

/**
 * {@code .2b2t} - what api.2b2t.vc knows, asked a question at a time and printed in chat.
 *
 * <p>Everything here is a read. Answers are cached by {@link VcApi}, so asking the same thing
 * twice in a minute costs one request; asking about ten people costs ten, spaced out.
 *
 * <p>Requests are answered on a background thread, so the reply lands a moment after the command
 * rather than freezing the game while a server on the other side of the internet thinks about
 * it. Meteor's chat is safe to write to from there.
 */
public class VcCommand extends Command {

    public VcCommand() {
        super("2b2t", "Look things up on api.2b2t.vc.", "vc");
    }

    private static final int OK = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    /** Rows of a top-ten list. More than this in chat is a wall, not an answer. */
    private static final int TOP = 10;
    /** Recent entries shown for deaths, kills, chats and connections. */
    private static final int RECENT = 5;

    private static final long STATIC_TTL = 60 * 60_000L;

    @Override
    //? if >=26.1 {
    /*public void build(LiteralArgumentBuilder<net.minecraft.client.multiplayer.ClientSuggestionProvider> builder) {
    *///?} else {
    public void build(LiteralArgumentBuilder<net.minecraft.commands.SharedSuggestionProvider> builder) {
    //?}
        builder.then(literal("queue").executes(ctx -> {
            ask("/queue", null, VcTypes.QueueData.class, ServerStats.queueTtl(), q -> {
                info("Queue: %d regular, %d priority.",
                    VcTypes.or0(q.regular), VcTypes.or0(q.prio));
                eta(VcTypes.or0(q.regular), VcTypes.or0(q.prio));
            });
            return OK;
        }));

        builder.then(literal("stats")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/stats/player", VcApi.params("playerName", name),
                    VcTypes.PlayerStats.class, ServerStats.playerTtl(), s -> {
                        info("%s%s", name, Boolean.TRUE.equals(s.prio) ? " (priority)" : "");
                        info("  Playtime %s, %s this month.",
                            VcTypes.playtime(VcTypes.or0(s.playtimeSeconds)),
                            VcTypes.playtime(VcTypes.or0(s.playtimeSecondsMonth)));
                        info("  First seen %s.", VcTypes.date(s.firstSeen));
                        info("  Last seen %s.", VcTypes.date(s.lastSeen));
                        info("  %d joins, %d deaths, %d kills, %d chats.",
                            VcTypes.or0(s.joinCount), VcTypes.or0(s.deathCount),
                            VcTypes.or0(s.killCount), VcTypes.or0(s.chatsCount));
                    });
                return OK;
            })));

        builder.then(literal("seen")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/seen", VcApi.params("playerName", name),
                    VcTypes.SeenResponse.class, ServerStats.playerTtl(), s -> {
                        info("%s: first seen %s.", name, VcTypes.date(s.firstSeen));
                        info("  Last seen %s.", VcTypes.date(s.lastSeen));
                    });
                return OK;
            })));

        builder.then(literal("playtime")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/playtime", VcApi.params("playerName", name),
                    VcTypes.PlaytimeResponse.class, ServerStats.playerTtl(), p ->
                        info("%s has played %s.", name,
                            VcTypes.playtime(VcTypes.or0(p.playtimeSeconds))));
                return OK;
            })));

        builder.then(literal("deaths")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/deaths", VcApi.params("playerName", name, "pageSize", String.valueOf(RECENT)),
                    VcTypes.DeathsResponse.class, ServerStats.playerTtl(), d -> {
                        info("%s: %d deaths.", name, VcTypes.or0(d.total));
                        if (d.deaths != null) {
                            for (VcTypes.Death death : d.deaths) {
                                info("  %s", death.deathMessage);
                            }
                        }
                    });
                return OK;
            })));

        builder.then(literal("kills")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/kills", VcApi.params("playerName", name, "pageSize", String.valueOf(RECENT)),
                    VcTypes.KillsResponse.class, ServerStats.playerTtl(), k -> {
                        info("%s: %d kills.", name, VcTypes.or0(k.total));
                        if (k.kills != null) {
                            for (VcTypes.Death kill : k.kills) {
                                info("  %s", kill.deathMessage);
                            }
                        }
                    });
                return OK;
            })));

        builder.then(literal("chats")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/chats", VcApi.params("playerName", name, "pageSize", String.valueOf(RECENT),
                        "sort", "desc"),
                    VcTypes.ChatSearchResponse.class, ServerStats.playerTtl(), c -> {
                        info("%s: %d messages on record.", name, VcTypes.or0(c.total));
                        if (c.chats != null) {
                            for (VcTypes.PlayerChat chat : c.chats) info("  %s", chat.chat);
                        }
                    });
                return OK;
            })));

        builder.then(literal("connections")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/connections", VcApi.params("playerName", name,
                        "pageSize", String.valueOf(RECENT)),
                    VcTypes.ConnectionsResponse.class, ServerStats.playerTtl(), c -> {
                        info("%s: %d connections on record.", name, VcTypes.or0(c.total));
                        if (c.connections != null) {
                            for (VcTypes.Connection conn : c.connections) {
                                info("  %s %s", conn.connection, VcTypes.date(conn.time));
                            }
                        }
                    });
                return OK;
            })));

        builder.then(literal("online").executes(ctx -> {
            ask("/tablist/info", null, VcTypes.TablistInfoResponse.class, 30_000L, t ->
                info("Online: %d - %d priority, %d not, %d suspected bots.",
                    VcTypes.or0(t.count), VcTypes.or0(t.prioCount),
                    VcTypes.or0(t.nonPrioCount), VcTypes.or0(t.botCount)));
            return OK;
        }));

        builder.then(literal("time").executes(ctx -> {
            ask("/time", null, VcTypes.TimeResponse.class, 60_000L, t ->
                info("World time %s (%d ticks), as of %s.",
                    VcTypes.worldTime(VcTypes.or0(t.worldTime)),
                    VcTypes.or0(t.worldTime), VcTypes.date(t.lastUpdated)));
            return OK;
        }));

        builder.then(literal("limit").executes(ctx -> {
            ask("/limits/session-time-limit", null, VcTypes.SessionTimeLimitResponse.class,
                STATIC_TTL, l -> info("Non-priority session limit: %d hours.",
                    VcTypes.or0(l.hours)));
            return OK;
        }));

        builder.then(literal("word")
            .then(argument("word", StringArgumentType.word()).executes(ctx -> {
                String word = StringArgumentType.getString(ctx, "word");
                ask("/chats/word-count", VcApi.params("word", word),
                    VcTypes.WordCount.class, STATIC_TTL, w ->
                        info("\"%s\" has been said %d times.", word, VcTypes.or0(w.count)));
                return OK;
            })));

        builder.then(literal("top").then(literal("playtime").executes(ctx -> {
            ask("/playtime/top", null, VcTypes.PlaytimeAllTimeResponse.class, STATIC_TTL, p -> {
                info("Top playtime, all time:");
                List<VcTypes.PlayerPlaytimeSecondsData> players = p.players;
                if (players == null) return;
                for (int i = 0; i < Math.min(TOP, players.size()); i++) {
                    var row = players.get(i);
                    info("  %d. %s - %s", i + 1, row.playerName,
                        VcTypes.playtime(row.playtimeSeconds == null ? 0 : row.playtimeSeconds));
                }
            });
            return OK;
        })));

        builder.then(literal("top").then(literal("month").executes(ctx -> {
            ask("/playtime/top/month", null, VcTypes.PlaytimeMonthResponse.class, STATIC_TTL, p -> {
                info("Top playtime this month:");
                List<VcTypes.PlayerPlaytimeDaysData> players = p.players;
                if (players == null) return;
                for (int i = 0; i < Math.min(TOP, players.size()); i++) {
                    var row = players.get(i);
                    info("  %d. %s - %.1f days", i + 1, row.playerName,
                        row.playtimeDays == null ? 0.0 : row.playtimeDays);
                }
            });
            return OK;
        })));

        builder.then(literal("top").then(literal("deaths").executes(ctx -> {
            topCount("/deaths/top/month", "Most deaths this month:");
            return OK;
        })));

        builder.then(literal("top").then(literal("kills").executes(ctx -> {
            topCount("/kills/top/month", "Most kills this month:");
            return OK;
        })));

        builder.then(literal("prio")
            .then(argument("player", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "player");
                ask("/stats/player", VcApi.params("playerName", name),
                    VcTypes.PlayerStats.class, ServerStats.playerTtl(), s ->
                        info("%s %s priority.", name,
                            Boolean.TRUE.equals(s.prio) ? "has" : "does not have"));
                return OK;
            })));

        // Markers, with what each is worth right now. Values come from the cache, so anything
        // not fetched yet shows as ? and is fetched behind this - ask again and it will be there.
        builder.then(literal("markers").executes(ctx -> {
            info("Markers, replaced when a message or a sign is sent:");
            for (String name : Markers.names()) {
                info("  {%s} = %s", name, Markers.value(name));
            }
            info("A backslash sends one as written: \\{queue}");
            return OK;
        }));

        builder.then(literal("refresh").executes(ctx -> {
            VcApi.clearCache();
            info("Forgot everything cached; the next question goes to the API.");
            return OK;
        }));
    }

    private void topCount(String path, String heading) {
        ask(path, null, VcTypes.PlayerDeathOrKillCountResponse.class, STATIC_TTL, r -> {
            info(heading);
            if (r.players == null) return;
            for (int i = 0; i < Math.min(TOP, r.players.size()); i++) {
                var row = r.players.get(i);
                info("  %d. %s - %d", i + 1, row.playerName, VcTypes.or0(row.count));
            }
        });
    }

    /** ETA from the queue equation, which the API publishes rather than hard-codes. */
    private void eta(int regular, int prio) {
        if (regular <= 0 && prio <= 0) return;

        VcApi.request("/queue/eta-equation", null, VcTypes.QueueEtaEquation.class, STATIC_TTL,
            e -> {
                if (e.factor == null || e.pow == null) return;
                if (regular > 0) {
                    info("  Joining the back of the regular queue: about %s.",
                        VcTypes.playtime((long) (e.factor * Math.pow(regular, e.pow))));
                }
                if (prio > 0) {
                    info("  Priority: about %s.",
                        VcTypes.playtime((long) (e.factor * Math.pow(prio, e.pow))));
                }
            },
            why -> { });
    }

    /**
     * One question, answered whenever the answer arrives.
     *
     * <p>Both callbacks are handed back to the game thread before they print. They arrive on the
     * HTTP worker, and the chat feed is a list the render thread walks every frame - writing to
     * it from anywhere else is the sort of bug that shows up once a fortnight as a crash nobody
     * can reproduce.
     *
     * <p>The failure is always reported. A lookup that quietly does nothing looks exactly like a
     * command that did not run, and from the outside there is no telling which.
     */
    private <T> void ask(String path, Map<String, String> query, Class<T> type, long ttl,
                         java.util.function.Consumer<T> print) {
        VcApi.request(path, query, type, ttl,
            value -> onGameThread(() -> print.accept(value)),
            why -> onGameThread(() -> warning("2b2t.vc: %s", why)));
    }

    private static void onGameThread(Runnable action) {
        net.minecraft.client.Minecraft.getInstance().execute(action);
    }
}
