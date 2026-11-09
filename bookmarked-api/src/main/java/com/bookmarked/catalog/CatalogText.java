package com.bookmarked.catalog;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure text helpers for the catalog: slugs, ISBNs and full-text search terms.
 */
public final class CatalogText {

    public static final String SLUG_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** MySQL full-text ignores words shorter than this (innodb_ft_min_token_size). */
    static final int MIN_FULLTEXT_WORD = 3;

    private CatalogText() {
    }

    /** "Clean Code: A Handbook" → "clean-code-a-handbook"; "Zoë's Café" → "zoes-cafe". */
    public static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String ascii = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String slug = NON_SLUG.matcher(ascii.toLowerCase(Locale.ROOT).replace("'", "")).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.length() > 250) {
            slug = slug.substring(0, 250).replaceAll("-+$", "");
        }
        return slug;
    }

    /**
     * Strips hyphens and spaces and checks the ISBN-10 or ISBN-13 checksum.
     *
     * @return the normalised ISBN (digits, plus a final X for some ISBN-10s), or empty if invalid
     */
    public static Optional<String> normalizeIsbn(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String isbn = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (isbn.matches("\\d{13}")) {
            int sum = 0;
            for (int i = 0; i < 12; i++) {
                sum += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
            }
            int check = (10 - sum % 10) % 10;
            return check == isbn.charAt(12) - '0' ? Optional.of(isbn) : Optional.empty();
        }
        if (isbn.matches("\\d{9}[\\dX]")) {
            int sum = 0;
            for (int i = 0; i < 10; i++) {
                char c = isbn.charAt(i);
                int value = c == 'X' ? 10 : c - '0';
                sum += value * (10 - i);
            }
            return sum % 11 == 0 ? Optional.of(isbn) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Lower-cased words of a user query with punctuation removed. */
    public static String[] words(String query) {
        if (query == null) {
            return new String[0];
        }
        return Arrays.stream(NON_WORD.split(query.toLowerCase(Locale.ROOT).trim()))
                .filter(w -> !w.isBlank())
                .limit(10)
                .toArray(String[]::new);
    }

    /**
     * Builds a MySQL boolean-mode query where every word is required and may be a
     * prefix: "clean cod" → "+clean* +cod*". Words too short for the full-text index
     * are skipped (the LIKE part of the search still covers them).
     *
     * @return the boolean query, or empty if no word is long enough
     */
    public static Optional<String> booleanFullTextQuery(String query) {
        String terms = Arrays.stream(words(query))
                .filter(w -> w.length() >= MIN_FULLTEXT_WORD)
                .map(w -> "+" + w + "*")
                .collect(Collectors.joining(" "));
        return terms.isEmpty() ? Optional.empty() : Optional.of(terms);
    }

    /**
     * Escape character for LIKE patterns. '!' rather than '\' because a backslash
     * inside a MySQL string literal is itself an escape and would need doubling.
     */
    public static final char LIKE_ESCAPE = '!';

    /** Escapes %, _ and ! so user input is matched literally inside LIKE ... ESCAPE '!'. */
    public static String likeContains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
