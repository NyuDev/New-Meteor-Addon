package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.vc.VcApi;
import fr.nyuway.newaddon.utils.vc.VcTypes;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * 2b2t.vc - the server's own data, in game.
 *
 * <p>api.2b2t.vc keeps what 2b2t itself does not: how long someone has played, when they were
 * first and last seen, what the queue is doing, who has priority. The {@code .2b2t} command asks
 * it a question at a time; this module is what asks on its own, and what decides how often
 * anything is asked at all.
 *
 * <h2>Being a good guest</h2>
 * The API is run by one person, for free, and publishes no rate limit. That is a reason to be
 * careful rather than a licence not to be, so requests go out one at a time with a floor on the
 * gap between them, and every answer is kept for as long as it can still be true. The defaults
 * here are deliberately slow; the command is instant anyway, because it is nearly always
 * answering from something already fetched.
 *
 * <p>Switching the module off stops the automatic lookups. The command keeps working - you asked
 * for that one.
 */
public class ServerStats extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLive = settings.createGroup("In LiveMessage");

    private final Setting<Integer> requestInterval = sgGeneral.add(new IntSetting.Builder()
        .name("request-interval")
        .description("Milliseconds between two requests to the API. Someone runs it for free " +
                     "and publishes no limit, so this is set slow on purpose.")
        .defaultValue(1000).min(250).max(10000).sliderRange(250, 5000)
        .build());

    private final Setting<Integer> playerCacheMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("player-cache")
        .description("Minutes an answer about a player is kept before asking again. Playtime " +
                     "and first-seen barely move, so this can be generous.")
        .defaultValue(10).min(1).max(120).sliderRange(1, 60)
        .build());

    private final Setting<Integer> queueCacheSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("queue-cache")
        .description("Seconds the queue length is kept. It is the one thing here that really " +
                     "does change minute to minute.")
        .defaultValue(20).min(5).max(300).sliderRange(5, 120)
        .build());

    private final Setting<Boolean> onlyOn2b2t = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-2b2t")
        .description("Only look anything up while actually connected to 2b2t. The data is about " +
                     "that server, so asking it about someone met elsewhere tells you nothing " +
                     "and still costs a request.")
        .defaultValue(true)
        .build());

    private final Setting<String> serverHost = sgGeneral.add(new StringSetting.Builder()
        .name("server-host")
        .description("What counts as being on 2b2t. Matched on the end of the address, so a " +
                     "proxy at anything.2b2t.org counts too.")
        .defaultValue("2b2t.org")
        .visible(onlyOn2b2t::get)
        .build());

    private final Setting<Boolean> inLiveMessage = sgLive.add(new BoolSetting.Builder()
        .name("show-in-messages")
        .description("Put what the API knows about someone under their name in the message " +
                     "window: playtime, when they were first seen, whether they have priority.")
        .defaultValue(true)
        .build());

    public ServerStats() {
        super(NewAddon.CATEGORY, "2b2t-vc",
            "Data from api.2b2t.vc: playtime, first and last seen, the queue, priority.");
    }

    @Override
    public void onActivate() {
        apply();
    }

    /** Pushes the settings into the client. Cheap, so it is done on every read rather than watched. */
    private void apply() {
        VcApi.configure(requestInterval.get(), NewAddon.version());
    }

    // --- what the rest of the addon asks -------------------------------------

    private static ServerStats get() {
        return Modules.get() == null ? null : Modules.get().get(ServerStats.class);
    }

    /** Whether automatic lookups are allowed at all right now. */
    public static boolean lookupsAllowed() {
        ServerStats module = get();
        if (module == null || !module.isActive()) return false;
        module.apply();
        return !module.onlyOn2b2t.get() || onServer(module.serverHost.get());
    }

    /** Whether the message window should show what the API knows. */
    public static boolean showInMessages() {
        ServerStats module = get();
        return module != null && module.isActive() && module.inLiveMessage.get()
            && lookupsAllowed();
    }

    /** Milliseconds an answer about a player stays good for. */
    public static long playerTtl() {
        ServerStats module = get();
        return (module == null ? 10 : module.playerCacheMinutes.get()) * 60_000L;
    }

    /** Milliseconds the queue length stays good for. */
    public static long queueTtl() {
        ServerStats module = get();
        return (module == null ? 20 : module.queueCacheSeconds.get()) * 1000L;
    }

    /**
     * Whether we are on the server this data is about.
     *
     * <p>Matched on the end of the address so a proxy in front of it still counts, and on the
     * address the client was given rather than anything the server says about itself.
     */
    public static boolean onServer(String host) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;

        var data = mc.getCurrentServer();
        if (data == null || data.ip == null) return false;

        String ip = data.ip.toLowerCase();
        int colon = ip.indexOf(':');
        if (colon != -1) ip = ip.substring(0, colon);

        String want = host == null ? "" : host.trim().toLowerCase();
        return !want.isEmpty() && (ip.equals(want) || ip.endsWith("." + want));
    }

    /**
     * What the API knows about someone, or null while it is being asked.
     *
     * <p>Never blocks: the first call starts the request and returns nothing, and a later one
     * has the answer. Safe to call once a frame, which is exactly how the message window uses it.
     */
    public static VcTypes.PlayerStats statsFor(String name) {
        if (name == null || name.isBlank() || !lookupsAllowed()) return null;
        return VcApi.cached("/stats/player", VcApi.params("playerName", name),
            VcTypes.PlayerStats.class, playerTtl());
    }

    /** The same, by UUID, which does not go stale when someone renames. */
    public static VcTypes.PlayerStats statsFor(UUID uuid) {
        if (uuid == null || !lookupsAllowed()) return null;
        return VcApi.cached("/stats/player", VcApi.params("uuid", uuid.toString()),
            VcTypes.PlayerStats.class, playerTtl());
    }
}
