package com.arthur.jdragresume.collect;

/** What to search for. Each collector checks the values against its own limits in {@link JobCollector#start}. */
public record CollectRequest(String keyword, String city, int maxPages, String experience) {
    public CollectRequest(String keyword, String city, int maxPages) {
        this(keyword, city, maxPages, "不限");
    }

    /** JavaScript's \s, which the extension normalizes with: Unicode white space (full-width too) plus BOM. */
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("(?:(?U)\\s|\\uFEFF)+");

    public CollectRequest {
        keyword = keyword == null ? "" : SPACES.matcher(keyword).replaceAll(" ").strip();
        city = city == null ? "" : city.trim();
        experience = experience == null ? "不限" : experience.trim();
    }
}
