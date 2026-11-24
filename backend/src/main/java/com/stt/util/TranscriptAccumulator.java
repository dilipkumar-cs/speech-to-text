package com.stt.util;

/**
 * Builds the running transcript out of the engine's final segments plus the current interim hypothesis.
 * <p>
 * In Gemini's VERBATIM mode each final message is a new segment to append; in SMART mode it is the whole
 * session text so far and replaces what we have.
 */
public final class TranscriptAccumulator {

    private final boolean cumulativeFinals;
    private final StringBuilder finalText = new StringBuilder();
    private String interim = "";

    public TranscriptAccumulator(boolean cumulativeFinals) {
        this.cumulativeFinals = cumulativeFinals;
    }

    public synchronized void addFinal(String segment) {
        interim = "";
        if (segment == null || segment.isBlank()) {
            return;
        }
        if (cumulativeFinals) {
            finalText.setLength(0);
            finalText.append(segment.strip());
        } else {
            append(finalText, segment);
        }
    }

    public synchronized void setInterim(String text) {
        interim = text == null ? "" : text.strip();
    }

    public synchronized String finalText() {
        return finalText.toString().strip();
    }

    public synchronized String interim() {
        return interim;
    }

    /** Final text followed by whatever is still being recognised. */
    public synchronized String fullText() {
        StringBuilder sb = new StringBuilder(finalText);
        append(sb, interim);
        return sb.toString().strip();
    }

    private static void append(StringBuilder target, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        boolean needsSpace = !target.isEmpty()
                && !Character.isWhitespace(target.charAt(target.length() - 1))
                && !Character.isWhitespace(segment.charAt(0));
        if (needsSpace) {
            target.append(' ');
        }
        target.append(target.isEmpty() ? segment.stripLeading() : segment);
    }
}
