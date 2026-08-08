package fr.nyuway.newaddon.modules.dm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a line of chat into a direct message, or decides it is not one.
 *
 * <h2>Why regex and not a packet</h2>
 * There is no "this is a whisper" flag on the wire. Vanilla builds the line from a translation
 * key and sends the finished text; every server that rolls its own format sends something else
 * again. Reading the text is the only thing that works everywhere, which is also why the
 * patterns are a setting rather than a constant - an unknown server needs one line added, not
 * a new build.
 *
 * <h2>What a pattern must capture</h2>
 * Group 1 is the other person, group 2 is what was said. Anything that does not compile, or
 * does not have two groups, is dropped at load with a note rather than throwing later on a
 * chat line.
 */
public final class DmPatterns {

    /** Vanilla and the formats seen most often on public servers. */
    public static final List<String> DEFAULT_INCOMING = List.of(
        "^(\\w{3,16}) whispers to you: (.+)$",
        "^(\\w{3,16}) whispers: (.+)$",
        "^\\[(\\w{3,16}) -> me\\] (.+)$",
        "^From (\\w{3,16}): (.+)$",
        "^(\\w{3,16}) -> You: (.+)$"
    );

    public static final List<String> DEFAULT_OUTGOING = List.of(
        "^You whisper to (\\w{3,16}): (.+)$",
        "^\\[me -> (\\w{3,16})\\] (.+)$",
        "^To (\\w{3,16}): (.+)$",
        "^You -> (\\w{3,16}): (.+)$"
    );

    /** A matched message: who it was with, and what was said. */
    public record Hit(String peer, String text) { }

    private final List<Pattern> compiled = new ArrayList<>();

    /**
     * @param sources raw patterns from the settings
     * @param onBad   told about each pattern that could not be used, and why
     */
    public DmPatterns(List<String> sources, java.util.function.BiConsumer<String, String> onBad) {
        for (String raw : sources) {
            String source = raw.trim();
            if (source.isEmpty()) continue;

            try {
                Pattern p = Pattern.compile(source);
                if (p.matcher("").groupCount() < 2) {
                    onBad.accept(source, "needs two groups: who, then what");
                    continue;
                }
                compiled.add(p);
            } catch (PatternSyntaxException e) {
                onBad.accept(source, e.getDescription());
            }
        }
    }

    /** First pattern that matches, or null. */
    public Hit match(String line) {
        for (Pattern p : compiled) {
            Matcher m = p.matcher(line);
            if (!m.matches()) continue;

            String peer = m.group(1);
            String text = m.group(2);
            if (peer == null || text == null || peer.isBlank()) continue;

            return new Hit(peer, text);
        }
        return null;
    }

    public boolean isEmpty() {
        return compiled.isEmpty();
    }

    /**
     * Adds the lines of one of Livemessage's own pattern files, if it is there.
     *
     * <p>Upstream keeps custom server formats in {@code patterns/fromPatterns.txt} and
     * {@code patterns/toPatterns.txt}. Anyone who has already taught it about a server should
     * not have to teach this too.
     */
    public void addFile(java.nio.file.Path file,
                        java.util.function.BiConsumer<String, String> onBad) {
        if (!java.nio.file.Files.isRegularFile(file)) return;

        try {
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String source = line.trim();
                if (source.isEmpty()) continue;
                try {
                    Pattern p = Pattern.compile(source);
                    if (p.matcher("").groupCount() < 2) {
                        onBad.accept(source, "needs two groups: who, then what");
                        continue;
                    }
                    compiled.add(p);
                } catch (PatternSyntaxException e) {
                    onBad.accept(source, e.getDescription());
                }
            }
        } catch (java.io.IOException ignored) {
            // Unreadable is the same as absent here: the built-in patterns still apply.
        }
    }
}
