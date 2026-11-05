package com.arthur.jdragresume.common;

/** Use the same literal LIKE semantics in both MyBatis and JPA searches (SQL ESCAPE '!'). */
public final class LikeKeywords {
    public static final char LIKE_ESCAPE = '!';

    private LikeKeywords() {
    }

    public static String escapeLikeKeyword(String keyword) {
        if (keyword == null) return null;
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return null;
        StringBuilder escaped = new StringBuilder(trimmed.length() + 8);
        for (int i = 0; i < trimmed.length(); i++) {
            char value = trimmed.charAt(i);
            if (value == LIKE_ESCAPE || value == '%' || value == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(value);
        }
        return escaped.toString();
    }
}
