package com.gathin.tvhome;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;
import java.util.concurrent.*;

/** A content carousel independent of dock layout and the launcher's cinematic title. */
final class PosterShelf extends FrameLayout {
    private static final float FOCUS_SCALE = 270f / 234f;
    interface Listener {
        void toDock();
        void toHeader();
        void open(ShelfItems.Item item);
        void contentAvailable(boolean available);
    }
    private final float density;
    private final boolean animations, reducedMotion, overshoot;
    private final Listener listener;
    private final RecyclerView list;
    private final Cards cards = new Cards();
    private final ShelfArtwork artwork;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    // Metadata only: keep the actual image shape across source switches without retaining bitmaps.
    private final Map<String, Integer> artworkWidths = new LinkedHashMap<String, Integer>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) { return size() > 256; }
    };
    private final int imageHeight, gap, verticalPadding;
    private Future<?> read;
    private CancellationSignal querySignal;
    private ViewTreeObserver.OnGlobalLayoutListener pendingFocusLayout;
    private String source = "";
    private int generation, focusGeneration, selected, itemWidth;
    private boolean active, homeShown, closed;
    private Boolean lastContentAvailable;
    private final Runnable fetch = this::fetch;

    private static final class Snapshot {
        final List<ShelfItems.Item> items;
        final long at;
        Snapshot(List<ShelfItems.Item> items) { this.items = items; at = android.os.SystemClock.elapsedRealtime(); }
    }

    PosterShelf(Context context, boolean animations, boolean reducedMotion, boolean overshoot, Listener listener) {
        super(context);
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.overshoot = overshoot;
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        imageHeight = dp(234);
        gap = dp(24);
        verticalPadding = dp(8);
        artwork = new ShelfArtwork(context);
        setId(R.id.posterShelf);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        list = new RecyclerView(context);
        list.setId(R.id.shelfList);
        list.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setFocusable(false);
        list.setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        list.setAdapter(cards);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(INVISIBLE);
    }

    int shelfHeight() { return imageHeight + dp(44) + 2 * verticalPadding; }

    /** Measure from the unscaled image bottom to the real top of the 116dp dock band. */
    void alignAboveDock(float bandTop) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int top = Math.round(bandTop - dp(60) - imageHeight - verticalPadding);
        if (lp.topMargin != top) { lp.topMargin = top; setLayoutParams(lp); }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dimensions(w);
    }

    private void dimensions(int width) {
        int portrait = dp(156);
        int edge = Math.max(0, (width - 5 * portrait - 4 * gap) / 2);
        list.setPadding(edge, verticalPadding, edge, verticalPadding);
        int nextWidth = ShelfItems.landscape(source) ? ShelfItems.bannerWidth(imageHeight) : portrait;
        if (nextWidth != itemWidth) {
            itemWidth = nextWidth;
            cards.notifyDataSetChanged();
        }
    }

    void resume() {
        if (closed) return;
        active = true;
        cards.notifyDataSetChanged();
        visibility();
        schedule(true);
    }

    void pause() {
        cancelFocusRequests();
        active = false;
        cancelRead();
        artwork.clear();
        for (int n = 0; n < list.getChildCount(); n++) {
            RecyclerView.ViewHolder h = list.getChildViewHolder(list.getChildAt(n));
            if (h instanceof Holder) {
                ((Holder) h).image.setImageDrawable(null);
                ((Holder) h).card.content.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }
        visibility();
    }

    void close() {
        closed = true;
        pause();
        reader.shutdownNow();
        artwork.close();
        snapshots.clear();
        artworkWidths.clear();
    }

    void showSource(String packageName) {
        if (closed) return;
        boolean changed = !source.equals(packageName);
        homeShown = true;
        if (changed) {
            cancelFocusRequests();
            cancelRead();
            artwork.clear();
            source = packageName;
            selected = 0;
            Snapshot cached = snapshots.get(source);
            cards.items = cached == null ? Collections.emptyList() : cached.items;
            dimensions(getWidth());
            cards.notifyDataSetChanged();
            list.scrollToPosition(0);
        }
        visibility();
        schedule(false);
    }

    void homeVisible(boolean shown) {
        if (homeShown == shown) return;
        homeShown = shown;
        if (!shown) { cancelFocusRequests(); cancelRead(); artwork.clear(); }
        else { cards.notifyDataSetChanged(); schedule(false); }
        visibility();
    }

    private void visibility() {
        boolean available = !cards.items.isEmpty();
        setVisibility(active && homeShown && available ? VISIBLE : INVISIBLE);
        if (lastContentAvailable == null || lastContentAvailable != available) {
            lastContentAvailable = available;
            listener.contentAvailable(available);
        }
    }

    private void schedule(boolean force) {
        if (!active || !homeShown || source.isEmpty() || closed) return;
        Snapshot snapshot = snapshots.get(source);
        if (!force && snapshot != null && android.os.SystemClock.elapsedRealtime() - snapshot.at < 30000) return;
        main.removeCallbacks(fetch);
        main.postDelayed(fetch, 140);
    }

    private void cancelRead() {
        generation++;
        main.removeCallbacks(fetch);
        if (querySignal != null) { querySignal.cancel(); querySignal = null; }
        if (read != null) { read.cancel(true); read = null; }
        reader.purge();
    }

    private void fetch() {
        if (!active || !homeShown || source.isEmpty() || closed) return;
        cancelRead();
        final String app = source;
        final int ticket = generation;
        final CancellationSignal signal = new CancellationSignal();
        querySignal = signal;
        read = reader.submit(() -> {
            List<ShelfItems.Item> result = ShelfPrograms.read(getContext().getApplicationContext(), app, System.currentTimeMillis(), signal);
            if (Thread.currentThread().isInterrupted()) return;
            main.post(() -> {
                if (closed || !active || !homeShown || generation != ticket || !source.equals(app)) return;
                snapshots.put(app, new Snapshot(result));
                boolean ownedFocus = list.hasFocus();
                cards.items = result;
                selected = Math.min(selected, Math.max(0, result.size() - 1));
                cards.notifyDataSetChanged();
                visibility();
                if (ownedFocus) {
                    if (result.isEmpty()) listener.toDock();
                    else focusItem(selected);
                }
            });
        });
    }

    boolean focusItems() {
        if (!active || !homeShown || cards.items.isEmpty() || getVisibility() != VISIBLE) return false;
        focusItem(selected);
        return true;
    }

    void cancelFocusRequests() {
        focusGeneration++;
        if (pendingFocusLayout != null) {
            ViewTreeObserver tree = list.getViewTreeObserver();
            if (tree.isAlive()) tree.removeOnGlobalLayoutListener(pendingFocusLayout);
            pendingFocusLayout = null;
        }
    }

    private void focusItem(int position) {
        cancelFocusRequests();
        final int ticket = focusGeneration;
        final String app = source;
        int target = Math.max(0, Math.min(position, cards.items.size() - 1));
        list.scrollToPosition(target);
        list.post(() -> {
            if (!active || !homeShown || closed || ticket != focusGeneration || !source.equals(app) || target >= cards.items.size()) return;
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(target);
            if (h instanceof Holder) ((Holder) h).card.requestFocus();
            else {
                pendingFocusLayout = new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        if (!active || !homeShown || closed || ticket != focusGeneration || !source.equals(app)) {
                            cancelFocusRequests();
                            return;
                        }
                        RecyclerView.ViewHolder ready = list.findViewHolderForAdapterPosition(target);
                        if (ready != null) {
                            ViewTreeObserver tree = list.getViewTreeObserver();
                            if (tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
                            pendingFocusLayout = null;
                            ((Holder) ready).card.requestFocus();
                        }
                    }
                };
                list.getViewTreeObserver().addOnGlobalLayoutListener(pendingFocusLayout);
                list.requestLayout();
            }
        });
    }

    private int dp(float value) { return Math.round(value * density); }

    private final class Cards extends RecyclerView.Adapter<Holder> {
        List<ShelfItems.Item> items = Collections.emptyList();
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(); }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            ShelfItems.Item item = items.get(position);
            RecyclerView.LayoutParams size = new RecyclerView.LayoutParams(artworkWidths.getOrDefault(item.artUri, itemWidth), imageHeight + dp(44));
            size.rightMargin = position == items.size() - 1 ? 0 : gap;
            holder.itemView.setLayoutParams(size);
            holder.sizeArtwork(size.width);
            holder.syncFocusScale();
            holder.itemView.setTranslationZ(holder.card.isFocused() ? dp(8) : 0);
            holder.card.updateRim();
            holder.card.content.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            holder.image.setImageDrawable(null);
            String displayTitle = ShelfItems.displayTitle(item.title);
            holder.title.setText(displayTitle);
            holder.title.animate().cancel();
            holder.title.setAlpha(1f);
            holder.updateVisuals();
            holder.card.setContentDescription(displayTitle);
            holder.progress.fraction = item.progress();
            holder.progress.setVisibility(item.progress() > 0 ? VISIBLE : GONE);
            holder.progress.invalidate();
            String tag = item.packageName + "|" + item.artUri;
            holder.image.setTag(tag);
            if (active && homeShown) artwork.load(item.artUri, ShelfItems.bannerWidth(imageHeight), ShelfItems.bannerHeight(imageHeight), image -> {
                if (!active || !homeShown || !tag.equals(holder.image.getTag())) return;
                int resolvedWidth = ShelfItems.artworkWidth(image.getWidth(), image.getHeight(), imageHeight);
                artworkWidths.put(item.artUri, resolvedWidth);
                ViewGroup.LayoutParams actual = holder.itemView.getLayoutParams();
                boolean widthChanged = actual.width != resolvedWidth;
                if (widthChanged) {
                    actual.width = resolvedWidth;
                    holder.itemView.setLayoutParams(actual);
                }
                boolean heightChanged = holder.sizeArtwork(resolvedWidth);
                if (widthChanged || heightChanged) holder.syncFocusScale();
                holder.image.setImageBitmap(image);
            });
        }
        @Override public void onViewRecycled(@NonNull Holder holder) {
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
            holder.card.animate().cancel();
            holder.title.animate().cancel();
            holder.card.content.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final PosterCard card;
        final ImageView image;
        final TextView title;
        final Progress progress;
        float focusedScaleX = FOCUS_SCALE, focusedScaleY = FOCUS_SCALE;

        boolean sizeArtwork(int width) {
            boolean wide = width > imageHeight;
            int height = wide ? ShelfItems.bannerHeight(imageHeight) : imageHeight;
            int top = wide ? ShelfItems.bannerTopOffset(imageHeight) : 0;
            LayoutParams size = (LayoutParams) card.getLayoutParams();
            boolean changed = size.height != height || size.topMargin != top;
            if (changed) {
                size.height = height;
                size.topMargin = top;
                card.setLayoutParams(size);
            }
            LayoutParams captionSize = (LayoutParams) title.getLayoutParams();
            int captionTop = top + height + dp(2);
            if (captionSize.topMargin != captionTop) {
                captionSize.topMargin = captionTop;
                title.setLayoutParams(captionSize);
            }
            focusedScaleX = dp(wide ? 452 : 180) / (float) width;
            focusedScaleY = dp(wide ? 256 : 270) / (float) height;
            card.focusTargetScale = focusedScaleX;
            return changed;
        }

        void syncFocusScale() {
            card.animate().cancel();
            boolean focused = card.isFocused() && animations;
            card.setScaleX(focused ? focusedScaleX : 1f);
            card.setScaleY(focused ? focusedScaleY : 1f);
            updateVisuals();
        }

        void updateVisuals() {
            card.updateRim();
            // Always visible; follow the image bottom with the same zoom curve and a constant 2dp gap.
            title.setTranslationY(card.getLayoutParams().height * (card.getScaleY() - 1f) / 2f);
        }

        Holder() {
            super(new FrameLayout(getContext()));
            FrameLayout container = (FrameLayout) itemView;
            container.setClipChildren(false);
            container.setClipToPadding(false);
            container.setFocusable(false);
            card = new PosterCard(getContext());
            container.addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, imageHeight));
            card.setId(R.id.shelfCard);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClickable(true);
            image = new ImageView(getContext());
            image.setId(R.id.shelfImage);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.content.addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            title = new TextView(getContext());
            title.setId(R.id.shelfCaption);
            title.setTextColor(0xF2FFFFFF);
            title.setTextSize(12.5f);
            title.setTypeface(getResources().getFont(R.font.outfit_medium));
            title.setLetterSpacing(0f);
            title.setIncludeFontPadding(false);
            title.setSingleLine(true);
            title.setGravity(Gravity.CENTER);
            title.setAlpha(1f);
            title.setTranslationY(0f);
            TextViewCompat.setLineHeight(title, Math.round(16 * getResources().getDisplayMetrics().scaledDensity));
            title.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams caption = new LayoutParams(LayoutParams.MATCH_PARENT, dp(22), Gravity.TOP);
            caption.topMargin = imageHeight + dp(2);
            container.addView(title, caption);
            progress = new Progress(getContext());
            progress.setId(R.id.shelfProgress);
            LayoutParams bar = new LayoutParams(LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM);
            bar.leftMargin = bar.rightMargin = dp(14); bar.bottomMargin = dp(14);
            card.content.addView(progress, bar);
            card.setOnFocusChangeListener((v, focused) -> {
                int position = getBindingAdapterPosition();
                if (focused && position != RecyclerView.NO_POSITION) selected = position;
                // Wide cards may grow past the gap: keep the selected image above its neighbours.
                itemView.setTranslationZ(focused ? dp(8) : 0);
                float scaleX = focused && animations ? focusedScaleX : 1f;
                float scaleY = focused && animations ? focusedScaleY : 1f;
                card.animate().cancel();
                if (!animations) { card.setScaleX(1f); card.setScaleY(1f); updateVisuals(); }
                else if (reducedMotion) card.animate().scaleX(scaleX).scaleY(scaleY)
                        .setDuration(focused ? Motion.REDUCED_MS : 60)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .setUpdateListener(a -> updateVisuals()).start();
                else card.animate().scaleX(scaleX).scaleY(scaleY).setDuration(focused ? 260 : 180)
                        .setInterpolator(Motion.cardFocusZoom(focused && overshoot))
                        .setUpdateListener(a -> updateVisuals()).start();
            });
            card.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { cancelFocusRequests(); listener.toDock(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_UP) { cancelFocusRequests(); listener.toHeader(); return true; }
                int position = getBindingAdapterPosition();
                return key == KeyEvent.KEYCODE_DPAD_LEFT && position <= 0
                        || key == KeyEvent.KEYCODE_DPAD_RIGHT && position >= cards.items.size() - 1;
            });
            card.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && position < cards.items.size()) listener.open(cards.items.get(position));
            });
        }
    }

    /** Cache the clipped image separately: animating the rim must not repaint a poster-sized mask. */
    private final class PosterCard extends FrameLayout {
        final ClippedSurface content;
        final CardRim rim;
        float focusTargetScale = FOCUS_SCALE;
        PosterCard(Context context) {
            super(context);
            content = new ClippedSurface(context);
            rim = new CardRim(context);
            addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(rim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        void updateRim() { rim.updateScale(getScaleX(), focusTargetScale); }
    }

    private final class ClippedSurface extends FrameLayout {
        final Path mask = new Path();
        final RectF bounds = new RectF();
        ClippedSurface(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(0xFF161922);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            bounds.set(0, 0, w, h);
            mask.rewind();
            SmoothCorners.add(mask, bounds, dp(16));
        }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(mask);
            super.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private final class CardRim extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF bounds = new RectF();
        int alpha = -1;
        CardRim(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.75f * density);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            updateScale(1f, FOCUS_SCALE);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float inset = .5f * density;
            bounds.set(inset, inset, w - inset, h - inset);
            path.rewind();
            SmoothCorners.add(path, bounds, dp(16) - inset);
            paint.setShader(PreviewGlass.edgeSheen(0, h));
        }
        void updateScale(float scale, float targetScale) {
            float focus = Math.max(0f, Math.min(1f, (scale - 1f) / (targetScale - 1f)));
            int next = PreviewGlass.edgeAlpha(focus);
            if (next != alpha) { alpha = next; paint.setAlpha(next); invalidate(); }
        }
        @Override protected void onDraw(Canvas canvas) { canvas.drawPath(path, paint); }
    }
    private static final class Progress extends View {
        float fraction;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Progress(Context context){super(context);}
        @Override protected void onDraw(Canvas canvas){
            paint.setColor(0x66FFFFFF);canvas.drawRoundRect(0,0,getWidth(),getHeight(),getHeight()/2f,getHeight()/2f,paint);
            paint.setColor(Color.WHITE);canvas.drawRoundRect(0,0,getWidth()*fraction,getHeight(),getHeight()/2f,getHeight()/2f,paint);
        }
    }
}
