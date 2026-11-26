package be.fjodortypes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns pasted text into the page's script format (see monologues.js):
 * one line per paragraph on screen, "~" for a long pause, [p:ms] for a short one.
 * <ul>
 *   <li>A blank line between paragraphs becomes a long pause ("~").</li>
 *   <li>Prose is split into sentences, one per line; a very short sentence stays with the next one.</li>
 *   <li>Text already written in short lines (a poem, a letter) keeps its line breaks.</li>
 *   <li>"..." or "…" in the middle of a line gets a hesitation, a long dash a smaller one.</li>
 * </ul>
 * Writers may use the page's own syntax ([p:1500], ~, > for italics); it is kept, with limits.
 */
public final class Pauses {
    public static final int MAX_LINES = 120;

    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.!?…][\"'”’)]?)\\s+(?=[\"'“‘(¡¿A-Z0-9À-ÖØ-Þ])");
    private static final Pattern PAUSE = Pattern.compile("\\[p:(\\d{1,6})]");
    private static final Pattern DELETE = Pattern.compile("\\[del:(\\d{1,6})]");
    private static final Pattern ELLIPSIS_MID = Pattern.compile("(\\.{3}|…)(?!\\[p:)\\s*(?=\\S)");
    private static final Pattern DASH_MID = Pattern.compile("\\s+[—–]\\s+(?=\\S)");

    private Pauses() {
    }

    /** Pasted text in, script out. */
    public static String format(String raw) {
        List<String> out = new ArrayList<>();
        for (String paragraph : clean(raw).split("\n\\s*\n")) {
            List<String> lines = paragraph.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && !out.get(out.size() - 1).equals("~") && !lines.get(0).equals("~")) {
                out.add("~");
            }
            if (keepsItsLines(lines)) {
                for (String l : lines) {
                    out.add(isMarker(l) ? l : hesitate(l));
                }
            } else {
                for (String l : sentences(String.join(" ", lines))) {
                    out.add(hesitate(l));
                }
            }
        }
        return limit(out);
    }

    /** For a script someone already edited: tidy and keep within limits, but don't move anything. */
    public static String sanitize(String script) {
        List<String> out = new ArrayList<>();
        for (String l : clean(script).split("\n")) {
            String line = l.strip();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return limit(out);
    }

    /** Short lines (or the page's own markers) mean the writer chose the breaks. */
    private static boolean keepsItsLines(List<String> lines) {
        if (lines.size() == 1) {
            return false;
        }
        if (lines.stream().anyMatch(Pauses::isMarker)) {
            return true;
        }
        return lines.stream().mapToInt(String::length).average().orElse(0) < 70;
    }

    private static boolean isMarker(String line) {
        return line.equals("~") || line.equals("---");
    }

    private static List<String> sentences(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String s : SENTENCE_END.split(text.strip())) {
            if (!pending.isEmpty()) {
                pending.append(' ');
            }
            pending.append(s);
            // "No." or "Well." on its own is fine; three words in a row each on a line reads choppy
            if (pending.length() >= 22) {
                lines.add(pending.toString());
                pending.setLength(0);
            }
        }
        if (!pending.isEmpty()) {
            if (!lines.isEmpty() && pending.length() < 12) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + pending);
            } else {
                lines.add(pending.toString());
            }
        }
        return lines;
    }

    private static String hesitate(String line) {
        if (line.startsWith("> ")) {
            return "> " + hesitate(line.substring(2));
        }
        String l = ELLIPSIS_MID.matcher(line).replaceAll("$1[p:700] ");
        l = DASH_MID.matcher(l).replaceAll(" —[p:300] ");
        return l.replace("[p:700] [p:", "[p:");
    }

    /** Same characters the page understands, nothing that could surprise it. */
    private static String clean(String raw) {
        String s = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            if (c == '\n' || !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT || c == 0x200D) {
                sb.appendCodePoint(c);
            }
        });
        String t = sb.toString().replaceAll(" {2,}", " ");
        t = clamp(PAUSE, t, "[p:%d]", 6000);
        return clamp(DELETE, t, "[del:%d]", 40);
    }

    private static String clamp(Pattern p, String text, String format, int max) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Math.min(Integer.parseInt(m.group(1)), max);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.format(format, n)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String limit(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            boolean marker = isMarker(l);
            if (out.isEmpty() && marker) {
                continue; // no pause before the first word
            }
            if (marker && !out.isEmpty() && isMarker(out.get(out.size() - 1))) {
                continue;
            }
            out.add(l);
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        while (!out.isEmpty() && isMarker(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return String.join("\n", out);
    }
}
