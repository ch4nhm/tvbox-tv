package com.ch4nhm.tvapp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.TvBoxConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
public final class LivePlayerActivity extends BaseActivity {

    private static final String EXTRA_SOURCE_URL = "source_url";
    private static final String EXTRA_CHANNEL_ID = "channel_id";
    private static final String EXTRA_GROUP = "group";
    private static final String EXTRA_QUERY = "query";
    private static final long CONTROLS_TIMEOUT_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<LiveRepository.LiveChannel> navigation = new ArrayList<>();
    private final Set<Integer> attemptedStreams = new HashSet<>();
    private final Runnable hideControlsTask = this::hideControls;
    private AppSettings appSettings;
    private ExoPlayer video;
    private OkHttpClient liveHttpClient;
    private PlayerView playerView;
    private FrameLayout root;
    private FrameLayout controls;
    private LinearLayout statePanel;
    private ProgressBar stateProgress;
    private TextView stateTitle;
    private TextView stateMessage;
    private TextView stateAction;
    private TextView title;
    private TextView channelName;
    private TextView channelMeta;
    private TextView lineAction;
    private View bottomPanel;
    private ImageView play;
    private LiveRepository.LiveSource source;
    private LiveRepository.LiveChannel channel;
    private String sourceUrl = "";
    private String requestedChannelId = "";
    private String groupFilter = "";
    private String queryFilter = "";
    private int streamIndex;
    private boolean prepared;
    private boolean controlsVisible = true;
    private boolean userPaused;
    private boolean resumeAfterPause;
    private boolean stateActionRetries = true;
    private TvLayoutMetrics layoutMetrics;

    public static void start(Context context, String sourceUrl, String channelId,
                             String group, String query) {
        Intent intent = new Intent(context, LivePlayerActivity.class);
        intent.putExtra(EXTRA_SOURCE_URL, sourceUrl);
        intent.putExtra(EXTRA_CHANNEL_ID, channelId);
        intent.putExtra(EXTRA_GROUP, group == null ? "" : group);
        intent.putExtra(EXTRA_QUERY, query == null ? "" : query);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_live_player);
        enterImmersive();
        appSettings = new AppSettings(this);
        sourceUrl = safe(getIntent().getStringExtra(EXTRA_SOURCE_URL));
        requestedChannelId = safe(getIntent().getStringExtra(EXTRA_CHANNEL_ID));
        groupFilter = safe(getIntent().getStringExtra(EXTRA_GROUP));
        queryFilter = safe(getIntent().getStringExtra(EXTRA_QUERY));

        bindViews();
        applyResponsiveChrome();
        buildPlayer();
        bindActions();
        bindPlayerListener();

        LiveRepository.LiveSource cached = LiveRepository.get().getCached(sourceUrl);
        if (cached == null) {
            LiveRepository.LiveSource last = LiveRepository.get().getLastSource();
            if (last != null && last.sourceUrl.equals(sourceUrl)) cached = last;
        }
        if (cached != null) {
            openSource(cached);
        } else {
            loadSource();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void bindViews() {
        root = findViewById(R.id.live_player_root);
        controls = findViewById(R.id.live_player_controls);
        playerView = findViewById(R.id.live_player_video);
        statePanel = findViewById(R.id.live_player_state_panel);
        stateProgress = findViewById(R.id.live_player_state_progress);
        stateTitle = findViewById(R.id.live_player_state_title);
        stateMessage = findViewById(R.id.live_player_state_message);
        stateAction = findViewById(R.id.live_player_state_action);
        title = findViewById(R.id.live_player_title);
        channelName = findViewById(R.id.live_player_channel);
        channelMeta = findViewById(R.id.live_player_meta);
        lineAction = findViewById(R.id.live_player_line);
        bottomPanel = findViewById(R.id.live_player_bottom_panel);
        play = findViewById(R.id.live_player_play);
    }

    private void buildPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(appSettings.isDecoderFallback());
        video = new ExoPlayer.Builder(this, renderersFactory).build();
        liveHttpClient = com.github.catvod.net.OkHttp.client().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
        playerView.setPlayer(video);
        playerView.setResizeMode(resizeMode(appSettings.getVideoScale()));
    }

