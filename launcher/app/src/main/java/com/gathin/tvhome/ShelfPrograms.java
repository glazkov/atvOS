package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads only the entries other apps make visible to READ_TV_LISTINGS. Call off the UI thread. */
final class ShelfPrograms {
    private static final String TAG = "PosterShelf";
    private static final String[] COMMON = {"package_name", "title", "poster_art_uri", "thumbnail_uri",
            "intent_uri", "last_playback_position_millis", "duration_millis"};

    static List<ShelfItems.Item> read(Context context, String source, long now, CancellationSignal signal) {
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED)
            return Collections.emptyList();
        List<ShelfItems.Item> items = new ArrayList<>();
        readTable(context, source, true, signal, Collections.emptyMap(), items);
        if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
        readTable(context, source, false, signal, channels(context, source, signal), items);
        return ShelfItems.choose(source, items, ShelfItems.LIMIT, now);
    }

    private static Map<Long, String> channels(Context context, String source, CancellationSignal signal) {
        Map<Long, String> result = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "package_name", "display_name"}, null, null, null, signal)) {
            int scanned = 0;
            if (c != null) while (scanned++ < 8192 && result.size() < 256 && c.moveToNext()) {
                if (Thread.currentThread().isInterrupted() || signal.isCanceled()) break;
                if (source.equals(c.getString(1))) result.put(c.getLong(0), c.getString(2));
            }
        } catch (RuntimeException e) { Log.w(TAG, "Cannot read preview channels: " + e.getClass().getSimpleName()); }
        return result;
    }

    private static void readTable(Context context, String source, boolean resume, CancellationSignal signal,
                                  Map<Long, String> channels, List<ShelfItems.Item> items) {
        List<String> fields = new ArrayList<>(java.util.Arrays.asList(COMMON));
        if (resume) {
            fields.add("last_engagement_time_utc_millis");
            fields.add("watch_next_type");
        } else fields.add("channel_id");
        Uri uri = Uri.parse("content://android.media.tv/" + (resume ? "watch_next_program" : "preview_program"));
        // A null projection can request vendor-only columns; a selection requires a privileged permission.
        try (Cursor c = context.getContentResolver().query(uri, fields.toArray(new String[0]), null, null, null, signal)) {
            int scanned = 0, matched = 0;
            if (c != null) while (scanned++ < 8192 && matched < 512 && c.moveToNext()) {
                if (Thread.currentThread().isInterrupted() || signal.isCanceled()) return;
                if (!source.equals(text(c, "package_name"))) continue;
                matched++;
                // Watch Next NEW promotions are not an actual continuation. CONTINUE=0, NEXT=1.
                if (resume && number(c, "watch_next_type") > 1) continue;
                String art = text(c, "poster_art_uri");
                String thumb = text(c, "thumbnail_uri");
                if ((ShelfItems.landscape(source) && !thumb.isEmpty()) || art.isEmpty()) art = thumb;
                int rank = resume ? 0 : channelRank(channels.get(number(c, "channel_id")));
                items.add(new ShelfItems.Item(source, text(c, "title"), art, text(c, "intent_uri"), resume,
                        number(c, "last_playback_position_millis"), number(c, "duration_millis"),
                        resume ? number(c, "last_engagement_time_utc_millis") : 0, rank));
            }
        } catch (RuntimeException e) { Log.w(TAG, "Cannot read " + uri.getLastPathSegment() + ": " + e.getClass().getSimpleName()); }
    }

    private static int channelRank(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("\u0441\u043c\u043e\u0442\u0440") || n.contains("watching")
                || n.contains("\u043f\u043e\u0434\u043f\u0438\u0441") || n.contains("subscriptions")) return 0;
        if (n.contains("\u043d\u043e\u0432\u044b\u0435 \u0444\u0438\u043b\u044c\u043c") || n.contains("new movies")) return 1;
        if (n.contains("\u043f\u043e\u043f\u0443\u043b\u044f\u0440") || n.contains("\u0440\u0435\u043a\u043e\u043c\u0435\u043d\u0434") || n.contains("recommend")) return 2;
        if (n.contains("\u0441\u0435\u0440\u0438\u0430\u043b") || n.contains("series")) return 3;
        if (n.contains("music")) return 5;
        return 4;
    }

    static Intent launchIntent(Context context, ShelfItems.Item item) {
        try {
            Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !item.packageName.equals(intent.getComponent().getPackageName())) return null;
            intent.setSelector(null);
            intent.setPackage(item.packageName);
            intent.setClipData(null);
            int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            intent.setFlags((intent.getFlags() & ~grants) | Intent.FLAG_ACTIVITY_NEW_TASK);
            return context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null ? null : intent;
        } catch (Exception ignored) { return null; }
    }

    private static String text(Cursor c, String name) {
        int at = c.getColumnIndex(name);
        return at < 0 || c.isNull(at) ? "" : c.getString(at);
    }
    private static long number(Cursor c, String name) {
        int at = c.getColumnIndex(name);
        return at < 0 || c.isNull(at) ? 0 : c.getLong(at);
    }
}
