package com.ch4nhm.tvapp;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImageLoader {

    private static final String TAG = "TVBoxImage";
    private static final int MIN_USABLE_IMAGE_SIDE = 24;
    private static final String DEFAULT_REFERER = "https://movie.douban.com/";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/140.0.0.0 Safari/537.36";
    private static final Pattern HEADER_MARKER = Pattern.compile(
            "@(Referer|User-Agent)=", Pattern.CASE_INSENSITIVE);

    private ImageLoader() {
    }

    public static void poster(ImageView view, String url, int fallback, float cornerDp) {
        loadPoster(view, null, null, url, fallback, cornerDp);
    }

    public static void poster(ImageView view, String title, String year,
                              String url, int fallback, float cornerDp) {
        loadPoster(view, title, year, url, fallback, cornerDp);
    }

    public static void largePoster(ImageView view, String url, int fallback) {
        invalidate(view);
        TargetSize target = largePosterTarget(view);
        load(view, null, null, url, fallback, 0, target.widthDp, target.heightDp,
                false, true, false, true);
    }

    public static void largePoster(ImageView view, String title, String year,
                                   String url, int fallback) {
        TargetSize target = largePosterTarget(view);
        loadWithUpgrade(view, title, year, url, fallback,
                0, target.widthDp, target.heightDp, false, true, false, true);
    }

    public static void largePoster(ImageView view, String title, String year,
                                   String url, int fallback, float cornerDp) {
        TargetSize target = largePosterTarget(view);
        loadWithUpgrade(view, title, year, url, fallback,
                cornerDp, target.widthDp, target.heightDp, false, true, false, true);
    }

    public static void backdrop(ImageView view, String url, int fallback) {
        invalidate(view);
        TargetSize target = backdropTarget(view);
        load(view, null, null, url, fallback, 0, target.widthDp, target.heightDp,
                false, false, true, false);
    }

    public static void backdrop(ImageView view, String title, String year,
                                String url, int fallback) {
        TargetSize target = backdropTarget(view);
        loadWithUpgrade(view, title, year, url, fallback,
                0, target.widthDp, target.heightDp, false, false, true, false);
    }

    public static void widePoster(ImageView view, String url, int fallback, float cornerDp) {
        invalidate(view);
        TvLayoutMetrics metrics = TvLayout.metrics(view.getContext());
        load(view, null, null, url, fallback, cornerDp,
                metrics.continueCardWidthDp, metrics.continueImageHeightDp,
                false, false, false, true);
    }

    public static void widePoster(ImageView view, String title, String year, String url,
                                  int fallback, float cornerDp) {
        TvLayoutMetrics metrics = TvLayout.metrics(view.getContext());
        loadWithUpgrade(view, title, year, url, fallback,
                cornerDp, metrics.continueCardWidthDp, metrics.continueImageHeightDp,
                false, false, false, true);
    }

    static void channelLogo(ImageView view, String url, Runnable onLoaded) {
        if (view == null) return;
        invalidate(view);
        Drawable transparent = new ColorDrawable(Color.TRANSPARENT);
        view.setImageDrawable(transparent);
        if (url == null || url.isBlank()) return;
        int width = view.getWidth() > 0 ? view.getWidth() : Math.round(dp(view, 220));
        int height = view.getHeight() > 0 ? view.getHeight() : Math.round(dp(view, 90));
        RequestOptions options = new RequestOptions()
                .placeholder(transparent)
                .error(transparent)
                .fallback(transparent)
                .fitCenter()
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(Math.max(1, width), Math.max(1, height));
        request(view, url, transparent, options, null, true, true, onLoaded);
    }

    private static TargetSize largePosterTarget(ImageView view) {
        if (view == null) return new TargetSize(150, 225);
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            float density = Math.max(0.1f,
                    view.getResources().getDisplayMetrics().density);
            return new TargetSize(Math.max(1, Math.round(view.getWidth() / density)),
                    Math.max(1, Math.round(view.getHeight() / density)));
        }
        TvLayoutMetrics metrics = TvLayout.metrics(view.getContext());
        return new TargetSize(metrics.detailPosterWidthDp,
                metrics.detailPosterHeightDp);
    }

    private static TargetSize backdropTarget(ImageView view) {
        if (view == null) return new TargetSize(960, 280);
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            float density = Math.max(0.1f,
                    view.getResources().getDisplayMetrics().density);
            return new TargetSize(Math.max(1, Math.round(view.getWidth() / density)),
                    Math.max(1, Math.round(view.getHeight() / density)));
        }
        TvViewport viewport = TvDisplayAdapter.viewport(view.getContext());
        TvLayoutMetrics metrics = TvLayout.metrics(view.getContext());
        return new TargetSize(Math.max(320, Math.round(viewport.usableWidthDp)),
                Math.max(180, metrics.detailHeroHeightDp));
    }

    private static final class TargetSize {
        final int widthDp;
        final int heightDp;

        TargetSize(int widthDp, int heightDp) {
            this.widthDp = widthDp;
            this.heightDp = heightDp;
        }
    }

    private static void loadPoster(ImageView view, String title, String year,
                                   String url, int fallback, float cornerDp) {
        if (view == null) return;
        if (title != null && !title.isBlank()) {
            int targetWidth = TvDisplayAdapter.posterCardWidthDp(view.getContext());
            loadWithUpgrade(view, title, year, url, fallback,
                    0, targetWidth, Math.round(targetWidth * 1.5f),
                    true, true, false, true);
            return;
        }
        invalidate(view);
        int width = Math.max(1,
                Math.round(dp(view, TvDisplayAdapter.posterCardWidthDp(view.getContext()))));
        int height = Math.max(1, Math.round(width * 1.5f));
        Drawable placeholder = placeholder(view, R.drawable.poster_placeholder, null);
        if (url != null && url.startsWith("//")) url = "https:" + url;
        RequestOptions options = new RequestOptions()
                .placeholder(placeholder)
                .error(placeholder)
                .fallback(placeholder)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(width, height);
        options = options.transform(new MultiTransformation<>(
                new PosterFit(true, ThemeManager.accent(view.getContext())),
                new RoundedCorners(Math.round(dp(view, cornerDp)))));
        request(view, url, placeholder, options, null);
    }

    private static void loadWithUpgrade(ImageView view, String title, String year,
                                        String url, int fallback, float cornerDp,
                                        int targetWidthDp, int targetHeightDp,
                                        boolean usePlaceholder, boolean preservePoster,
                                        boolean ambient, boolean enhance) {
        if (view == null) return;
        if (title == null || title.isBlank()) {
            invalidate(view);
            load(view, title, year, url, fallback, cornerDp, targetWidthDp, targetHeightDp,
                    usePlaceholder, preservePoster, ambient, enhance);
            return;
        }

        Object token = new Object();
        view.setTag(R.id.image_request_token, token);
        ArtworkResolver resolver = ArtworkResolver.get(view.getContext());
        String known = resolver.cachedFast(title, year);
        if (known != null && ArtworkResolver.isGoodArtwork(known)) {
            load(view, title, year, known, url, fallback, cornerDp, targetWidthDp, targetHeightDp,
                    usePlaceholder, preservePoster, ambient, enhance);
            return;
        }

        String optimized = ArtworkResolver.upgradeUrl(url);
        if (ArtworkResolver.hasRemoteArtwork(url)
                && ArtworkResolver.isGoodArtwork(optimized)) {
            load(view, title, year, optimized, url, fallback, cornerDp, targetWidthDp,
                    targetHeightDp, usePlaceholder, preservePoster, ambient, enhance);
            return;
        }

        load(view, title, year, optimized, url, fallback, cornerDp, targetWidthDp,
                targetHeightDp, usePlaceholder, preservePoster, ambient, enhance);
        resolver.upgrade(title, year, resolved -> {
            if (view.getTag(R.id.image_request_token) != token) return;
            view.setTag(R.id.image_request_token, null);
            load(view, title, year, resolved, url, fallback, cornerDp,
                    targetWidthDp, targetHeightDp, usePlaceholder, preservePoster,
                    ambient, enhance);
        });
    }

    private static void load(ImageView view, String title, String year, String url, int fallback,
                             float cornerDp, int targetWidthDp, int targetHeightDp,
                             boolean usePlaceholder, boolean preservePoster,
                             boolean ambient, boolean enhance) {
        load(view, title, year, url, null, fallback, cornerDp, targetWidthDp, targetHeightDp,
                usePlaceholder, preservePoster, ambient, enhance);
    }

    private static void load(ImageView view, String title, String year, String url,
                             String retryUrl, int fallback, float cornerDp,
                             int targetWidthDp, int targetHeightDp,
                             boolean usePlaceholder, boolean preservePoster,
                             boolean ambient, boolean enhance) {
        if (view == null) return;
        String fallbackUrl = ArtworkResolver.hasRemoteArtwork(retryUrl) ? retryUrl : null;
        if (title != null && !title.isBlank() && ArtworkResolver.hasRemoteArtwork(url)) {
            String rawUrl = url;
            String preferred = ArtworkResolver.get(view.getContext())
                    .preferredFast(title, year, rawUrl);
            if (!preferred.equals(rawUrl)) {
                if (fallbackUrl == null) fallbackUrl = rawUrl;
                url = preferred;
            }
        }
        if (fallbackUrl != null && fallbackUrl.equals(url)) fallbackUrl = null;
        int width = Math.max(1, Math.round(dp(view, targetWidthDp)));
        int height = Math.max(1, Math.round(dp(view, targetHeightDp)));
        Drawable placeholder = placeholder(view,
                usePlaceholder ? R.drawable.poster_placeholder : fallback,
                usePlaceholder ? title : null);
        if (fallbackUrl != null) {
            Drawable current = view.getDrawable();
            if (current != null) placeholder = current;
        }
        if (url != null && url.startsWith("//")) url = "https:" + url;
        if (fallbackUrl != null && fallbackUrl.startsWith("//")) {
            fallbackUrl = "https:" + fallbackUrl;
        }
        if (ArtworkResolver.isKnownBrokenArtworkSupplier(url)) {
            Log.w(TAG, "Ignoring known placeholder proxy: " + url);
            url = null;
        }
        if (ArtworkResolver.isKnownBrokenArtworkSupplier(fallbackUrl)) {
            fallbackUrl = null;
        }
        RequestOptions options;
        try {
            options = new RequestOptions()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .fallback(placeholder)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(width, height);
        } catch (Throwable error) {
            options = new RequestOptions()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .override(width, height);
        }
        if (ambient) {
            options = cornerDp > 0
                    ? options.transform(new MultiTransformation<>(
                            new AmbientBackdrop(),
                            new RoundedCorners(Math.round(dp(view, cornerDp)))))
                    : options.transform(new AmbientBackdrop());
        } else if (preservePoster) {
            int accent = ThemeManager.accent(view.getContext());
            if (cornerDp > 0) {
                options = options.transform(new MultiTransformation<>(
                        new PosterFit(enhance, accent),
                        new RoundedCorners(Math.round(dp(view, cornerDp)))));
            } else {
                options = options.transform(new PosterFit(enhance, accent));
            }
        } else if (cornerDp > 0) {
            options = options.transform(new MultiTransformation<>(
                    new CenterCrop(),
                    new RoundedCorners(Math.round(dp(view, cornerDp)))));
        } else {
            options = options.transform(new CenterCrop());
        }
        request(view, url, placeholder, options, fallbackUrl);
    }

    /**
     * Portrait TVBox art is commonly reused as a wide backdrop. Cropping it
     * produces pixelated faces, so wide backgrounds deliberately use a
     * restrained cinematic blur and vignette instead of pretending to be a
     * native film still.
     */
    private static final class AmbientBackdrop
            extends com.bumptech.glide.load.resource.bitmap.BitmapTransformation {
        private static final String ID = "com.ch4nhm.tvapp.ImageLoader.AmbientBackdrop.v4";

        @Override
        protected android.graphics.Bitmap transform(
                com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool pool,
                android.graphics.Bitmap source, int outWidth, int outHeight) {
            android.graphics.Bitmap full = com.bumptech.glide.load.resource.bitmap.TransformationUtils
                    .centerCrop(pool, source, outWidth, outHeight);
            // Drop the source far below display resolution before scaling back
            // up. The result reads as an abstract color field instead of a
            // giant, recognizable face made of blurry source pixels.
            int smallWidth = Math.max(10, Math.min(18, outWidth / 96));
            int smallHeight = Math.max(12,
                    Math.round(smallWidth * (float) outHeight / Math.max(1, outWidth)));
            android.graphics.Bitmap small = pool.get(
                    smallWidth, smallHeight, android.graphics.Bitmap.Config.ARGB_8888);
            if (small == null) small = android.graphics.Bitmap.createBitmap(
                    smallWidth, smallHeight, android.graphics.Bitmap.Config.ARGB_8888);

            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                            | android.graphics.Paint.FILTER_BITMAP_FLAG);
            android.graphics.Canvas smallCanvas = new android.graphics.Canvas(small);
            smallCanvas.drawBitmap(full, null,
                    new android.graphics.Rect(0, 0, smallWidth, smallHeight), paint);

            android.graphics.Bitmap output = pool.get(
                    outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888);
            if (output == null) output = android.graphics.Bitmap.createBitmap(
                    outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(output);
            canvas.drawColor(Color.rgb(8, 10, 14));

            android.graphics.ColorMatrix grade = new android.graphics.ColorMatrix();
            grade.setSaturation(0.86f);
            float contrast = 1.08f;
            float offset = 128f * (1f - contrast);
            grade.postConcat(new android.graphics.ColorMatrix(new float[]{
                    contrast, 0, 0, 0, offset,
                    0, contrast, 0, 0, offset,
                    0, 0, contrast, 0, offset,
                    0, 0, 0, 1, 0
            }));
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(grade));
            canvas.drawBitmap(small, null,
                    new android.graphics.Rect(0, 0, outWidth, outHeight), paint);

            // A faint sharp layer keeps large color fields from looking muddy.
            paint.setColorFilter(null);
            paint.setAlpha(8);
            canvas.drawBitmap(full, 0, 0, paint);

            android.graphics.RadialGradient vignette =
                    new android.graphics.RadialGradient(
                            outWidth * 0.52f, outHeight * 0.38f,
                            Math.max(outWidth, outHeight) * 0.72f,
                            new int[]{Color.TRANSPARENT, Color.argb(30, 0, 0, 0),
                                    Color.argb(150, 0, 0, 0)},
                            new float[]{0f, 0.62f, 1f},
                            android.graphics.Shader.TileMode.CLAMP);
            paint.setShader(vignette);
            paint.setAlpha(255);
            canvas.drawRect(0, 0, outWidth, outHeight, paint);

            pool.put(small);
            if (full != output) pool.put(full);
            return output;
        }

        @Override
        public void updateDiskCacheKey(java.security.MessageDigest messageDigest) {
            messageDigest.update(ID.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof AmbientBackdrop;
        }

        @Override
        public int hashCode() {
            return ID.hashCode();
        }
    }

    private static void request(ImageView view, String url, Drawable placeholder,
                                RequestOptions options, String fallbackUrl) {
        request(view, url, placeholder, options, fallbackUrl, true, true, null);
    }

    private static void request(ImageView view, String url, Drawable placeholder,
                                RequestOptions options, String fallbackUrl,
                                boolean includeHeaders, boolean allowHeaderRetry,
                                Runnable onLoaded) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setImageTintList(null);
        }
        if (url == null || url.isBlank()) {
            if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                request(view, fallbackUrl, placeholder, options, null,
                        includeHeaders, allowHeaderRetry, onLoaded);
                return;
            }
            view.setImageDrawable(placeholder);
            return;
        }
        if (ArtworkResolver.isKnownBrokenArtworkSupplier(url)) {
            Log.w(TAG, "Ignoring known placeholder proxy: " + url);
            if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                request(view, fallbackUrl, placeholder, options, null,
                        includeHeaders, allowHeaderRetry, onLoaded);
            } else {
                view.setImageDrawable(placeholder);
            }
            return;
        }
        Object requestToken = new Object();
        view.setTag(R.id.image_fallback_token, requestToken);
        ImageSource source = ImageSource.parse(url);
        Glide.with(view)
                .load(includeHeaders ? source.model() : source.url)
                .apply(options)
                .listener(new com.bumptech.glide.request.RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(
                            com.bumptech.glide.load.engine.GlideException error,
                            Object model,
                            com.bumptech.glide.request.target.Target<Drawable> target,
                            boolean isFirstResource) {
                        Log.w(TAG, "Image failed: " + model + " / "
                                + (error == null ? "unknown" : error.getMessage()));
                        view.post(() -> {
                            if (view.getTag(R.id.image_fallback_token) != requestToken
                                    || !view.isAttachedToWindow()) {
                                return;
                            }
                            if (includeHeaders && allowHeaderRetry) {
                                request(view, url, placeholder, options, fallbackUrl,
                                        false, false, onLoaded);
                            } else if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                                request(view, fallbackUrl, placeholder, options, null,
                                        true, true, onLoaded);
                            } else {
                                view.setImageDrawable(placeholder);
                            }
                        });
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(
                            Drawable resource,
                            Object model,
                            com.bumptech.glide.request.target.Target<Drawable> target,
                            com.bumptech.glide.load.DataSource dataSource,
                            boolean isFirstResource) {
                        int width = resource.getIntrinsicWidth();
                        int height = resource.getIntrinsicHeight();
                        if (width > 0 && height > 0
                                && width <= MIN_USABLE_IMAGE_SIDE
                                && height <= MIN_USABLE_IMAGE_SIDE) {
                            Log.w(TAG, "Ignoring tiny image " + width + "x" + height
                                    + ": " + model);
                            view.post(() -> {
                                if (view.getTag(R.id.image_fallback_token) != requestToken
                                        || !view.isAttachedToWindow()) {
                                    return;
                                }
                                if (includeHeaders && allowHeaderRetry) {
                                    request(view, url, placeholder, options, fallbackUrl,
                                            false, false, onLoaded);
                                } else if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                                    request(view, fallbackUrl, placeholder, options, null,
                                            true, true, onLoaded);
                                } else {
                                    view.setImageDrawable(placeholder);
                                }
                            });
                            return true;
                        }
                        if (onLoaded != null) view.post(onLoaded);
                        return false;
                    }
                })
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .into(view);
    }

    private static final class ImageSource {
        private final String url;
        private final Map<String, String> headers;

        private ImageSource(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        static ImageSource parse(String raw) {
            String value = raw.trim();
            if (value.contains("&amp;")) {
                value = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            Matcher marker = HEADER_MARKER.matcher(value);
            if (marker.find()) {
                String address = value.substring(0, marker.start()).trim();
                int cursor = marker.start();
                while (cursor < value.length()) {
                    Matcher current = HEADER_MARKER.matcher(value);
                    if (!current.find(cursor) || current.start() != cursor) break;
                    int valueStart = current.end();
                    Matcher next = HEADER_MARKER.matcher(value);
                    int valueEnd = next.find(valueStart) ? next.start() : value.length();
                    String name = canonicalHeader(current.group(1));
                    String headerValue = Uri.decode(value.substring(valueStart, valueEnd).trim());
                    if (!headerValue.isEmpty()) headers.put(name, headerValue);
                    cursor = valueEnd;
                }
                value = address;
            }
            if (value.startsWith("//")) value = "https:" + value;
            value = ArtworkResolver.upgradeUrl(value);
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("doubanio.com/view/photo/")) {
                headers.putIfAbsent("Referer", DEFAULT_REFERER);
            } else if (lower.contains("baidu.com/")) {
                headers.putIfAbsent("Referer", "https://www.baidu.com/");
            } else if (lower.contains("hdslb.com/") || lower.contains("bilibili.com/")) {
                headers.putIfAbsent("Referer", "https://www.bilibili.com/");
            }
            headers.putIfAbsent("User-Agent", DEFAULT_USER_AGENT);
            headers.putIfAbsent("Accept",
                    "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            return new ImageSource(value, headers);
        }

        Object model() {
            if (headers.isEmpty()) return url;
            LazyHeaders.Builder builder = new LazyHeaders.Builder();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.setHeader(header.getKey(), header.getValue());
            }
            return new GlideUrl(url, builder.build());
        }

        private static String canonicalHeader(String value) {
            return "referer".equalsIgnoreCase(value) ? "Referer" : "User-Agent";
        }
    }

    /**
     * Portrait references should stay intact. Wide video stills are fitted
     * without cropping and the unused area is filled with a dim blurred copy
     * instead of an empty black band. Low-resolution artwork gets a restrained
     * grade and unsharp pass so it holds up on large TV posters.
     */
    private static final class PosterFit extends com.bumptech.glide.load.resource.bitmap.BitmapTransformation {
        private static final String ID = "com.ch4nhm.tvapp.ImageLoader.PosterFit.v5";

        private final boolean enhance;
        private final int accent;

        PosterFit(boolean enhance, int accent) {
            this.enhance = enhance;
            this.accent = accent;
        }

        @Override
        protected android.graphics.Bitmap transform(
                com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool pool,
                android.graphics.Bitmap source, int outWidth, int outHeight) {
            float sourceAspect = (float) source.getWidth() / source.getHeight();
            float targetAspect = (float) outWidth / outHeight;
            float aspectDelta = sourceAspect / targetAspect;
            if (aspectDelta >= 0.82f && aspectDelta <= 1.22f) {
                android.graphics.Bitmap fitted = com.bumptech.glide.load.resource.bitmap.TransformationUtils
                        .centerCrop(pool, source, outWidth, outHeight);
                return enhance(pool, fitted, source, outWidth, outHeight);
            }
            android.graphics.Bitmap full = com.bumptech.glide.load.resource.bitmap.TransformationUtils
                    .centerCrop(pool, source, outWidth, outHeight);
            android.graphics.Bitmap output = pool.get(outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888);
            if (output == null) output = android.graphics.Bitmap.createBitmap(outWidth, outHeight,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(output);
            canvas.drawColor(Color.rgb(12, 14, 18));
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            paint.setAlpha(78);
            int blurWidth = Math.max(1, outWidth / 20);
            int blurHeight = Math.max(1, outHeight / 20);
            android.graphics.Bitmap blur = pool.get(
                    blurWidth, blurHeight, android.graphics.Bitmap.Config.ARGB_8888);
            if (blur == null) {
                blur = android.graphics.Bitmap.createBitmap(
                        blurWidth, blurHeight, android.graphics.Bitmap.Config.ARGB_8888);
            }
            android.graphics.Canvas blurCanvas = new android.graphics.Canvas(blur);
            blurCanvas.drawBitmap(full, null,
                    new android.graphics.Rect(0, 0, blurWidth, blurHeight), paint);
            canvas.drawBitmap(blur, null,
                    new android.graphics.Rect(0, 0, outWidth, outHeight), paint);
            pool.put(blur);

            float scale = Math.min((float) outWidth / source.getWidth(),
                    (float) outHeight / source.getHeight());
            float width = source.getWidth() * scale;
            float height = source.getHeight() * scale;
            float left = (outWidth - width) / 2f;
            float top = (outHeight - height) / 2f;
            android.graphics.RectF destination = new android.graphics.RectF(
                    left, top, left + width, top + height);
            paint.setAlpha(255);
            canvas.drawBitmap(source, null, destination, paint);
            return enhance(pool, output, source, outWidth, outHeight);
        }

        private android.graphics.Bitmap enhance(
                com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool pool,
                android.graphics.Bitmap fitted,
                android.graphics.Bitmap source,
                int outWidth, int outHeight) {
            if (!enhance) return fitted;
            float scale = Math.max(
                    (float) outWidth / Math.max(1, source.getWidth()),
                    (float) outHeight / Math.max(1, source.getHeight()));
            if (scale < 1.08f) return fitted;

            android.graphics.Bitmap output = pool.get(
                    outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888);
            if (output == null) output = android.graphics.Bitmap.createBitmap(
                    outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888);

            android.graphics.ColorMatrix saturation = new android.graphics.ColorMatrix();
            saturation.setSaturation(1.06f);
            float contrast = 1.035f;
            float offset = 128f * (1f - contrast);
            saturation.postConcat(new android.graphics.ColorMatrix(new float[]{
                    contrast, 0, 0, 0, offset,
                    0, contrast, 0, 0, offset,
                    0, 0, contrast, 0, offset,
                    0, 0, 0, 1, 0
            }));

            android.graphics.Canvas canvas = new android.graphics.Canvas(output);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                            | android.graphics.Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(saturation));
            canvas.drawBitmap(fitted, 0, 0, paint);
            if (fitted != output) pool.put(fitted);

            return output;
        }

        @Override
        public void updateDiskCacheKey(java.security.MessageDigest messageDigest) {
            messageDigest.update((ID + ":" + enhance + ":" + accent)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof PosterFit fit
                    && fit.enhance == enhance && fit.accent == accent;
        }

        @Override
        public int hashCode() {
            return (ID.hashCode() * 31 + (enhance ? 1 : 0)) * 31 + accent;
        }
    }

    private static Drawable placeholder(View view, int fallback, String title) {
        if (fallback == R.drawable.poster_placeholder && title != null && !title.isBlank()) {
            return new PosterPlaceholderDrawable(title,
                    ThemeManager.accent(view.getContext()));
        }
        try {
            return view.getContext().getDrawable(fallback);
        } catch (Exception ignored) {
            return new ColorDrawable(Color.rgb(21, 26, 33));
        }
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    private static void invalidate(ImageView view) {
        if (view == null) return;
        view.setTag(R.id.image_request_token, null);
        view.setTag(R.id.image_fallback_token, null);
    }
}
