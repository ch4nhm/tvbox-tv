package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * Runs the TVBox pan-provider QR flow directly in the app. The source exposes
 * start/poll/cancel endpoints through its local proxy, so opening the full web
 * configuration centre is unnecessary for the normal login path.
 */
final class PanLoginDialog {

    private static final String TAG = "TVBoxPanLogin";

    interface Listener {
        void onLoggedIn();

        void onOtherMethods();

        default void onClosed() {
        }
    }

    private static final long FIRST_POLL_DELAY_MS = 900L;
    private static final long POLL_INTERVAL_MS = 1_500L;
    private static final int MAX_TRANSIENT_ERRORS = 4;

    private final Activity activity;
    private final PanRepository repository;
    private final Listener listener;
    private final String brandName;
    private final String provider;
    private final boolean allowOtherMethods;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView qrImage;
    private final ProgressBar progress;
    private final TextView status;
    private final Dialog dialog;
    private final Runnable pollRunnable = this::poll;
    private final String credentialFingerprintAtStart;

    private String taskId;
    private boolean closed;
    private boolean succeeded;
    private int transientErrors;

    static PanLoginDialog show(Activity activity, PanRepository repository,
                               PanRepository.Brand brand, String provider,
                               Listener listener) {
        return show(activity, repository, brand, provider, true, listener);
    }

    static PanLoginDialog show(Activity activity, PanRepository repository,
                               PanRepository.Brand brand, String provider,
                               boolean allowOtherMethods, Listener listener) {
        PanLoginDialog login = null;
        try {
            login = new PanLoginDialog(
                    activity, repository, brand, provider, allowOtherMethods, listener);
            login.start();
            return login;
        } catch (Throwable error) {
            String message = "无法打开扫码登录：" + rootMessage(error);
            Log.e(TAG, message, error);
            if (login != null) {
                login.showTerminal(message);
                return login;
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private PanLoginDialog(Activity activity, PanRepository repository,
                           PanRepository.Brand brand, String provider,
                           boolean allowOtherMethods, Listener listener) {
        this.activity = activity;
        this.repository = repository;
        this.listener = listener;
        this.brandName = brand == null ? "网盘" : brand.name;
        this.provider = provider;
        this.allowOtherMethods = allowOtherMethods;
        this.credentialFingerprintAtStart =
                PanCredentialStore.credentialFingerprint(provider);

        int panelPadding = dp(30);
        int panelWidth = panelWidth();
        int qrSize = qrSize();
        LinearLayout root = new LinearLayout(activity);
        root.setClipChildren(false);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(panelPadding, dp(24), panelPadding, dp(24));
        root.setBackground(panelBackground());

        TextView title = text(brandName + "扫码登录", 30, 0xfff5f5f7);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        root.addView(title, wrap());

        FrameLayout qrFrame = new FrameLayout(activity);
        qrFrame.setBackground(qrBackground());
        qrFrame.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams frameParams =
                new LinearLayout.LayoutParams(qrSize, qrSize);
        frameParams.gravity = Gravity.CENTER_HORIZONTAL;
        frameParams.topMargin = dp(18);
        root.addView(qrFrame, frameParams);

        qrImage = new ImageView(activity);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImage.setContentDescription(brandName + "登录二维码");
        qrFrame.addView(qrImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(activity);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff050506));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(42), dp(42));
        progressParams.gravity = Gravity.CENTER;
        qrFrame.addView(progress, progressParams);

        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statusRowParams = wrap();
        statusRowParams.topMargin = dp(14);
        root.addView(statusRow, statusRowParams);

        status = text("正在生成二维码…", 19, 0xfff5f5f7);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusRow.addView(status, wrap());

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setClipChildren(false);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams =
                new LinearLayout.LayoutParams(panelWidth - panelPadding * 2, dp(56));
        actionsParams.topMargin = dp(18);
        root.addView(actionRow, actionsParams);

        TextView cancel = action("取消", false);
        TextView other = action("其他方式", false);
        TextView refresh = action("刷新二维码", true);
        actionRow.addView(cancel, actionParams(0));
        if (allowOtherMethods) {
            actionRow.addView(other, actionParams(dp(10)));
        }
        actionRow.addView(refresh, actionParams(dp(10)));

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(true);
        cancel.setOnClickListener(view -> dialog.dismiss());
        if (allowOtherMethods) {
            other.setOnClickListener(view -> {
                dialog.dismiss();
                listener.onOtherMethods();
            });
        }
        refresh.setOnClickListener(view -> start());
        dialog.setOnDismissListener(ignored -> {
            close();
            listener.onClosed();
        });

        dialog.show();
        Log.i(TAG, "Login dialog shown: provider=" + provider
                + ", hasExistingCredential="
                + !credentialFingerprintAtStart.isBlank());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.78f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.CENTER);
            window.setLayout(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getDecorView().setPadding(0, 0, 0, 0);
        }
    }

    private void start() {
        if (closed) return;
        Log.i(TAG, "Starting scan login: provider=" + provider);
        stopPolling();
        cancelCurrentTask();
        transientErrors = 0;
        Glide.with(activity).clear(qrImage);
        qrImage.setImageDrawable(null);
        qrImage.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.VISIBLE);
        status.setTextColor(0xfff5f5f7);
        status.setText("正在生成二维码…");