    private void applyResponsiveChrome() {
        layoutMetrics = TvLayout.metrics(this);
        ViewGroup.LayoutParams bottomParams = bottomPanel.getLayoutParams();
        if (bottomParams != null) {
            bottomParams.width = dp(layoutMetrics.playerPanelWidthDp);
            if (bottomParams instanceof ViewGroup.MarginLayoutParams margins) {
                margins.setMarginStart(0);
                margins.setMarginEnd(0);
                margins.bottomMargin = dp(18);
            }
            bottomPanel.setLayoutParams(bottomParams);
        }

        View previous = findViewById(R.id.live_player_previous);
        View next = findViewById(R.id.live_player_next);
        int control = dp(layoutMetrics.buttonHeightDp);
        resizeControl(previous, control);
        resizeControl(next, control);
        resizeControl(play, control + dp(8));
        resizeHeight(lineAction, control);
        resizeHeight(stateAction, control);
        View controlRow = findViewById(R.id.live_player_control_row);
        if (controlRow != null) {
            ViewGroup.LayoutParams rowParams = controlRow.getLayoutParams();
            if (rowParams != null) {
                rowParams.height = control + dp(18);
                controlRow.setLayoutParams(rowParams);
            }
        }

        ViewGroup.LayoutParams stateParams = statePanel.getLayoutParams();
        if (stateParams != null) {
            stateParams.width = Math.min(dp(600f), dp(layoutMetrics.dialogMaxWidthDp));
            statePanel.setLayoutParams(stateParams);
        }
        channelName.setTextSize(Math.max(24f, layoutMetrics.sectionTitleTextSizeSp + 4f));
        title.setTextSize(Math.max(20f, layoutMetrics.sectionTitleTextSizeSp - 4f));
        stateTitle.setTextSize(Math.max(24f, layoutMetrics.sectionTitleTextSizeSp + 2f));
        stateMessage.setTextSize(Math.max(14f, layoutMetrics.posterTitleTextSizeSp - 1f));
    }

