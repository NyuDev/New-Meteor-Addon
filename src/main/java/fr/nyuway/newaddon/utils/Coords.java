package fr.nyuway.newaddon.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a line of text is telling somebody where something is.
 *
 * <h2>Why not one regular expression</h2>
 * A single pattern for "a coordinate" has to hold every way people write one - {@code 1200 64
 * -900}, {@code x:1200 z:-900}, {@code 1200, 64, -900}, {@code ~ ~10 ~} - and then decide, in
 * the same breath, that {@code 1 2 3} in a sentence is not one. Patterns that try to do both
 * become unreadable and start backtracking badly on long messages.
 *
 * <p>So the numbers are found with a small pattern, and the judging is done in Java where it can
 * be read: numbers standing together with nothing but separators between them are a run, and a
 * run is a coordinate when it is long enough and either carries an x/y/z label or contains a
 * number too big to be anything else.
 *
 * <h2>What it deliberately does not catch</h2>
 * Someone spelling a location out in words. That is not a thing a filter can do, and pretending
 * otherwise would give a false sense of a guard that only ever stopped the obvious.
 */
public final class Coords {

    private Coords() { }

    /**
     * One number as people write them in coordinates: an optional tilde, an optional sign,
     * digits, and an optional decimal part. {@code 1.2e6} is not a coordinate anybody types.
     */
    private static final Pattern NUMBER = Pattern.compile("~?-?\\d{1,9}(?:\\.\\d+)?");

    /** An x, y or z label sitting in front of a number, with or without a colon or equals. */
    private static final Pattern LABEL = Pattern.compile("(?i)[xyz]\\s*[:=]?\\s*$");

    /** What may sit between two numbers of the same coordinate and nothing else. */
    private static final Pattern SEPARATOR = Pattern.compile("(?i)^[\\s,;/|]*(?:[xyz]\\s*[:=]?\\s*)?$");

    /**
     * Whether the text reads as a coordinate.
     *
     * @param minNumbers how many numbers standing together it takes; two catches an x/z pair
     * @param magnitude  a number at least this big, in absolute value, is taken as a coordinate
     *                   whatever else is around it. Small numbers need a label instead, so
     *                   "I got 3 4 5" is a sentence and "x3 y4 z5" is a location.
     */
    public static boolean present(String text, int minNumbers, int magnitude) {
        return find(text, minNumbers, magnitude) != null;
    }

    /** The offending run, for a message that says what it found, or null. */
    public static String find(String text, int minNumbers, int magnitude) {
        if (text == null || text.isBlank()) return null;

        List<int[]> spans = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            values.add(value(m.group()));
        }
        if (spans.size() < minNumbers) return null;

        int runStart = 0;
        for (int i = 1; i <= spans.size(); i++) {
            boolean joins = i < spans.size() && joined(text, spans.get(i - 1)[1], spans.get(i)[0]);
            if (joins) continue;

            int length = i - runStart;
            if (length >= minNumbers
                && (labelled(text, spans, runStart, i) || big(values, runStart, i, magnitude))) {
                return text.substring(spans.get(runStart)[0], spans.get(i - 1)[1]).trim();
            }
            runStart = i;
        }

        return null;
    }

    /** Whether two numbers belong to the same run: only separators and labels between them. */
    private static boolean joined(String text, int endOfFirst, int startOfSecond) {
        if (startOfSecond <= endOfFirst) return false;
        String between = text.substring(endOfFirst, startOfSecond);

        // A long gap is a sentence, not a separator, however innocent the characters in it.
        return between.length() <= 4 && SEPARATOR.matcher(between).matches();
    }

    /** Whether any number in the run is announced as an axis. */
    private static boolean labelled(String text, List<int[]> spans, int from, int to) {
        for (int i = from; i < to; i++) {
            int start = spans.get(i)[0];
            String before = text.substring(Math.max(0, start - 4), start);
            if (LABEL.matcher(before).find()) return true;
        }
        return false;
    }

    /** Whether any number in the run is too large to be a count of something. */
    private static boolean big(List<Double> values, int from, int to, int magnitude) {
        for (int i = from; i < to; i++) {
            if (Math.abs(values.get(i)) >= magnitude) return true;
        }
        return false;
    }

    private static double value(String token) {
        String clean = token.startsWith("~") ? token.substring(1) : token;
        if (clean.isEmpty() || clean.equals("-")) return 0;
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
