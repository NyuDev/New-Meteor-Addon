package fr.nyuway.newaddon.compat;

import fr.nyuway.newaddon.NewAddon;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Client for StasisBot's encrypted control channel.
 *
 * <p>Speaks the same wire format as the bot's {@code ControlProtocol}: a UTF-8 frame
 * {@code v1|<epochMillis>|<type>|<payload>} sealed with <b>AES-256-GCM</b> (key = SHA-256
 * of the shared secret, fresh 12-byte nonce, 128-bit tag), base64url encoded, POSTed as the
 * body of {@code /ctl}. The bot rejects frames outside a 45 second window, so nothing here
 * can be captured and replayed later.
 *
 * <p>The secret never travels: it is only ever used as key material. A wrong secret produces
 * a 403 with an empty body, which tells an attacker nothing.
 *
 * <p>All network work happens on a daemon thread. Callbacks fire there too, so callers must
 * hop back to the game thread themselves before touching anything Minecraft.
 */
public final class StasisControl {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_LEN = 12;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "new-stasis-control");
        t.setDaemon(true);
        return t;
    });

    private static final SecureRandom RNG = new SecureRandom();

    private static volatile HttpClient client;

    private StasisControl() {
    }

    /**
     * Asks the bot to pull {@code player} home.
     *
     * @param endpoint  {@code host:port}, with or without a scheme
     * @param secret    shared secret, identical to the bot's
     * @param player    name the bot should pull
     * @param feedback  called on the IO thread with a short human-readable result
     */
    public static void homeRequest(String endpoint, String secret, String player,
                                   Consumer<String> feedback) {
        if (secret == null || secret.isBlank()) {
            feedback.accept("No control secret set.");
            return;
        }
        if (endpoint == null || endpoint.isBlank()) {
            feedback.accept("No control endpoint set.");
            return;
        }

        final byte[] key = sha256(secret.trim());
        final String url = normalise(endpoint) + "/ctl";
        final String body = seal(key, "HOMEREQ", player);

        IO.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 403) {
                    feedback.accept("Bot rejected the request - wrong secret?");
                    return;
                }
                if (resp.statusCode() != 200) {
                    feedback.accept("Bot replied HTTP " + resp.statusCode() + ".");
                    return;
                }

                String[] frame = open(key, resp.body());
                if (frame == null) {
                    feedback.accept("Could not decrypt the reply - secret mismatch?");
                    return;
                }
                if ("OK".equals(frame[0])) {
                    feedback.accept("Bot accepted the pull.");
                } else {
                    feedback.accept("Bot answered " + frame[0] + ": " + frame[1]);
                }
            } catch (Exception e) {
                NewAddon.LOG.warn("[stasis] HOMEREQ failed: {}", e.toString());
                feedback.accept("Bot unreachable (" + e.getClass().getSimpleName() + ").");
            }
        });
    }

    private static HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (StasisControl.class) {
                c = client;
                if (c == null) {
                    c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** {@code host:port} to a base URL, defaulting to plain HTTP - the payload is sealed anyway. */
    private static String normalise(String endpoint) {
        String base = endpoint.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String seal(byte[] key, String type, String payload) {
        String plain = "v1|" + System.currentTimeMillis() + "|" + type + "|"
            + (payload == null ? "" : payload);
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RNG.nextBytes(nonce);

            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[NONCE_LEN + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(ct, 0, out, NONCE_LEN, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("control encrypt failed", e);
        }
    }

    /** Opens a sealed reply into {@code [type, payload]}, or null on any failure. */
    private static String[] open(byte[] key, String b64) {
        try {
            byte[] blob = Base64.getUrlDecoder().decode(b64.trim());
            if (blob.length <= NONCE_LEN) return null;

            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(blob, 0, nonce, 0, NONCE_LEN);

            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plain = c.doFinal(blob, NONCE_LEN, blob.length - NONCE_LEN);

            String[] p = new String(plain, StandardCharsets.UTF_8).split("\\|", 4);
            if (p.length < 4 || !"v1".equals(p[0])) return null;
            return new String[]{p[2], p[3]};
        } catch (Exception e) {
            // Wrong secret, tampered or truncated - reject without saying why.
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