    private static void resizeControl(View view, int heightPx) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.width = heightPx;
        params.height = heightPx;
        view.setLayoutParams(params);
    }

    private static void resizeHeight(View view, int heightPx) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.height = heightPx;
        view.setLayoutParams(params);
    }

    private void bindActions() {
        ImageView previous = findViewById(R.id.live_player_previous);
        ImageView next = findViewById(R.id.live_player_next);
        focusControl(previous, 1.06f);
        focusControl(play, 1.07f);
        focusControl(next, 1.06f);
        focusControl(lineAction, 1.04f);
        focusControl(stateAction, 1.04f);

        previous.setOnClickListener(view -> switchChannel(-1));
        next.setOnClickListener(view -> switchChannel(1));
        play.setOnClickListener(view -> togglePlayback());
        lineAction.setOnClickListener(view -> showLineDialog());
        stateAction.setOnClickListener(view -> {
            if (channel == null) {
                loadSource();
            } else if (stateActionRetries) {
                attemptedStreams.clear();
                playCurrentChannel();
            } else {
                switchChannel(1);
            }
        });
        root.setOnClickListener(view -> {
            if (!controlsVisible) showControls();
        });
        play.post(play::requestFocus);
    }

    private void focusControl(View view, float scale) {
        if (view == null) return;
        FocusAnimator.scale(view, scale, () -> {
            showControls();
            view.post(() -> view.requestRectangleOnScreen(
                    new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        });
    }

    private void bindPlayerListener() {
        video.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    hideState();
                    updatePlayIcon();
                    scheduleControlsHide();
                } else if (state == Player.STATE_BUFFERING && !prepared) {
                    showLoading("正在缓冲直播画面…");
                } else if (state == Player.STATE_ENDED) {
                    prepared = false;
                    updatePlayIcon();
                    showEndedState();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                prepared = false;
                handledPlaybackError(error);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayIcon();
                if (isPlaying) scheduleControlsHide();
                else showControls();
            }
        });
    }

    private void loadSource() {
        TvBoxConfig config = TvBoxRepository.get().getConfig();
        if (config == null) config = new ConfigStore(this).getConfig();
        if (config == null || config.lives == null) {
            showError("直播源已失效，请返回频道列表重新加载。", true);
            return;
        }
        TvBoxConfig.Live live = null;
        for (TvBoxConfig.Live candidate : config.lives) {
            if (candidate != null && sourceUrl.equals(candidate.url)) {
                live = candidate;
                break;
            }
        }
        if (live == null) {
            showError("当前片源配置中找不到这个直播源。", true);
            return;
        }
        showLoading("正在重新连接直播源…");
        TvBoxConfig.Live selected = live;
        LiveRepository.get().load(this, config, selected, new LiveRepository.Callback() {
            @Override
            public void onLoaded(LiveRepository.LiveSource loaded) {
                runOnUiThread(() -> openSource(loaded));
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> showError("直播源加载失败："
                        + rootMessage(error), true));
            }
        });
    }

    private void openSource(LiveRepository.LiveSource loaded) {
        source = loaded;
        title.setText(source.name + " · 直播");
        navigation.clear();
        String query = queryFilter.toLowerCase(Locale.ROOT).trim();
        for (LiveRepository.LiveChannel candidate : source.channels) {
            if (!groupFilter.isBlank() && !groupFilter.equals(candidate.group)) continue;
            if (!query.isEmpty() && !candidate.name.toLowerCase(Locale.ROOT).contains(query)
                    && !candidate.group.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            navigation.add(candidate);
        }
        if (navigation.isEmpty()) navigation.addAll(source.channels);
        LiveRepository.LiveChannel selected = source.findChannel(requestedChannelId);
        if (selected == null || !navigation.contains(selected)) {
            selected = navigation.isEmpty() ? null : navigation.get(0);
        }
        if (selected == null || selected.streams.isEmpty()) {
            showError("没有可播放的直播频道。", true);
            return;
        }
        channel = selected;
        streamIndex = 0;
        attemptedStreams.clear();
        playCurrentChannel();
    }

    private void playCurrentChannel() {
        if (channel == null || channel.streams.isEmpty()) {
            showError("这个频道没有播放地址。", true);
            return;
        }
        streamIndex = Math.max(0, Math.min(channel.streams.size() - 1, streamIndex));
        LiveRepository.LiveStream stream = channel.streams.get(streamIndex);
        attemptedStreams.add(streamIndex);
        prepared = false;
        userPaused = false;
        channelName.setText(channel.name);
        updateChannelMeta();
        updateLineAction();
        showLoading("正在连接 " + channel.name + "…");
        showControls();

        OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(liveHttpClient);
        if (!stream.headers.isEmpty()) {
            factory.setDefaultRequestProperties(stream.headers);
        }
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(factory);
        video.setMediaSource(sourceFactory.createMediaSource(mediaItem(stream.url)));
        video.prepare();
        video.play();
    }

    private static MediaItem mediaItem(String url) {
        MediaItem.Builder item = MediaItem.fromUri(url).buildUpon();
        String path = "";
        try {
            path = safe(Uri.parse(url).getLastPathSegment()).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        String lowerUrl = safe(url).toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || lowerUrl.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (path.endsWith(".flv") || lowerUrl.contains("/huya/")
                || lowerUrl.contains("/douyu/") || lowerUrl.contains("/yy/")
                || lowerUrl.contains("ottiptv.cc/")) {
            // These feeds usually redirect to a live FLV stream and do not
            // expose an extension in the original URL.
            item.setMimeType(MimeTypes.VIDEO_FLV);
        } else if (path.endsWith(".mp4") || path.endsWith(".m4v")
                || path.endsWith(".webm") || path.endsWith(".mkv")
                || path.endsWith(".ts") || path.endsWith(".mp3")
                || path.endsWith(".aac")) {
            // The URL extension is enough for Media3 to select the
            // progressive extractor.
        } else {
            // Many IPTV endpoints hide an HLS manifest behind a query-style
            // URL (for example live.php?id=CCTV1).
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return item.build();
    }

    private void updateChannelMeta() {
        if (channel == null || channel.streams.isEmpty()) return;
        int position = navigation.indexOf(channel);
        String place = position >= 0
                ? String.format(Locale.getDefault(), " · %d/%d", position + 1, navigation.size())
                : "";
        LiveRepository.LiveStream stream = channel.streams.get(streamIndex);
        channelMeta.setText(channel.group + " · " + stream.label + place + " · 直播");
    }

    private void updateLineAction() {
        if (channel == null) return;
        boolean multiple = channel.streams.size() > 1;
        lineAction.setVisibility(multiple ? View.VISIBLE : View.GONE);
        lineAction.setEnabled(multiple);
        lineAction.setFocusable(multiple);
        lineAction.setAlpha(1f);
        lineAction.setText("线路 " + (streamIndex + 1) + " / " + channel.streams.size());
    }

    private void togglePlayback() {
        if (!prepared) {
            if (statePanel.getVisibility() == View.VISIBLE && stateActionRetries) {
                attemptedStreams.clear();
                playCurrentChannel();
            }
            return;
        }
        if (video.isPlaying()) {
            userPaused = true;
            video.pause();
        } else {
            userPaused = false;
            video.play();
        }
        showControls();
    }

    private void switchChannel(int direction) {
        if (navigation.isEmpty()) {
            Toast.makeText(this, "当前没有可切换的频道", Toast.LENGTH_SHORT).show();
            return;
        }
        int current = channel == null ? -1 : navigation.indexOf(channel);
        int target = current < 0 ? 0 : Math.floorMod(current + direction, navigation.size());
        if (target == current) {
            Toast.makeText(this, "当前范围内只有一个频道", Toast.LENGTH_SHORT).show();
            return;
        }
        channel = navigation.get(target);
        requestedChannelId = channel.id;
        streamIndex = 0;
        attemptedStreams.clear();
        playCurrentChannel();
        play.requestFocus();
    }

    private void handledPlaybackError(PlaybackException error) {
        int next = nextUntriedStream();
        if (next >= 0) {
            streamIndex = next;
            playCurrentChannel();
            return;
        }
        showError(friendlyError(error.getMessage()), true);
    }

    private int nextUntriedStream() {
        if (channel == null) return -1;
        for (int index = streamIndex + 1; index < channel.streams.size(); index++) {
            if (!attemptedStreams.contains(index)) return index;
        }
        for (int index = 0; index < streamIndex; index++) {
            if (!attemptedStreams.contains(index)) return index;
        }
        return -1;
    }

    private void showLineDialog() {
        if (channel == null || channel.streams.size() <= 1) {
            Toast.makeText(this, "当前频道只有一条线路", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(18), dp(14), dp(18), dp(6));
        TextView selectedView = null;
        final AlertDialog[] dialogReference = new AlertDialog[1];
        for (int index = 0; index < channel.streams.size(); index++) {
            LiveRepository.LiveStream stream = channel.streams.get(index);
            TextView item = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            params.bottomMargin = dp(4);
            item.setLayoutParams(params);
            String prefix = index == streamIndex ? "✓  " : "";
            item.setText(prefix + stream.label);
            item.setTextColor(getColorStateList(R.color.filter_option_text));
            item.setTextSize(17);
            item.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(22), 0, dp(22), 0);
            item.setBackgroundResource(R.drawable.bg_filter_option);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setSelected(index == streamIndex);
            int selected = index;
            item.setOnClickListener(view -> {
                streamIndex = selected;
                attemptedStreams.clear();
                if (dialogReference[0] != null) dialogReference[0].dismiss();
                playCurrentChannel();
                view.postDelayed(() -> {
                    if (getCurrentFocus() == null) play.requestFocus();
                }, 80L);
            });
            item.setOnFocusChangeListener((view, focused) -> {
                if (focused) view.post(() -> view.requestRectangleOnScreen(
                        new Rect(0, 0, view.getWidth(), view.getHeight()), true));
            });
            options.addView(item);
            if (index == streamIndex) selectedView = item;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(options);
        int height = Math.min(dp(330), dp(14) + channel.streams.size() * dp(60));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择播放线路")
                .setView(wrapper)
                .setNegativeButton("关闭", null)
                .create();
        dialogReference[0] = dialog;
        TextView initial = selectedView;
        dialog.setOnShowListener(ignored -> {
            DialogStyler.style(dialog);
            if (initial != null) initial.requestFocus();
        });
        dialog.show();
    }

    private void showLoading(String detail) {
        handler.removeCallbacks(hideControlsTask);
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(1f);
        controlsVisible = true;
        bottomPanel.setVisibility(View.GONE);
        stateActionRetries = true;
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.VISIBLE);
        stateTitle.setText("正在连接直播");
        stateMessage.setText(detail);
        stateAction.setVisibility(View.GONE);
    }

    private void hideState() {
        statePanel.setVisibility(View.GONE);
        stateAction.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.VISIBLE);
    }

    private void showError(String detail, boolean retry) {
        prepared = false;
        stateActionRetries = retry;
        showControls();
        bottomPanel.setVisibility(View.GONE);
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateTitle.setText("直播信号暂时不可用");
        stateMessage.setText(detail);
        stateAction.setText(retry ? "重试" : "下一个频道");
        stateAction.setVisibility(View.VISIBLE);
        stateAction.post(stateAction::requestFocus);
    }

    private void showEndedState() {
        showControls();
        stateActionRetries = false;
        bottomPanel.setVisibility(View.GONE);
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateTitle.setText("播放已结束");
        stateMessage.setText("这个频道当前返回的是点播内容。可以切换到下一个频道。");
        stateAction.setText("下一个频道");
        stateAction.setVisibility(View.VISIBLE);
        stateAction.post(stateAction::requestFocus);
    }

    private void updatePlayIcon() {
        if (play == null || video == null) return;
        play.setImageResource(video.isPlaying()
                ? R.drawable.ic_pause_light : R.drawable.ic_play_light);
        play.setContentDescription(video.isPlaying() ? "暂停直播" : "继续直播");
    }

    private void showControls() {
        handler.removeCallbacks(hideControlsTask);
        if (!controlsVisible) {
            controlsVisible = true;
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(1f).setDuration(180).start();
        }
        if (prepared && video != null && video.isPlaying()
                && statePanel.getVisibility() != View.VISIBLE) {
            scheduleControlsHide();
        }
    }

    private void scheduleControlsHide() {
        handler.removeCallbacks(hideControlsTask);
        if (!prepared || video == null || !video.isPlaying()
                || userPaused || statePanel.getVisibility() == View.VISIBLE) {
            return;
        }
        handler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS);
    }

    private void hideControls() {
        if (!prepared || video == null || !video.isPlaying()
                || statePanel.getVisibility() == View.VISIBLE) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused != root && focused != playerView) {
            root.requestFocus();
        }
        controls.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (!isFinishing() && controls.getAlpha() == 0f) {
                controls.setVisibility(View.GONE);
                controlsVisible = false;
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            switchChannel(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            switchChannel(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            togglePlayback();
            return true;
        }
        if (statePanel.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchChannel(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchChannel(1);
                return true;
            }
        }
        if (!controlsVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchChannel(-1);
                showControls();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchChannel(1);
                showControls();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_MENU) {
                showControls();
                play.requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        if (video != null && video.isPlaying()) {
            resumeAfterPause = true;
            video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeAfterPause && prepared && video != null && !userPaused) {
            video.play();
        }
        resumeAfterPause = false;
        showControls();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (video != null) video.release();
        super.onDestroy();
    }

    private static int resizeMode(int scale) {
        return switch (scale) {
            case AppSettings.VIDEO_SCALE_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            case AppSettings.VIDEO_SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL;
            default -> AspectRatioFrameLayout.RESIZE_MODE_FIT;
        };
    }

    private static String friendlyError(String message) {
        String value = safe(message).toLowerCase(Locale.ROOT);
        if (value.contains("timeout") || value.contains("connect")
                || value.contains("network") || value.contains("host")) {
            return "直播线路连接超时，请重试或切换线路。";
        }
        if (value.contains("404") || value.contains("403")
                || value.contains("response code")) {
            return "直播地址已失效，请切换线路或返回选择其他频道。";
        }
        if (value.contains("codec") || value.contains("decoder")
                || value.contains("unsupported")) {
            return "当前设备无法解码这个直播流，请切换线路或频道。";
        }
        return "直播信号暂时无法播放，请重试或切换频道。";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "未知错误"
                : String.valueOf(current.getMessage() == null ? current : current.getMessage());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }
}
