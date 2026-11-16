package com.gathin.tvhome;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/** Bounded decoding, explicit cancellation and no artwork work while Home is paused. */
final class ShelfArtwork {
    interface Callback { void ready(Bitmap image); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(12), new ThreadPoolExecutor.AbortPolicy());
    private final List<Request> pending = new ArrayList<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private int generation;

    private static final class Request {
        final CancellationSignal signal = new CancellationSignal();
        Future<?> future;
        InputStream input;
        HttpURLConnection connection;
        boolean cancelled;
        synchronized void input(InputStream value) throws java.io.IOException {
            if (cancelled) { value.close(); throw new java.io.IOException("cancelled"); }
            input = value;
        }
        synchronized void connection(HttpURLConnection value) throws java.io.IOException {
            if (cancelled) { value.disconnect(); throw new java.io.IOException("cancelled"); }
            connection = value;
        }
        void cancel() {
            InputStream stream;
            HttpURLConnection http;
            synchronized (this) { cancelled = true; stream = input; http = connection; }
            signal.cancel();
            if (future != null) future.cancel(true);
            if (http != null) http.disconnect();
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        }
    }

    ShelfArtwork(Context context) { this.context = context.getApplicationContext(); }

    void load(String uri, int width, int height, Callback callback) {
        String key = uri + "@" + width + "x" + height;
        Bitmap cached = cache.get(key);
        if (cached != null) { callback.ready(cached); return; }
        final int ticket = generation;
        pending.removeIf(r -> r.future != null && r.future.isDone());
        // Discard obsolete queued cells first when the remote scrolls faster than decoding.
        if (pool.getQueue().remainingCapacity() == 0) {
            Runnable oldest = pool.getQueue().poll();
            if (oldest instanceof Future<?>) ((Future<?>) oldest).cancel(true);
        }
        Request request = new Request();
        try {
            request.future = pool.submit(() -> {
                Bitmap image = decode(request, uri, width, height);
                if (image == null || Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    if (ticket != generation) return;
                    cache.put(key, image);
                    callback.ready(image);
                });
            });
            pending.add(request);
        } catch (RejectedExecutionException ignored) { request.cancel(); }
    }

    void clear() {
        generation++;
        for (Request request : pending) request.cancel();
        pending.clear();
        pool.getQueue().clear();
        pool.purge();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
    void close() { clear(); pool.shutdownNow(); }

    private Bitmap decode(Request request, String value, int width, int height) {
        AssetFileDescriptor descriptor = null;
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(value);
            InputStream input;
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                connection = (HttpURLConnection) new URL(value).openConnection();
                request.connection(connection);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                if (connection.getResponseCode() != 200) return null;
                input = connection.getInputStream();
            } else if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r", request.signal);
                if (descriptor == null) return null;
                input = descriptor.createInputStream();
            } else return null;
            if (input == null) return null;
            request.input(input);
            byte[] bytes;
            try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) >= 0;) {
                    if (Thread.currentThread().isInterrupted() || out.size() + n > 8 * 1024 * 1024) return null;
                    out.write(chunk, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= width
                    && bounds.outHeight / (options.inSampleSize * 2) >= height) options.inSampleSize *= 2;
            while ((long) (bounds.outWidth / options.inSampleSize) * (bounds.outHeight / options.inSampleSize) > 2500000L)
                options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) { return null; }
        finally {
            if (connection != null) connection.disconnect();
            if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
            synchronized (request) { request.input = null; request.connection = null; }
        }
    }
}
