package com.gathin.tvhome;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Data rules shared by the reader and carousel, independent of Android views. */
final class ShelfItems {
    private static final long DAY = 86400000L;
    static final int LIMIT = 40;

    static final class Item {
        final String packageName, title, artUri, intentUri;
        final boolean continuation;
        final long position, duration, engagedAt;
        final int rank;

        Item(String packageName, String title, String artUri, String intentUri, boolean continuation,
             long position, long duration, long engagedAt, int rank) {
            this.packageName = clean(packageName);
            this.title = clean(title);
            this.artUri = clean(artUri);
            this.intentUri = clean(intentUri);
            this.continuation = continuation;
            this.position = position;
            this.duration = duration;
            this.engagedAt = engagedAt;
            this.rank = rank;
        }

        float progress() {
            return continuation && duration > 0 && position > 0
                    ? (float) Math.min(1d, (double) position / duration) : 0f;
        }

        String key() {
            // A preview and continuation may link to different screens of the same title.
            String art = artUri.split("[?#]", 2)[0];
            return title.toLowerCase(Locale.ROOT) + "|" + art;
        }
    }

    static List<Item> choose(String source, List<Item> candidates, int limit, long now) {
        if (source == null || limit <= 0) return Collections.emptyList();
        List<Item> sorted = new ArrayList<>();
        for (Item item : candidates) {
            if (!source.equals(item.packageName) || item.title.isEmpty() || item.intentUri.isEmpty()
                    || !validArt(item.artUri)) continue;
            if (item.continuation && (item.progress() >= .98f
                    || item.engagedAt > now + DAY
                    || item.engagedAt > 0 && item.engagedAt < now - 90 * DAY)) continue;
            sorted.add(item);
        }
        sorted.sort(Comparator.comparing((Item item) -> !item.continuation)
                .thenComparing((a, b) -> a.continuation && b.continuation
                        ? Long.compare(b.engagedAt, a.engagedAt) : Integer.compare(a.rank, b.rank)));
        Set<String> seen = new HashSet<>();
        List<Item> chosen = new ArrayList<>();
        for (Item item : sorted) {
            if (!seen.add(item.key())) continue;
            chosen.add(item);
            if (chosen.size() >= limit) break;
        }
        return Collections.unmodifiableList(chosen);
    }

    /** Prefer the Russian translation for captions, retaining the published title for identity. */
    static String displayTitle(String title) {
        String value = clean(title);
        String[] variants = value.split("\\s+/\\s+", -1);
        String fallback = null;
        for (String variant : variants) {
            String candidate = variant.trim();
            if (candidate.isEmpty()) continue;
            if (fallback == null) fallback = candidate;
            for (int i = 0; i < candidate.length(); i++) {
                char c = candidate.charAt(i);
                if (c >= 'А' && c <= 'я' || c == 'Ё' || c == 'ё') return candidate;
            }
        }
        return fallback == null ? value : fallback;
    }

    static int posterWidth(int screenWidth, int edge, int gap) {
        return Math.max(1, (screenWidth - 2 * edge - 4 * gap) / 5);
    }

    static int bannerWidth(int posterHeight) {
        return Math.max(1, Math.round(posterHeight * 426f / 234f));
    }

    static int bannerHeight(int posterHeight) {
        return Math.max(1, Math.round(posterHeight * 240f / 234f));
    }

    static int bannerTopOffset(int posterHeight) {
        // Centre the extra height, then apply the owner's additional 7dp lift.
        return Math.round((posterHeight - bannerHeight(posterHeight)) / 2f)
                - Math.round(posterHeight * 7f / 234f);
    }

    static int artworkWidth(int actualWidth, int actualHeight, int cardHeight) {
        return actualWidth > actualHeight ? bannerWidth(cardHeight)
                : Math.max(1, Math.round(cardHeight * 2f / 3f));
    }

    static boolean landscape(String pkg) {
        return "com.google.android.youtube.tv".equals(pkg) || "ru.rutube.app.tv".equals(pkg)
                || "com.vk.tv".equals(pkg) || "ru.kinopoisk.tv".equals(pkg) || "ru.ivi.client".equals(pkg);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static boolean validArt(String value) {
        try {
            String scheme = URI.create(value).getScheme();
            return "https".equals(scheme) || "http".equals(scheme)
                    || "content".equals(scheme) || "android.resource".equals(scheme);
        } catch (IllegalArgumentException ignored) { return false; }
    }
}
