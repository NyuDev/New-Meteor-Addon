package fr.nyuway.newaddon.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import fr.nyuway.newaddon.NewAddon;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real skins for people who are not on the server.
 *
 * <p>A head can be drawn straight from the tab list for anyone online, and that is what the
 * windows do. For everyone else the client has nothing - the tab list is the only place it keeps
 * skins - so the head fell back to Steve, which is not that person's head and never was.
 *
 * <h2>Where it comes from</h2>
 * Mojang, not a third party. The session server gives a profile whose textures property is a
 * base64 blob holding the URL of the skin PNG on textures.minecraft.net; that PNG is downloaded,
 * uploaded as a texture, and drawn by the same code that draws an online head.
 *
 * <h2>Fetched once, and once only</h2>
 * Every skin is written to {@code meteor-client/new-addon/skins} and read from there afterwards,
 * so a conversation list of sixty people costs sixty requests the first time and none ever
 * again. In memory, a UUID is asked about at most once per session: a hit, a miss and a failure
 * are all remembered, because the buddy list asks about every row of every frame and the one
 * account Mojang has never heard of must not become sixty requests a second.
 *
 * <p>Nothing here runs on the game thread except the upload, which has to. {@link #texture}
 * answers immediately with what it has, or with null and a fetch started behind it.
 */
public final class Skins {

    private Skins() { }

    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "New-skins");
        t.setDaemon(true);
        return t;
    });

    /** Registered textures, by UUID. Object because the identifier class was renamed at 1.21.11. */
    private static final Map<UUID, Object> READY = new HashMap<>();
    /** Asked about already, so nothing is asked twice: in flight, and known to have no skin. */
    private static final Set<UUID> ASKED = new HashSet<>();

    private static Path folder() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("skins");
    }

    /**
     * That player's skin as a texture, or null while it is being found.
     *
     * <p>Safe to call once per row per frame: everything it does is a map lookup, and the work
     * happens once, elsewhere.
     */
    public static Object texture(UUID uuid) {
        if (uuid == null) return null;

        synchronized (READY) {
            Object ready = READY.get(uuid);
            if (ready != null) return ready;
            if (ASKED.contains(uuid)) return null;
            ASKED.add(uuid);
        }

        WORKER.execute(() -> {
            try {
                byte[] png = fromDisk(uuid);
                if (png == null) {
                    png = download(uuid);
                    if (png != null) toDisk(uuid, png);
                }
                if (png != null) upload(uuid, png);
            } catch (Exception e) {
                NewAddon.LOG.warn("[skins] {}: {}", uuid, e.toString());
            }
        });

        return null;
    }

    /** A skin kept longer than this is fetched again, so a changed one is not cached for ever. */
    private static final long STALE = 7L * 24 * 60 * 60 * 1000;

    private static byte[] fromDisk(UUID uuid) {
        Path file = folder().resolve(uuid + ".png");
        try {
            if (!Files.isRegularFile(file)) return null;
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            return age > STALE ? null : Files.readAllBytes(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static void toDisk(UUID uuid, byte[] png) {
        try {
            Files.createDirectories(folder());
            Files.write(folder().resolve(uuid + ".png"), png);
        } catch (Exception e) {
            // A skin that cannot be saved still draws this session; it is a cache, not the point.
            NewAddon.LOG.warn("[skins] could not save {}: {}", uuid, e.toString());
        }
    }

    /** Session server to textures property to PNG. Null when the account simply has no skin. */
    private static byte[] download(UUID uuid) throws Exception {
        String id = uuid.toString().replace("-", "");
        String body = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
        if (body == null || body.isBlank()) return null;

        JsonObject profile = GSON.fromJson(body, JsonObject.class);
        if (profile == null || !profile.has("properties")) return null;

        String encoded = null;
        for (var element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equals(property.get("name").getAsString())) {
                encoded = property.get("value").getAsString();
                break;
            }
        }
        if (encoded == null) return null;

        JsonObject textures = GSON.fromJson(
            new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8),
            JsonObject.class);
        if (textures == null || !textures.has("textures")) return null;

        JsonObject all = textures.getAsJsonObject("textures");
        if (!all.has("SKIN")) return null;

        String url = all.getAsJsonObject("SKIN").get("url").getAsString();
        return getBytes(url);
    }

    /** Puts the image on the GPU and remembers it. On the game thread, because textures are its. */
    private static void upload(UUID uuid, byte[] png) {
        Minecraft.getInstance().execute(() -> {
            try {
                NativeImage image = NativeImage.read(png);

                //? if <1.21.5 {
                /*DynamicTexture texture = new DynamicTexture(image);
                *///?} else {
                DynamicTexture texture = new DynamicTexture(() -> "new-addon/skin", image);
                //?}

                var id = identifier("new-addon", "skin/" + uuid);
                Minecraft.getInstance().getTextureManager().register(id, texture);

                synchronized (READY) {
                    READY.put(uuid, id);
                }
            } catch (Exception e) {
                NewAddon.LOG.warn("[skins] could not upload {}: {}", uuid, e.toString());
            }
        });
    }

    /**
     * A texture identifier.
     *
     * <p>Three eras: the constructor was public until 1.21.1 made a factory of it, and the class
     * itself was renamed from {@code ResourceLocation} to {@code Identifier} at 1.21.11.
     */
    //? if <1.21.1 {
    /*private static net.minecraft.resources.ResourceLocation identifier(String namespace, String path) {
        return new net.minecraft.resources.ResourceLocation(namespace, path);
    }
    *///?} else if <1.21.11 {
    /*private static net.minecraft.resources.ResourceLocation identifier(String namespace, String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
    *///?} else {
    private static net.minecraft.resources.Identifier identifier(String namespace, String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, path);
    }
    //?}

    private static String get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(request(url),
            HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(request(url),
            HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "New-Meteor-Addon/" + NewAddon.version()
                + " (+https://github.com/NyuDev/New-Meteor-Addon)")
            .GET()
            .build();
    }
}
