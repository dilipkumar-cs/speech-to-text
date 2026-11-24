package com.stt.transcription;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free detection of Hindi / English / code-mixed (Hinglish) text.
 * <p>
 * Devanagari characters are counted as Hindi. Latin-script words are counted as English unless they
 * are common romanised Hindi words ("kya", "hai", "nahi"...), which is how Hinglish is often written.
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

    private LanguageDetector() {
    }

    /** @return "hi", "en", "hi+en", or null when the text has no letters. */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int hindi = 0;
        int english = 0;
        for (String word : WORD_SPLIT.split(text)) {
            if (word.isEmpty()) {
                continue;
            }
            if (isDevanagari(word)) {
                hindi++;
            } else if (ROMANISED_HINDI.contains(word.toLowerCase(Locale.ROOT))) {
                hindi++;
            } else if (word.chars().anyMatch(Character::isLetter)) {
                english++;
            }
        }
        if (hindi == 0 && english == 0) {
            return null;
        }
        if (hindi > 0 && english > 0) {
            return "hi+en";
        }
        return hindi > 0 ? "hi" : "en";
    }

    private static boolean isDevanagari(String word) {
        return word.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.DEVANAGARI);
    }
}
