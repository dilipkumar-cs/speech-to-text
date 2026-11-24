package com.stt.util;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free detection of Hindi, Kannada, English and code-mixed text
 * (e.g. Hinglish, Kanglish).
 * <p>
 * Devanagari characters count as Hindi and Kannada-script characters as Kannada. Latin-script words count as
 * English unless they are common romanised Hindi or Kannada words ("kya", "hai", "illa", "beku"...),
 * which is how code-mixed speech is often written.
 */
public final class LanguageDetector {

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{M}]+");

    private static final Set<String> ROMANISED_HINDI = Set.of(
            "hai", "hain", "ho", "tha", "thi", "namaste", "namaskar", "duniya", "haal", "dhanyavaad", "shukriya", "kya", "kyun", "kyon", "kaise", "kaisa", "kab", "kahan",
            "nahi", "nahin", "haan", "mera", "meri", "mere", "tera", "teri", "tumhara", "aap", "aapka", "aapki",
            "hum", "hamara", "mai", "mujhe", "tum", "tujhe", "yeh", "ye", "woh", "wo", "accha", "acha",
            "theek", "thik", "bahut", "bohot", "kuch", "sab", "aur", "lekin", "toh", "bhi", "abhi",
            "kal", "aaj", "chalo", "karna", "karo", "kar", "raha", "rahi", "rahe", "gaya", "gayi", "hoga",
            "ka", "ki", "ke", "ko", "se", "mein", "liye", "wala", "wali", "ji", "yaar", "bhai", "matlab"
    );

    private static final Set<String> ROMANISED_KANNADA = Set.of(
            "naanu", "nanu", "neenu", "neevu", "nimma", "nanna", "namma", "avaru", "avanu", "avalu", "yenu", "enu",
            "yaake", "yake", "elli", "yelli", "hege", "hegiddira", "hegidiya", "chennagide", "chennagiddini",
            "illa", "ide", "iddini", "idini", "beku", "beda", "baa", "banni", "hogi", "hogona", "hogbeku", "maadi",
            "madi", "maadu", "gottilla", "gottu", "swalpa", "houdu", "howdu", "sari", "aytu", "ayitu", "guru",
            "oota", "mane", "naale", "ivattu", "ninne", "dhanyavadagalu", "namaskara", "kannada", "kelsa", "tumba"
    );

    private LanguageDetector() {
    }

    /**
     * @return the detected languages joined with "+" in a stable order (e.g. "hi", "kn", "en", "hi+en",
     * "kn+en", "hi+kn+en"), or null when the text has no letters.
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean hindi = false;
        boolean kannada = false;
        boolean english = false;
        for (String word : WORD_SPLIT.split(text)) {
            if (word.isEmpty()) {
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT);
            if (hasScript(word, Character.UnicodeBlock.DEVANAGARI) || ROMANISED_HINDI.contains(lower)) {
                hindi = true;
            } else if (hasScript(word, Character.UnicodeBlock.KANNADA) || ROMANISED_KANNADA.contains(lower)) {
                kannada = true;
            } else if (word.chars().anyMatch(Character::isLetter)) {
                english = true;
            }
        }
        Set<String> found = new LinkedHashSet<>();
        if (hindi) found.add("hi");
        if (kannada) found.add("kn");
        if (english) found.add("en");
        if (found.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("+");
        found.forEach(joiner::add);
        return joiner.toString();
    }

    private static boolean hasScript(String word, Character.UnicodeBlock block) {
        return word.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == block);
    }
}