        try {
            repository.startScanLogin(provider, result -> activity.runOnUiThread(() -> {
                if (closed) {
                    repository.cancelScanLogin(provider, result.taskId);
                    return;
                }
                taskId = result.taskId;
                Log.i(TAG, "QR generated: provider=" + provider
                        + ", image=" + result.image);
                if (result.image) {
                    loadQrImage(result);
                } else {
                    Bitmap bitmap = QrCodes.encode(result.qrUrl, qrSize() - dp(28));
                    if (bitmap == null) {
                        showTerminal("二维码内容无效，请刷新重试");
                        return;
                    }
                    showQrCode(bitmap, result.message);
                }
            }), error -> activity.runOnUiThread(() -> {
                if (!closed) showTerminal("登录失败：" + rootMessage(error));
            }));
        } catch (Throwable error) {
            Log.e(TAG, "Scan login start failed: provider=" + provider, error);
            showTerminal("登录启动失败：" + rootMessage(error));
        }
    }

    private void loadQrImage(PanRepository.ScanLogin result) {
        int size = qrSize() - dp(28);
        Glide.with(activity)
                .asBitmap()
                .load(result.qrUrl)
                .into(new CustomTarget<Bitmap>(size, size) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (closed || !result.taskId.equals(taskId)) return;
                        showQrCode(resource, result.message);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        if (!closed && result.taskId.equals(taskId)) {
                            showTerminal("二维码图片加载失败，请刷新重试");
                        }
                    }
                });
    }

    private void showQrCode(Bitmap bitmap, String message) {
        qrImage.setImageBitmap(bitmap);
        qrImage.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        status.setText(message);
        handler.postDelayed(pollRunnable, FIRST_POLL_DELAY_MS);
    }

    private void poll() {
        String currentTask = taskId;
        if (closed || currentTask == null || currentTask.isBlank()) return;
        repository.pollScanLogin(provider, currentTask, result -> activity.runOnUiThread(() -> {
            if (closed || !currentTask.equals(taskId)) return;
            transientErrors = 0;
            if (result.succeeded()) {
                completeLogin();
                return;
            }
            status.setText(result.message);
            verifySavedSession(currentTask, result);
        }), error -> activity.runOnUiThread(() -> {
            if (closed || !currentTask.equals(taskId)) return;
            transientErrors++;
            if (transientErrors >= MAX_TRANSIENT_ERRORS) {
                showTerminal("等待登录结果超时，请刷新二维码重试");
                return;
            }
            status.setText("网络波动，正在重试…");
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }));
    }

    private void verifySavedSession(String currentTask, PanRepository.ScanStatus result) {
        repository.isScanProviderConfigured(provider, configured ->
                activity.runOnUiThread(() -> {
                    if (closed || !currentTask.equals(taskId)) return;
                    String currentFingerprint =
                            PanCredentialStore.credentialFingerprint(provider);
                    boolean credentialChanged = PanLoginCompletion.credentialsUpdated(
                            configured, credentialFingerprintAtStart, currentFingerprint);
                    if (credentialChanged) {
                        Log.i(TAG, "Credential changed while polling: provider=" + provider);
                        completeLogin();
                    } else if (result.terminal) {
                        showTerminal(result.message);
                    } else {
                        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                    }
                }), error -> activity.runOnUiThread(() -> {
                    if (closed || !currentTask.equals(taskId)) return;
                    if (result.terminal) showTerminal(result.message);
                    else handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                }));
    }

    private void completeLogin() {
        if (closed || succeeded) return;
        Log.i(TAG, "Scan login completed: provider=" + provider);
        succeeded = true;
        taskId = null;
        stopPolling();
        progress.setVisibility(View.GONE);
        status.setText("登录成功");
        dialog.dismiss();
        listener.onLoggedIn();
    }

    private void showTerminal(String message) {
        Log.w(TAG, "Scan login terminal: provider=" + provider + ", message=" + message);
        stopPolling();
        cancelCurrentTask();
        Glide.with(activity).clear(qrImage);
        progress.setVisibility(View.GONE);
        status.setTextColor(0xffff9f9f);
        status.setText(message == null || message.isBlank() ? "登录失败" : message);
        qrImage.setVisibility(View.INVISIBLE);
    }

    private void close() {
        if (closed) return;
        Log.i(TAG, "Login dialog closed: provider=" + provider
                + ", succeeded=" + succeeded);
        closed = true;
        stopPolling();
        if (!succeeded) cancelCurrentTask();
        handler.removeCallbacksAndMessages(null);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollRunnable);
    }

    private void cancelCurrentTask() {
        String current = taskId;
        taskId = null;
        if (current != null && !current.isBlank()) {
            repository.cancelScanLogin(provider, current);
        }
    }

    private TextView action(String label, boolean primary) {
        TextView view = text(label, 17, primary ? 0xff050506 : 0xfff5f5f7);
        view.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FocusAnimator.scale(view, 1.025f);
        return view;
    }

    private LinearLayout.LayoutParams actionParams(int startMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginStart(startMargin);
        return params;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int panelWidth() {
        TvLayoutMetrics metrics = TvLayout.metrics(activity);
        TvViewport viewport = TvDisplayAdapter.viewport(activity);
        int available = dp(viewport.usableWidthDp) - dp(24);
        int target = dp(viewport.usableWidthDp * 0.62f);
        int minimum = dp(Math.min(560f, metrics.dialogMaxWidthDp));
        int maximum = dp(Math.min(680f, metrics.dialogMaxWidthDp));
        return Math.max(1, Math.min(available, Math.max(minimum, Math.min(maximum, target))));
    }

    private int qrSize() {
        TvViewport viewport = TvDisplayAdapter.viewport(activity);
        int available = dp(viewport.usableHeightDp - 260f);
        return Math.max(dp(150), Math.min(dp(210), available));
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xf21c1c1e);
        drawable.setCornerRadius(dp(30));
        drawable.setStroke(dp(1), 0x33ffffff);
        return drawable;
    }

    private GradientDrawable qrBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(22));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? "" : current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

}
