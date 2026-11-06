package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class DialogStyler {

    interface OptionListener {
        void onSelected(int index);
    }

    private DialogStyler() {
    }

    static void style(AlertDialog dialog) {
        if (dialog == null) return;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog_panel);
            dialog.getWindow().setDimAmount(0.72f);
            Activity activity = activity(dialog.getContext());
            if (activity != null) constrainToViewport(dialog, activity);
        }
        int titleId = dialog.getContext().getResources()
                .getIdentifier("alertTitle", "id", "android");
        if (titleId != 0) styleText(dialog.findViewById(titleId), 28, true);
        styleText(dialog.findViewById(android.R.id.message), 18, false);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
        styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
        clearButtonBarBackgrounds(dialog);
    }

    static AlertDialog showOptions(Activity activity, String title, String[] labels,
                                   int selectedIndex, OptionListener listener) {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_option_list, null, false);
        TextView titleView = content.findViewById(R.id.dialog_option_title);
        LinearLayout options = content.findViewById(R.id.dialog_option_rows);
        ScrollView scroll = content.findViewById(R.id.dialog_option_scroll);
        titleView.setText(title);

        TextView selectedView = null;
        final AlertDialog[] dialogReference = new AlertDialog[1];
        for (int index = 0; index < labels.length; index++) {
            boolean selected = index == selectedIndex;
            TextView row = optionRow(activity, labels[index], selected);
            int optionIndex = index;
            row.setOnClickListener(view -> {
                if (dialogReference[0] != null) dialogReference[0].dismiss();
                if (listener != null) listener.onSelected(optionIndex);
            });
            options.addView(row);
            if (selected) selectedView = row;
        }

        int rowCount = Math.max(1, labels.length);
        TvLayoutMetrics metrics = TvLayout.metrics(activity);
        int rowHeight = Math.max(dp(activity, 46),
                dp(activity, metrics.buttonHeightDp + 4f));
        int desiredHeight = dp(activity, 8) + rowCount * rowHeight;
        ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
        int maximumHeight = Math.min(dp(activity, 360),
                dp(activity, metrics.dialogMaxHeightDp * 0.62f));
        scrollParams.height = Math.min(maximumHeight, desiredHeight);
        scroll.setLayoutParams(scrollParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .setNegativeButton("取消", null)
                .create();
        dialogReference[0] = dialog;
        TextView initial = selectedView;
        dialog.setOnShowListener(ignored -> {
            style(dialog);
            View focus = initial != null ? initial : options.getChildAt(0);
            if (focus != null) focus.requestFocus();
        });
        dialog.show();
        return dialog;
    }

    static AlertDialog showConfirmation(Activity activity, String title, String message,
                                        String negativeLabel, String positiveLabel,
                                        Runnable onConfirm) {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_confirmation, null, false);
        TextView titleView = content.findViewById(R.id.dialog_confirm_title);
        TextView messageView = content.findViewById(R.id.dialog_confirm_message);
        TextView negative = content.findViewById(R.id.dialog_confirm_negative);
        TextView positive = content.findViewById(R.id.dialog_confirm_positive);

        titleView.setText(title);
        messageView.setText(message);
        messageView.setVisibility(message == null || message.isBlank()
                ? View.GONE : View.VISIBLE);
        negative.setText(negativeLabel);
        positive.setText(positiveLabel);
        styleAction(negative, false);
        styleAction(positive, true);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .create();
        negative.setOnClickListener(view -> dialog.dismiss());
        positive.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        dialog.setOnShowListener(ignored -> {
            style(dialog);
            positive.requestFocus();
        });
        dialog.show();
        return dialog;
    }

    private static TextView optionRow(Activity activity, String label, boolean selected) {
        TvLayoutMetrics metrics = TvLayout.metrics(activity);
        TextView row = new TextView(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(activity, 46), dp(activity, metrics.buttonHeightDp + 4f)));
        params.bottomMargin = dp(activity, 4);
        row.setLayoutParams(params);
        row.setText(selected ? "✓  " + label : label);
        row.setTextColor(activity.getColorStateList(R.color.filter_option_text));
        row.setTextSize(18);
        row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 22), 0, dp(activity, 22), 0);
        row.setBackgroundResource(R.drawable.bg_filter_option);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setSelected(selected);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setSingleLine(true);
        row.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.post(() -> view.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true));
        });
        return row;
    }

    private static void styleText(TextView view, int sizeSp, boolean title) {
        if (view == null) return;
        view.setTextSize(sizeSp);
        view.setTextColor(0xfff5f5f7);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(
                title ? "sans-serif-black" : "sans-serif", Typeface.NORMAL));
        if (title) view.setLetterSpacing(-0.01f);
    }

    private static void styleAction(TextView view, boolean primary) {
        view.setTextSize(18);
        view.setTextColor(primary ? 0xff050506 : 0xfff5f5f7);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        FocusAnimator.scale(view, 1.025f);
    }

    private static void clearButtonBarBackgrounds(AlertDialog dialog) {
        clearAncestorBackgrounds(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        clearAncestorBackgrounds(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
        clearAncestorBackgrounds(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    private static void clearAncestorBackgrounds(View view) {
        View current = view;
        while (current != null && current.getId() != android.R.id.content) {
            if (current instanceof ViewGroup group) {
                group.setBackground(null);
                group.setBackgroundTintList(null);
                if (group instanceof ScrollView scroll) scroll.setForeground(null);
            }
            if (!(current.getParent() instanceof View parent)) return;
            current = parent;
        }
    }

    private static void styleButton(Button button, boolean primary) {
        if (button == null) return;
        TvLayoutMetrics metrics = TvLayout.metrics(button.getContext());
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTextColor(primary ? 0xff050506 : 0xfff5f5f7);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        // Material dialog buttons apply a theme tint over custom drawable backgrounds.
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setMinWidth(dp(button, 132));
        button.setMinHeight(dp(button, metrics.buttonHeightDp));
        button.setPadding(dp(button, 24), dp(button, 12),
                dp(button, 24), dp(button, 12));
        styleButtonBar(button, primary);
    }

    private static void styleButtonBar(Button button, boolean primary) {
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMarginStart(primary ? dp(button, 12) : 0);
            button.setLayoutParams(margins);
        }
        if (!(button.getParent() instanceof ViewGroup parent)
                || !parent.getClass().getSimpleName().contains("ButtonBar")) {
            return;
        }
        parent.setClipToPadding(false);
        parent.setPadding(dp(button, 24), dp(button, 12),
                dp(button, 24), dp(button, 24));
    }

    private static int dp(Button button, int value) {
        return Math.round(value * button.getResources().getDisplayMetrics().density);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static Activity activity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper wrapper) {
            if (current instanceof Activity activity) return activity;
            current = wrapper.getBaseContext();
        }
        return current instanceof Activity activity ? activity : null;
    }

    private static void constrainToViewport(AlertDialog dialog, Activity activity) {
        if (dialog.getWindow() == null) return;
        TvLayoutMetrics metrics = TvLayout.metrics(activity);
        int available = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels
                - dp(activity, metrics.contentMarginDp * 2f));
        if (available >= dp(activity, 760)) return;
        int width = Math.min(available, dp(activity, metrics.dialogMaxWidthDp));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
