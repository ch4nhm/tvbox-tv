package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends BaseActivity {

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SITE = "site";
    private static final String EXTRA_EPISODE_NAME = "episode_name";
    private static final String EXTRA_EPISODE_ID = "episode_id";
    private static final String EXTRA_FLAG = "flag";
    private static final String EXTRA_ITEM_ID = "item_id";
    private static final String EXTRA_ITEM_META = "item_meta";
    private static final String EXTRA_ITEM_PIC = "item_pic";
    private static final String EXTRA_ITEM_REMARK = "item_remark";
    private static final String EXTRA_START_MS = "start_ms";
    private static final long HISTORY_INTERVAL_MS = 10_000L;
    private static final long CONTROLS_TIMEOUT_MS = 4_500L;
    private static final long LONG_SEEK_INTERVAL_MS = 220L;
    private static final long LONG_SEEK_STEP_MS = 10_000L;
    private static final long TICK_VISIBLE_MS = 500L;
    private static final long TICK_HIDDEN_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsTask = this::hideControls;
    private AppSettings appSettings;
    private ExoPlayer video;
    private PlayerView playerView;
    private FrameLayout root;
    private FrameLayout controls;
    private View topPanel;
    private View bottomPanel;
    private SeekBar seek;
    private TextView titleView;
    private TextView episodeView;
    private TextView time;
    private TextView duration;
    private ImageView play;
    private ImageView previous;
    private ImageView next;
    private LinearLayout statePanel;
    private ProgressBar stateProgress;
    private TextView stateTitle;
    private TextView stateMessage;
    private TextView stateAction;
    private boolean prepared;
    private boolean resumeOnResume;
    private ContentItem historyItem;
    private String episodeName;
    private String episodeId;
    private String episodeFlag;
    private long pendingStartMs;
    private long lastHistorySaveMs;
    private boolean resumeApplied;
    private boolean stateNeedsPanLogin;
    private String statePanBrand = "";
    private boolean stateNeedsReplay;
    private boolean stateNeedsSourceRetry;
    private boolean switchingEpisode;
    private boolean endedHandled;
    private boolean focusPlayWhenReady;
    private long seekStepMs;
    private TvBoxRepository.Playback currentPlayback;
    private boolean softwareDecoderActive;
    private boolean softwareFallbackAttempted;
    private PanRepository panRepository;
    private PanLoginDialog panLoginDialog;
    private boolean controlsVisible = true;
    private boolean autoPanPromptShown;
    private int playbackRequestId;
    private long lastLongSeekAt;
    private long backCallbackIgnoreUntil;
    private boolean confirmHandledDown;
    private OkHttpDataSource.Factory playbackDataSourceFactory;
    private DefaultMediaSourceFactory playbackSourceFactory;
    private DefaultHttpDataSource.Factory localDataSourceFactory;
    private Future<?> webParseFuture;
    private DefaultMediaSourceFactory localSourceFactory;
    private TvLayoutMetrics layoutMetrics;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!prepared) return;
            if (controlsVisible) updatePlaybackProgress();
            if (video.isPlaying()) saveHistory(false);
            handler.postDelayed(this, controlsVisible ? TICK_VISIBLE_MS : TICK_HIDDEN_MS);
        }
    };

    public static void start(Context context, ContentItem item, TvBoxRepository.Episode episode, String title) {
        start(context, item, episode, title, 0);
    }

    public static void start(Context context, ContentItem item, TvBoxRepository.Episode episode,
                             String title, long startMs) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_EPISODE_NAME, EpisodeAdapter.displayName(episode.name));
        intent.putExtra(EXTRA_EPISODE_ID, episode.id);
        intent.putExtra(EXTRA_FLAG, episode.flag);
        intent.putExtra(EXTRA_START_MS, Math.max(0, startMs));
        intent.putExtra(EXTRA_ITEM_ID, item.id);
        intent.putExtra(EXTRA_ITEM_META, item.meta);
        intent.putExtra(EXTRA_ITEM_PIC, item.pic);
        intent.putExtra(EXTRA_ITEM_REMARK, item.remark);
        if (item.site != null) intent.putExtra(EXTRA_SITE, item.site.key);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        enterImmersive();
        titleView = findViewById(R.id.player_title);
        episodeView = findViewById(R.id.player_episode);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String episode = getIntent().getStringExtra(EXTRA_EPISODE_NAME);
        if (title == null) title = "长月烬明";
        if (episode == null) episode = "第1集";
        episodeName = episode;
        episodeId = getIntent().getStringExtra(EXTRA_EPISODE_ID);
        episodeFlag = getIntent().getStringExtra(EXTRA_FLAG);
        pendingStartMs = Math.max(0, getIntent().getLongExtra(EXTRA_START_MS, 0));
        String siteKey = getIntent().getStringExtra(EXTRA_SITE);
        String itemId = getIntent().getStringExtra(EXTRA_ITEM_ID);
        if (itemId != null && !itemId.isBlank()) {
            historyItem = new ContentItem(itemId, title,
                    safe(getIntent().getStringExtra(EXTRA_ITEM_META)),
                    safe(getIntent().getStringExtra(EXTRA_ITEM_PIC)),
                    safe(getIntent().getStringExtra(EXTRA_ITEM_REMARK)),
                    findSite(siteKey));
        }

        titleView.setText(title);
        episodeView.setText(episode);

        appSettings = new AppSettings(this);
        seekStepMs = appSettings.getSeekStepSeconds() * 1_000L;
        playbackDataSourceFactory = new OkHttpDataSource.Factory(
                com.github.catvod.net.OkHttp.player());
        playbackSourceFactory = new DefaultMediaSourceFactory(
                new DefaultDataSource.Factory(this, playbackDataSourceFactory));
        localDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true);
        localSourceFactory = new DefaultMediaSourceFactory(localDataSourceFactory);
        root = findViewById(R.id.player_root);
        controls = findViewById(R.id.player_controls);
        topPanel = findViewById(R.id.player_top_panel);
        bottomPanel = findViewById(R.id.player_bottom_panel);
        playerView = findViewById(R.id.player_video);
        video = buildPlayer(false);
        playerView.setPlayer(video);
        playerView.setResizeMode(resizeMode(appSettings.getVideoScale()));
        seek = findViewById(R.id.player_seek);
        time = findViewById(R.id.player_time);
        duration = findViewById(R.id.player_duration);
        play = findViewById(R.id.player_play);
        previous = findViewById(R.id.player_previous);
        next = findViewById(R.id.player_next);
        statePanel = findViewById(R.id.player_state_panel);
        stateProgress = findViewById(R.id.player_state_progress);
        stateTitle = findViewById(R.id.player_state_title);
        stateMessage = findViewById(R.id.player_state_message);
        stateAction = findViewById(R.id.player_state_action);
        applyResponsiveChrome();

        root.setOnClickListener(view -> {
            if (!controlsVisible) showControls();
        });
        FocusAnimator.scale(play, 1.07f, this::showControls);
        FocusAnimator.scale(previous, 1.06f, this::showControls);
        FocusAnimator.scale(next, 1.06f, this::showControls);
        FocusAnimator.scale(stateAction, 1.04f);
        seek.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) showControls();
        });

        play.setOnClickListener(view -> togglePlayback());
        previous.setOnClickListener(view -> playNeighbor(-1, false));
        next.setOnClickListener(view -> playNeighbor(1, false));
        stateAction.setOnClickListener(view -> {
            if (stateNeedsSourceRetry) {
                retryCurrentEpisode();
            } else if (stateNeedsPanLogin) {
                showPanLogin();
            } else if (stateNeedsReplay) {
                replay();
            } else {
                finish();
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    video.seekTo(progress);
                    time.setText(format(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                video.seekTo(seekBar.getProgress());
                if (!video.isPlaying()) video.play();
                showControls();
                scheduleControlsHide();
            }
        });
        seek.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                seekBy(-seekStepMs);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekBy(seekStepMs);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                togglePlayback();
                return true;
            }
            return false;
        });

        attachPlayerListener(video);

        if (episodeId == null || episodeId.isBlank()) {
            episodeView.setText(episode);
            showPlaybackError("当前剧集没有播放地址，请返回详情选择其他剧集或线路。", false);
        } else {
            playEpisode(siteKey, episodeFlag, episodeId);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                long now = SystemClock.elapsedRealtime();
                if (controlsVisible) {
                    hideControlsNow();
                    backCallbackIgnoreUntil = now + 300L;
                    return;
                }
                if (now < backCallbackIgnoreUntil) return;
                finish();
            }
        });

        requestInitialFocus(play);
    }

    private void applyResponsiveChrome() {
        layoutMetrics = TvLayout.metrics(this);
        int panelWidth = dp(layoutMetrics.playerPanelWidthDp);
        ViewGroup.LayoutParams panelParams = bottomPanel.getLayoutParams();
        if (panelParams != null) {
            panelParams.width = panelWidth;
            if (panelParams instanceof ViewGroup.MarginLayoutParams margins) {
                margins.bottomMargin = dp(14);
                margins.setMarginStart(0);
                margins.setMarginEnd(0);
            }
            bottomPanel.setLayoutParams(panelParams);
        }

        ViewGroup.LayoutParams topParams = topPanel.getLayoutParams();
        if (topParams != null) {
            topParams.height = dp(layoutMetrics.buttonHeightDp + 24f);
            if (topParams instanceof ViewGroup.MarginLayoutParams margins) {
                margins.setMarginStart(dp(layoutMetrics.contentMarginDp));
                margins.setMarginEnd(dp(layoutMetrics.contentMarginDp));
            }
            topPanel.setLayoutParams(topParams);
        }

        int controlHeight = dp(layoutMetrics.buttonHeightDp);
        resizeControl(previous, controlHeight);
        resizeControl(next, controlHeight);
        resizeControl(play, controlHeight + dp(8));
        View controlRow = findViewById(R.id.player_control_row);
        if (controlRow != null) {
            ViewGroup.LayoutParams rowParams = controlRow.getLayoutParams();
            if (rowParams != null) {
                rowParams.height = controlHeight + dp(18);
                controlRow.setLayoutParams(rowParams);
            }
        }
        resizeHeight(stateAction, dp(layoutMetrics.buttonHeightDp));
        ViewGroup.LayoutParams stateParams = statePanel.getLayoutParams();
        if (stateParams != null) {
            stateParams.width = Math.min(dp(600f), dp(layoutMetrics.dialogMaxWidthDp));
            statePanel.setLayoutParams(stateParams);
        }
        titleView.setTextSize(Math.max(20f, layoutMetrics.sectionTitleTextSizeSp - 4f));
        episodeView.setTextSize(Math.max(14f, layoutMetrics.posterTitleTextSizeSp - 2f));
        time.setTextSize(Math.max(13f, layoutMetrics.posterTitleTextSizeSp - 3f));
        duration.setTextSize(Math.max(13f, layoutMetrics.posterTitleTextSizeSp - 3f));
        stateTitle.setTextSize(Math.max(24f, layoutMetrics.sectionTitleTextSizeSp + 2f));
        stateMessage.setTextSize(Math.max(14f, layoutMetrics.posterTitleTextSizeSp - 1f));
        int timeWidth = dp(Math.max(64f, layoutMetrics.buttonHeightDp * 1.15f));
        resizeWidth(time, timeWidth);
        resizeWidth(duration, timeWidth);
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

    private static void resizeWidth(View view, int widthPx) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.width = widthPx;
        view.setLayoutParams(params);
    }

    private ExoPlayer buildPlayer(boolean preferSoftwareDecoder) {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(appSettings.isDecoderFallback());
        if (preferSoftwareDecoder) {
            renderersFactory.setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE);
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        return new ExoPlayer.Builder(this, renderersFactory)
                .setLoadControl(loadControl)
                .build();
    }

    private void attachPlayerListener(ExoPlayer player) {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (player != video) return;
                if (state == Player.STATE_READY) {
                    prepared = true;
                    switchingEpisode = false;
                    endedHandled = false;
                    hideStatePanel();
                    seek.setMax((int) Math.max(0, player.getDuration()));
                    duration.setText(format((int) player.getDuration()));
                    if (!resumeApplied) {
                        long startPosition = pendingStartMs;
                        if (startPosition <= 0 && appSettings.isResumePlayback()
                                && historyItem != null) {
                            startPosition = UserDataStore.get(PlayerActivity.this)
                                    .resumePosition(historyItem, episodeId);
                        }
                        long total = player.getDuration();
                        if (total > 0 && startPosition > 0) {
                            long endGuard = Math.max(0, total - 10_000L);
                            player.seekTo(Math.min(startPosition, endGuard));
                        }
                        resumeApplied = true;
                    }
                    play.setImageResource(player.isPlaying() ? R.drawable.ic_pause_light : R.drawable.ic_play_light);
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                    saveHistory(true);
                    showControls();
                    scheduleControlsHide();
                } else if (state == Player.STATE_ENDED) {
                    saveHistory(true);
                    prepared = false;
                    play.setImageResource(R.drawable.ic_play_light);
                    if (!endedHandled) {
                        endedHandled = true;
                        if (appSettings.isAutoNextEpisode()) playNeighbor(1, true);
                        else showEndedState();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (player == video) handlePlayerError(player, error);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (player == video && prepared) {
                    play.setImageResource(isPlaying ? R.drawable.ic_pause_light : R.drawable.ic_play_light);
                    if (isPlaying) scheduleControlsHide();
                    else showControls();
                }
            }
        });
    }

    private void handlePlayerError(ExoPlayer player, PlaybackException error) {
        prepared = false;
        switchingEpisode = false;
        if (appSettings.isDecoderFallback()
                && !softwareFallbackAttempted
                && !softwareDecoderActive
                && currentPlayback != null
                && isDecoderError(error)) {
            softwareFallbackAttempted = true;
            long position = Math.max(0, player.getCurrentPosition());
            showParsingState("正在切换兼容解码", "当前解码器不可用，正在使用软解重试…");
            handler.post(() -> retryWithSoftwareDecoder(player, position));
            return;
        }
        showPlaybackError(friendlyPlayerError(error.getMessage()), false);
    }

    private void retryWithSoftwareDecoder(ExoPlayer failedPlayer, long position) {
        if (isFinishing() || isDestroyed() || failedPlayer != video || currentPlayback == null) {
            return;
        }
        android.util.Log.i("TVBoxPlayer", "Retrying playback with software MediaCodec");
        handler.removeCallbacks(tick);
        softwareDecoderActive = true;
        ExoPlayer previous = video;
        video = buildPlayer(true);
        playerView.setPlayer(video);
        attachPlayerListener(video);
        previous.release();

        resumeApplied = position > 0;
        showParsingState("正在使用兼容解码", "已切换到软解，正在重新连接画面…");
        startMediaSource(currentPlayback, position);
    }

    private static boolean isDecoderError(PlaybackException error) {
        if (error == null) return false;
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return true;
            default:
                break;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getName().toLowerCase(Locale.ROOT);
            String message = cause.getMessage() == null
                    ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (name.contains("mediacodec")
                    || name.contains("decoder")
                    || message.contains("mediacodec")
                    || message.contains("decoder")
                    || message.contains("queueinputbuffer")) {
                return true;
            }
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private void togglePlayback() {
        if (!prepared) return;
        if (video.isPlaying()) video.pause();
        else video.play();
        showControls();
    }

    private void seekBy(long deltaMs) {
        if (!prepared) return;
        int target = (int) Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + deltaMs));
        video.seekTo(target);
        if (!video.isPlaying()) video.play();
        seek.setProgress(target);
        time.setText(format(target));
        showControls();
        scheduleControlsHide();
    }

    private void replay() {
        if (episodeId == null || episodeId.isBlank()) return;
        stateNeedsReplay = false;
        stateNeedsSourceRetry = false;
        endedHandled = false;
        resumeApplied = true;
        prepared = false;
        showParsingState("正在重播", "正在回到视频开头…");
        video.seekTo(0);
        video.play();
    }

    private void playNeighbor(int direction, boolean completed) {
        if (switchingEpisode) return;
        if (historyItem == null) {
            if (completed) showEndedState();
            Toast.makeText(this, "当前内容没有可切换的选集", Toast.LENGTH_SHORT).show();
            return;
        }
        saveHistory(true);
        switchingEpisode = true;
        TvBoxRepository.get().detail(historyItem, new TvBoxRepository.DetailCallback() {
            @Override
            public void onDetail(TvBoxRepository.Detail detail) {
                runOnUiThread(() -> selectNeighbor(detail, direction, completed));
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    switchingEpisode = false;
                    if (completed) showEndedState();
                    Toast.makeText(PlayerActivity.this,
                            "无法切换选集：" + readablePlaybackMessage(error.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void selectNeighbor(TvBoxRepository.Detail detail, int direction, boolean completed) {
        switchingEpisode = false;
        TvBoxRepository.PlayGroup group = null;
        int current = -1;
        for (TvBoxRepository.PlayGroup candidate : detail.episodes) {
            int index = indexOf(candidate, episodeId);
            if (index >= 0 && (group == null
                    || java.util.Objects.equals(candidate.flag, episodeFlag))) {
                group = candidate;
                current = index;
                if (java.util.Objects.equals(candidate.flag, episodeFlag)) break;
            }
        }
        int targetIndex = current + direction;
        if (group == null || targetIndex < 0 || targetIndex >= group.episodes.size()) {
            if (completed) {
                showEndedState();
                Toast.makeText(this, direction > 0 ? "已经是最后一集" : "已经是第一集",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, direction > 0 ? "没有下一集" : "没有上一集",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        switchEpisode(group.episodes.get(targetIndex));
    }

    private void switchEpisode(TvBoxRepository.Episode episode) {
        episodeName = EpisodeAdapter.displayName(episode.name);
        episodeId = episode.id;
        episodeFlag = episode.flag;
        pendingStartMs = 0;
        resumeApplied = false;
        prepared = false;
        endedHandled = false;
        autoPanPromptShown = false;
        episodeView.setText(episodeName);
        titleView.setText(getIntent().getStringExtra(EXTRA_TITLE));
        video.stop();
        video.clearMediaItems();
        String siteKey = historyItem != null && historyItem.site != null
                ? historyItem.site.key : getIntent().getStringExtra(EXTRA_SITE);
        playEpisode(siteKey, episodeFlag, episodeId);
    }

    private static int indexOf(TvBoxRepository.PlayGroup group, String id) {
        if (group == null || id == null) return -1;
        for (int index = 0; index < group.episodes.size(); index++) {
            if (id.equals(group.episodes.get(index).id)) return index;
        }
        return -1;
    }

    private static String format(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d",
                totalSeconds / 60, totalSeconds % 60);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNeighbor(1, false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            playNeighbor(-1, false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlayback();
            return true;
        }
        boolean controlsKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        if (controlsKey && !controlsVisible
                && statePanel.getVisibility() != View.VISIBLE
                && panLoginDialog == null) {
            showControls();
            if (prepared && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        ? -seekStepMs : seekStepMs);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
        if (confirm) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && prepared
                    && statePanel.getVisibility() != View.VISIBLE
                    && panLoginDialog == null
                    && (!controlsVisible || !controls.hasFocus())) {
                confirmHandledDown = true;
                togglePlayback();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && confirmHandledDown) {
                confirmHandledDown = false;
                return true;
            }
        }
        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (horizontal && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() > 0 && prepared && video != null
                && statePanel.getVisibility() != View.VISIBLE
                && panLoginDialog == null) {
            long now = SystemClock.elapsedRealtime();
            if (lastLongSeekAt == 0 || now - lastLongSeekAt >= LONG_SEEK_INTERVAL_MS) {
                lastLongSeekAt = now;
                seekBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        ? -LONG_SEEK_STEP_MS : LONG_SEEK_STEP_MS);
            }
            return true;
        }
        if (horizontal && event.getAction() == KeyEvent.ACTION_UP && lastLongSeekAt > 0) {
            lastLongSeekAt = 0;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (controlsVisible && statePanel.getVisibility() != View.VISIBLE
                && panLoginDialog == null) {
            scheduleControlsHide();
        }
    }

    @Override
    protected void onPause() {
        saveHistory(true);
        handler.removeCallbacks(hideControlsTask);
        if (video != null && video.isPlaying()) {
            resumeOnResume = true;
            video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeOnResume && prepared) {
            video.play();
            resumeOnResume = false;
        }
        showControls();
        scheduleControlsHide();
    }

    @Override
    protected void onDestroy() {
        saveHistory(true);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideControlsTask);
        cancelWebParse();
        if (video != null) video.release();
        super.onDestroy();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void playEpisode(String siteKey, String flag, String id) {
        final int requestId = ++playbackRequestId;
        cancelWebParse();
        com.ch4nhm.tvapp.data.SiteConfig site = findSite(siteKey);
        if (isDirectUrl(id)) {
            showParsingState("正在连接视频", "正在准备直链播放…");
            episodeView.setText(episodeName);
            startPlayback(new TvBoxRepository.Playback(
                    id, java.util.Collections.emptyMap(), ""));
            return;
        }
        if (site == null) {
            switchingEpisode = false;
            showPlaybackError("站点已失效，请返回详情重新选择内容。", false);
            Toast.makeText(this, "站点已失效", Toast.LENGTH_LONG).show();
            return;
        }
        if (quarkEpisodeNeedsLogin() && !PanCredentialStore.hasQuarkCookie()) {
            switchingEpisode = false;
            episodeView.setText(episodeName);
            showPlaybackError("当前线路来自夸克网盘，登录后即可继续播放。", true);
            return;
        }
        showParsingState();
        episodeView.setText(episodeName);
        ContentItem item = new ContentItem("", "", "", "", "", site);
        TvBoxRepository.get().player(item, new TvBoxRepository.Episode("", id, flag), new TvBoxRepository.PlayerCallback() {
            @Override
            public void onPlayer(TvBoxRepository.Playback playback) {
                runOnUiThread(() -> {
                    if (!isCurrentPlaybackRequest(requestId)) return;
                    startPlayback(playback);
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (!isCurrentPlaybackRequest(requestId)) return;
                    String message = readablePlaybackMessage(error.getMessage());
                    switchingEpisode = false;
                    episodeView.setText(episodeName);
                    showPlaybackError("获取播放地址失败：" + message, needsPanLogin(message));
                    Toast.makeText(PlayerActivity.this, "获取播放地址失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isCurrentPlaybackRequest(int requestId) {
        return requestId == playbackRequestId && !isFinishing() && !isDestroyed();
    }

    private void startPlayback(TvBoxRepository.Playback playback) {
        playback = normalizePlayback(playback);
        if (playback.url.isEmpty()) {
            currentPlayback = null;
            String message = readablePlaybackMessage(playback.message);
            switchingEpisode = false;
            boolean needsLogin = needsPanLogin(message)
                    || (message.isBlank() && panEpisodeNeedsLogin());
            episodeView.setText(episodeName);
            showPlaybackError(message, needsLogin);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (WebPlaybackResolver.shouldResolve(playback)) {
            resolveWebPlayback(playback);
            return;
        }
        currentPlayback = playback;
        showParsingState("正在连接视频", "播放地址已获取，正在准备画面…");
        episodeView.setText(episodeName);
        startMediaSource(playback, 0);
    }

    private void resolveWebPlayback(TvBoxRepository.Playback playback) {
        final int requestId = playbackRequestId;
        showParsingState("正在解析网页线路", "正在从网页线路获取真实播放地址…");
        episodeView.setText(episodeName);
        cancelWebParse();
        webParseFuture = WebPlaybackResolver.resolve(playback).whenComplete((resolved, error) ->
                runOnUiThread(() -> {
                    if (!isCurrentPlaybackRequest(requestId)) return;
                    webParseFuture = null;
                    if (error != null) {
                        switchingEpisode = false;
                        String message = readablePlaybackMessage(rootMessage(error));
                        showPlaybackError("网页线路解析失败：" + message, false);
                        Toast.makeText(PlayerActivity.this,
                                "网页线路解析失败：" + message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    currentPlayback = resolved;
                    showParsingState("正在连接视频", "播放地址已解析，正在准备画面…");
                    startMediaSource(resolved, 0);
                }));
    }

    private void cancelWebParse() {
        Future<?> current = webParseFuture;
        webParseFuture = null;
        if (current != null) current.cancel(true);
    }

    private void startMediaSource(TvBoxRepository.Playback playback, long startPosition) {
        MediaItem mediaItem = mediaItemFor(playback.url);
        if (isLocalProxyUrl(playback.url)) {
            localDataSourceFactory.setDefaultRequestProperties(playback.headers);
            video.setMediaSource(localSourceFactory.createMediaSource(mediaItem));
        } else {
            playbackDataSourceFactory.setDefaultRequestProperties(playback.headers);
            video.setMediaSource(playbackSourceFactory.createMediaSource(mediaItem));
        }
        video.prepare();
        if (startPosition > 0) video.seekTo(startPosition);
        video.play();
    }

    private static MediaItem mediaItemFor(String url) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(url);
        String type = "";
        try {
            type = Uri.parse(url).getQueryParameter("type");
        } catch (Throwable ignored) {
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String path = "";
        try {
            path = Uri.parse(url).getPath();
        } catch (Throwable ignored) {
        }
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if ("mpd".equalsIgnoreCase(type) || path.endsWith(".mpd")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        } else if ("m3u8".equalsIgnoreCase(type)
                || "hls".equalsIgnoreCase(type)
                || path.endsWith(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) {
            builder.setMimeType(MimeTypes.APPLICATION_RTSP);
        }
        return builder.build();
    }

    private static boolean isLocalProxyUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showControls() {
        handler.removeCallbacks(hideControlsTask);
        if (statePanel.getVisibility() == View.VISIBLE || panLoginDialog != null) {
            hideControlsNow();
            return;
        }
        updatePlaybackProgress();
        boolean wasHidden = !controlsVisible;
        controlsVisible = true;
        controls.animate().cancel();
        topPanel.animate().cancel();
        bottomPanel.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(wasHidden ? 0f : 1f);
        topPanel.setTranslationY(wasHidden ? -dp(10) : 0f);
        bottomPanel.setTranslationY(wasHidden ? dp(12) : 0f);
        controls.animate()
                .alpha(1f)
                .setDuration(180L)
                .start();
        topPanel.animate()
                .translationY(0f)
                .setDuration(220L)
                .start();
        bottomPanel.animate()
                .translationY(0f)
                .setDuration(220L)
                .start();
        if (!controls.hasFocus()) play.requestFocus();
        scheduleControlsHide();
    }

    private void updatePlaybackProgress() {
        if (!prepared || video == null || seek == null || time == null) return;
        int position = (int) Math.max(0, video.getCurrentPosition());
        seek.setProgress(position);
        time.setText(format(position));
    }

    private void scheduleControlsHide() {
        handler.removeCallbacks(hideControlsTask);
        if (!prepared || video == null || !video.isPlaying()
                || statePanel.getVisibility() == View.VISIBLE || panLoginDialog != null) {
            return;
        }
        handler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS);
    }

    private void hideControls() {
        if (!prepared || video == null || !video.isPlaying()
                || statePanel.getVisibility() == View.VISIBLE || panLoginDialog != null) {
            return;
        }
        hideControlsNow();
    }

    private void hideControlsNow() {
        handler.removeCallbacks(hideControlsTask);
        if (!controlsVisible && controls.getVisibility() != View.VISIBLE) return;
        controlsVisible = false;
        boolean hadFocus = controls.hasFocus();
        topPanel.animate()
                .translationY(-dp(10))
                .setDuration(170L)
                .start();
        bottomPanel.animate()
                .translationY(dp(12))
                .setDuration(170L)
                .start();
        controls.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (!controlsVisible) controls.setVisibility(View.INVISIBLE);
                })
                .start();
        if (hadFocus) root.requestFocus();
    }

    private static boolean isDirectUrl(String value) {
        String url = directUrl(value);
        if (url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("rtsp://") || lower.startsWith("rtsps://")
                || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private static TvBoxRepository.Playback normalizePlayback(
            TvBoxRepository.Playback playback) {
        if (playback == null) {
            return new TvBoxRepository.Playback("", Map.of(), "播放地址为空");
        }
        String raw = playback.url == null ? "" : playback.url.trim();
        Map<String, String> headers = new HashMap<>(playback.headers);
        int separator = raw.lastIndexOf('|');
        if (separator > 0 && separator < raw.length() - 1) {
            String suffix = raw.substring(separator + 1).trim();
            if (looksLikeHeaders(suffix)) {
                raw = raw.substring(0, separator).trim();
                parseInlineHeaders(suffix, headers);
            }
        }
        if (raw.startsWith("//")) raw = "https:" + raw;
        return new TvBoxRepository.Playback(
                raw, headers, playback.message, playback.requiresParsing);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? "未知错误" : message.trim();
    }

    private static String directUrl(String value) {
        if (value == null) return "";
        String raw = value.trim();
        int separator = raw.lastIndexOf('|');
        if (separator > 0 && looksLikeHeaders(raw.substring(separator + 1))) {
            raw = raw.substring(0, separator).trim();
        }
        return raw;
    }

    private static boolean looksLikeHeaders(String value) {
        if (value == null) return false;
        String suffix = value.trim();
        if (suffix.startsWith("{")) return true;
        String lower = suffix.toLowerCase(Locale.ROOT);
        return (lower.contains("user-agent") || lower.contains("referer")
                || lower.contains("cookie") || lower.contains("authorization")
                || lower.matches("(^|[&;])\\s*ua\\s*="))
                && (suffix.contains("=") || suffix.contains(":"));
    }

    private static void parseInlineHeaders(String rawHeaders, Map<String, String> target) {
        String value = rawHeaders == null ? "" : rawHeaders.trim();
        if (value.startsWith("{")) {
            try {
                JsonObject object = JsonParser.parseString(value).getAsJsonObject();
                for (var entry : object.entrySet()) {
                    JsonElement element = entry.getValue();
                    if (element != null && element.isJsonPrimitive()) {
                        target.put(normalizeHeaderName(entry.getKey()),
                                element.getAsString());
                    }
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        for (String item : value.split("[&;]")) {
            int separator = item.indexOf('=');
            if (separator <= 0) separator = item.indexOf(':');
            if (separator <= 0 || separator >= item.length() - 1) continue;
            target.put(normalizeHeaderName(item.substring(0, separator)),
                    item.substring(separator + 1).trim());
        }
    }

    private static String normalizeHeaderName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.equalsIgnoreCase("ua")) return "User-Agent";
        if (value.equalsIgnoreCase("referrer") || value.equalsIgnoreCase("referer")) {
            return "Referer";
        }
        return value;
    }

    private static String readablePlaybackMessage(String message) {
        if (message == null || message.isBlank()) return "暂无可用播放地址";
        return message.replaceFirst("^\\[[^\\]]+]\\(auth\\)\\s*", "").trim();
    }

    private static String friendlyPlayerError(String message) {
        String value = readablePlaybackMessage(message).toLowerCase(Locale.ROOT);
        if (value.contains("mediacodec")
                || value.contains("decoder")
                || value.contains("codec")
                || value.contains("unsupported")) {
            return "当前设备无法解码这个视频格式，请切换其他剧集或片源。";
        }
        if (value.contains("timeout")
                || value.contains("unable to connect")
                || value.contains("connection")
                || value.contains("network")) {
            return "视频连接超时，请检查网络后重试，或切换其他剧集。";
        }
        return "视频暂时无法加载，请切换其他剧集或片源。";
    }

    private void showParsingState() {
        showParsingState("正在获取播放地址", "正在向当前片源获取播放链接，请稍候…");
    }

    private void showParsingState(String title, String detail) {
        stateNeedsPanLogin = false;
        statePanBrand = "";
        stateNeedsReplay = false;
        stateNeedsSourceRetry = false;
        focusPlayWhenReady = true;
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.VISIBLE);
        stateTitle.setText(title);
        stateMessage.setText(detail);
        stateAction.setVisibility(View.GONE);
        setPlaybackControlsEnabled(false);
        hideControlsNow();
    }

    private void hideStatePanel() {
        stateNeedsPanLogin = false;
        statePanBrand = "";
        stateNeedsReplay = false;
        stateNeedsSourceRetry = false;
        statePanel.setVisibility(View.GONE);
        stateProgress.setVisibility(View.VISIBLE);
        stateAction.setVisibility(View.GONE);
        setPlaybackControlsEnabled(true);
        if (focusPlayWhenReady) {
            focusPlayWhenReady = false;
            play.post(play::requestFocus);
        }
        showControls();
    }

    private void showPlaybackError(String detail, boolean needsLogin) {
        showPlaybackError(detail, needsLogin, !needsLogin);
    }

    private void showPlaybackError(String detail, boolean needsLogin, boolean retryable) {
        switchingEpisode = false;
        stateNeedsPanLogin = needsLogin;
        statePanBrand = needsLogin ? panBrand(detail) : "";
        if (needsLogin && statePanBrand.isBlank()) statePanBrand = episodePanBrand();
        boolean savedCredential = hasPanCredential(statePanBrand);
        stateNeedsReplay = false;
        stateNeedsSourceRetry = !needsLogin ? retryable : savedCredential;
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateTitle.setText(needsLogin
                ? savedCredential ? "片源未识别网盘登录" : "需要登录网盘"
                : "暂时无法播放");
        stateMessage.setText(detail);
        stateAction.setText(needsLogin
                ? savedCredential ? "重新加载" : panLoginAction(statePanBrand)
                : stateNeedsSourceRetry ? "重新加载" : "关闭播放器");
        stateAction.setVisibility(View.VISIBLE);
        play.setImageResource(R.drawable.ic_play_light);
        setPlaybackControlsEnabled(false);
        hideControlsNow();
        stateAction.post(stateAction::requestFocus);
        if (needsLogin && !savedCredential && !statePanBrand.isBlank()
                && !autoPanPromptShown) {
            autoPanPromptShown = true;
            handler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()
                        && stateNeedsPanLogin && panLoginDialog == null) {
                    showPanLogin();
                }
            }, 280L);
        }
    }

    private void showEndedState() {
        stateNeedsPanLogin = false;
        statePanBrand = "";
        stateNeedsReplay = true;
        stateNeedsSourceRetry = false;
        statePanel.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateTitle.setText("播放已结束");
        stateMessage.setText("本集播放完毕。可以重播本集，或返回详情选择其他剧集。");
        stateAction.setText("重播本集");
        stateAction.setVisibility(View.VISIBLE);
        setPlaybackControlsEnabled(false);
        hideControlsNow();
        stateAction.post(stateAction::requestFocus);
    }

    private void setPlaybackControlsEnabled(boolean enabled) {
        play.setEnabled(enabled);
        play.setAlpha(enabled ? 1f : 0.35f);
        previous.setEnabled(enabled);
        previous.setAlpha(enabled ? 1f : 0.35f);
        next.setEnabled(enabled);
        next.setAlpha(enabled ? 1f : 0.35f);
        seek.setEnabled(enabled);
        seek.setAlpha(enabled ? 1f : 0.35f);
    }

    private static boolean needsPanLogin(String message) {
        if (message == null) return false;
        String value = message.toLowerCase(Locale.ROOT);
        return value.contains("cookie")
                || value.contains("登录")
                || value.contains("网盘")
                || value.contains("夸克")
                || value.contains("阿里云盘")
                || value.contains("百度");
    }

    private boolean panEpisodeNeedsLogin() {
        String value = (safe(episodeFlag) + " " + safe(episodeName) + " " + safe(episodeId))
                .toLowerCase(Locale.ROOT);
        return value.contains("夸父")
                || value.contains("网盘")
                || value.contains("quark")
                || value.contains("aliyun")
                || value.contains("alipan")
                || value.contains("阿里云盘")
                || value.contains("uc盘")
                || value.contains("百度盘");
    }

    private boolean quarkEpisodeNeedsLogin() {
        String value = (safe(episodeFlag) + " " + safe(episodeName) + " " + safe(episodeId))
                .toLowerCase(Locale.ROOT);
        return value.contains("夸父")
                || value.contains("夸克")
                || value.contains("quark");
    }

    private String episodePanBrand() {
        return panBrand(safe(episodeFlag) + " " + safe(episodeName));
    }

    private static boolean hasPanCredential(String brandId) {
        return PanCredentialStore.hasCredential(brandId);
    }

    private void showPanLogin() {
        if (isFinishing() || isDestroyed() || episodeId == null || episodeId.isBlank()) {
            return;
        }
        if (panLoginDialog != null) return;
        if (panRepository == null) panRepository = new PanRepository();
        String brandId = statePanBrand == null ? "" : statePanBrand;
        if (brandId.isBlank()) {
            PanCenterActivity.start(this);
            return;
        }
        PanRepository.Brand brand = PanRepository.standaloneBrand(brandId);
        String provider = panRepository.scanProvider(brand);
        if (provider == null) {
            PanCenterActivity.start(this, brandId);
            return;
        }
        panLoginDialog = PanLoginDialog.show(this, panRepository, brand, provider, false,
                new PanLoginDialog.Listener() {
                    @Override
                    public void onLoggedIn() {
                        if (isFinishing() || isDestroyed()) return;
                        panLoginDialog = null;
                        retryPlaybackAfterPanLogin();
                    }

                    @Override
                    public void onOtherMethods() {
                        panLoginDialog = null;
                        PanCenterActivity.start(PlayerActivity.this, brandId);
                    }

                    @Override
                    public void onClosed() {
                        panLoginDialog = null;
                        if (statePanel.getVisibility() != View.VISIBLE) {
                            showControls();
                        }
                    }
                });
    }

    private void retryPlaybackAfterPanLogin() {
        Toast.makeText(this, "网盘登录成功，正在重新获取播放地址", Toast.LENGTH_SHORT).show();
        stateNeedsPanLogin = false;
        autoPanPromptShown = true;
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || prepared) return;
            resolveCurrentEpisode();
        }, 350L);
    }

    private void retryCurrentEpisode() {
        if (isFinishing() || isDestroyed() || episodeId == null || episodeId.isBlank()) return;
        stateNeedsSourceRetry = false;
        resolveCurrentEpisode();
    }

    private void resolveCurrentEpisode() {
        String siteKey = historyItem != null && historyItem.site != null
                ? historyItem.site.key : getIntent().getStringExtra(EXTRA_SITE);
        playEpisode(siteKey, episodeFlag, episodeId);
    }

    private static String panBrand(String message) {
        return PanRepository.brandForSourceText(message);
    }

    private static String panLoginAction(String brandId) {
        return switch (brandId == null ? "" : brandId) {
            case "quark" -> "登录夸克网盘";
            case "aliyun" -> "登录阿里云盘";
            case "uc" -> "登录 UC 网盘";
            case "baidu" -> "登录百度网盘";
            case "pan139" -> "登录 139 云盘";
            case "thunder" -> "登录迅雷云盘";
            case "guangya" -> "登录光鸭云盘";
            case "pan123" -> "登录 123 云盘";
            case "pan115" -> "登录 115 网盘";
            case "pan189" -> "登录天翼云盘";
            case "bili" -> "登录哔哩哔哩";
            default -> "打开网盘中心";
        };
    }

    private static int resizeMode(int scale) {
        return switch (scale) {
            case AppSettings.VIDEO_SCALE_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            case AppSettings.VIDEO_SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL;
            default -> AspectRatioFrameLayout.RESIZE_MODE_FIT;
        };
    }

    private void saveHistory(boolean force) {
        if (historyItem == null || episodeId == null || episodeId.isBlank()
                || video == null || !prepared) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastHistorySaveMs < HISTORY_INTERVAL_MS) return;
        lastHistorySaveMs = now;
        UserDataStore.get(this).recordHistory(historyItem, episodeName, episodeId, episodeFlag,
                video.getCurrentPosition(), video.getDuration());
    }

    private static com.ch4nhm.tvapp.data.SiteConfig findSite(String key) {
        if (key == null || key.isBlank() || TvBoxRepository.get().getConfig() == null) return null;
        for (var candidate : TvBoxRepository.get().getConfig().sites) {
            if (key.equals(candidate.key)) return candidate;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
