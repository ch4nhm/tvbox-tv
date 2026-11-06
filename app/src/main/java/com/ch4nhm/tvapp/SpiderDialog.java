package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;

import com.bumptech.glide.Glide;
import com.github.catvod.Runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * TVBox Spider dialog host compatible with catvod's Dialog options contract.
 * The options are deliberately rendered dynamically because encrypted spiders
 * use this for QR-code login, cookie prompts and multi-step verification.
 */
public final class SpiderDialog {

    private static final int MESSAGE_ID = 10_001;
    private static final int ERROR_ID = 10_002;
    private static final int COPY_ID = 10_003;
    private static final int QRCODE_ID = 10_004;
    private static final int IMAGE_ID = 10_005;
    private static final int FIELD_BASE = 20_000;
    private static final int ACTION_BASE = 30_000;
    private static final int PICKER_BASE = 40_000;

    private final Activity activity;
    private final Function<String, CompletableFuture<String>> onAction;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final Map<String, Input> inputs = new LinkedHashMap<>();
    private final Map<Integer, ButtonOptions> buttons = new LinkedHashMap<>();
    private Dialog dialog;
    private Dialog picker;
    private int pickerId;
    private TextView message;
    private TextView copy;
    private ImageView qrcode;
    private ImageView image;
    private LinearLayout actions;
    private CompletableFuture<String> action;
    private boolean closed;
    private int requestId;
    private java.util.function.Consumer<String> completedListener;

    public static CompletableFuture<String> showOptions(
            String options, Function<String, CompletableFuture<String>> onAction) {
        return show(options, onAction);
    }

    public static CompletableFuture<String> show(String options) {
        return show(options, null);
    }

    public static CompletableFuture<String> show(String options, Function<String, CompletableFuture<String>> onAction) {
        Activity current = Runtime.activity();
        if (current == null || current.isFinishing() || current.isDestroyed()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("当前没有可用页面显示登录窗口"));
            return failed;
        }
        SpiderDialog request = new SpiderDialog(current, onAction);
        current.runOnUiThread(() -> request.open(options));
        return request.result;
    }

    public static CompletableFuture<String> show(
            int requestId,
            String options,
            Function<String, CompletableFuture<String>> onAction,
            java.util.function.Consumer<String> completed) {
        Activity current = Runtime.activity();
        if (current == null || current.isFinishing() || current.isDestroyed()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("当前没有可用页面显示登录窗口"));
            return failed;
        }
        SpiderDialog request = new SpiderDialog(current, onAction);
        request.requestId = requestId;
        request.completedListener = completed;
        current.runOnUiThread(() -> request.open(options));
        return request.result;
    }

    private SpiderDialog(Activity activity, Function<String, CompletableFuture<String>> onAction) {
        this.activity = activity;
        this.onAction = onAction;
        result.whenComplete((value, error) -> {
            if (value != null && completedListener != null) completedListener.accept(value);
            activity.runOnUiThread(this::close);
        });
    }

    private void open(String options) {
        try {
            JSONObject json = new JSONObject(options);
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(flag(json, "cancelable", true));
            dialog.setOnDismissListener(ignored -> result.complete(null));
            dialog.setContentView(buildContent(requestId, json));
            bindButtons(json.optJSONArray("buttons"));
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(64), dp(760));
                attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
                attributes.gravity = Gravity.CENTER;
                attributes.dimAmount = 0.72f;
                window.setAttributes(attributes);
            }
            dialog.show();
            if (!result.isDone()) focusFirst();
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    private View buildContent(int requestId, JSONObject options) throws JSONException {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(30), dp(24), dp(30), dp(24));
        root.setBackground(background());

        String title = options.optString("title", "").trim();
        if (!title.isEmpty()) {
            TextView heading = text(title, 28, Color.WHITE);
            heading.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-black", android.graphics.Typeface.BOLD));
            root.addView(heading, matchWrap(0, 0, 0, dp(12)));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        int availableHeight = activity.getResources().getDisplayMetrics().heightPixels - dp(220);
        scrollParams.height = Math.max(dp(220), Math.min(dp(570), availableHeight));
        root.addView(scroll, scrollParams);

        message = text("", 18, 0xffe9eef7);
        message.setId(viewId(requestId, MESSAGE_ID));
        message.setTextIsSelectable(flag(options, "copyable", false));
        message.setOnKeyListener(this::onFocusKey);
        content.addView(message, matchWrap(0, 0, 0, dp(8)));
        setMessage(options.optString("message", ""));

        if (flag(options, "copyable", false)) {
            copy = text("复制内容", 17, Color.WHITE);
            copy.setId(viewId(requestId, COPY_ID));
            copy.setGravity(Gravity.CENTER);
            copy.setFocusable(true);
            copy.setBackgroundResource(R.drawable.bg_button_secondary);
            copy.setBackgroundTintList(null);
            copy.setMinWidth(dp(132));
            copy.setMinHeight(dp(48));
            copy.setPadding(dp(22), dp(10), dp(22), dp(10));
            FocusAnimator.scale(copy, 1.025f);
            copy.setOnClickListener(view -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TVBox", message.getText()));
                android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.END;
            params.bottomMargin = dp(8);
            content.addView(copy, params);
        }

        qrcode = addImage(content, viewId(requestId, QRCODE_ID));
        image = addImage(content, viewId(requestId, IMAGE_ID));
        if (options.has("qrcode")) setQrCode(options.opt("qrcode"));
        if (options.has("image")) setImage(options.opt("image"));
        bindInputs(content, options.optJSONArray("inputs"), requestId);

        actions = new LinearLayout(activity);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(18), 0, 0);
        root.addView(actions, matchWrap(0, 0, 0, 0));
        return root;
    }

    private void bindButtons(JSONArray options) throws JSONException {
        if (options == null || options.length() == 0) {
            Button positive = button("确定", true);
            positive.setOnClickListener(view -> submitDefault());
            actions.addView(positive, buttonParams());
            Button negative = button("取消", false);
            negative.setOnClickListener(view -> result.complete(null));
            actions.addView(negative, buttonParams());
            buttons.put(1, new ButtonOptions("ok", positive, true, new HashSet<>(inputs.keySet()), true));
            return;
        }
        if (options.length() > 3) throw new IllegalArgumentException("Dialog supports at most three buttons");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < options.length(); index++) {
            JSONObject json = options.getJSONObject(index);
            String id = json.getString("id");
            String label = json.getString("label");
            if (id.isBlank() || label.isBlank() || !ids.add(id))
                throw new IllegalArgumentException("Dialog button ids and labels are required");
            boolean enabled = flag(json, "enabled", true);
            Set<String> fields = validationFields(json.optJSONArray("validate"));
            Button view = button(label, index == 0);
            ButtonOptions config = new ButtonOptions(id, view, flag(json, "close", true), fields, enabled);
            view.setEnabled(enabled);
            view.setOnClickListener(ignored -> click(config));
            actions.addView(view, buttonParams());
            buttons.put(index == 0 ? 1 : index == 1 ? -2 : -3, config);
        }
    }

    private Set<String> validationFields(@Nullable JSONArray names) throws JSONException {
        if (names == null) return new HashSet<>(inputs.keySet());
        Set<String> selected = new HashSet<>();
        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            if (!inputs.containsKey(name)) throw new IllegalArgumentException("Unknown dialog field: " + name);
            selected.add(name);
        }
        return selected;
    }

    private Button button(String label, boolean primary) {
        Button value = new Button(activity);
        value.setText(label);
        value.setAllCaps(false);
        value.setTextSize(17);
        value.setTextColor(primary ? 0xff050506 : Color.WHITE);
        value.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        value.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        value.setBackgroundTintList(null);
        value.setStateListAnimator(null);
        value.setMinWidth(dp(132));
        value.setMinHeight(dp(56));
        value.setPadding(dp(24), dp(12), dp(24), dp(12));
        FocusAnimator.scale(value, 1.025f);
        return value;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(12);
        return params;
    }

    private void submitDefault() {
        ButtonOptions first = buttons.get(1);
        if (first != null) click(first);
    }

    private void click(ButtonOptions button) {
        if (closed || result.isDone() || action != null || button.view == null || !button.enabled) return;
        if (!validate(button.fields)) return;
        try {
            String event = new JSONObject().put("button", button.id).put("values", values()).toString();
            if (onAction == null) {
                result.complete(event);
                return;
            }
            setBusy(true);
            action = Objects.requireNonNull(onAction.apply(event), "Dialog action returned null");
            CompletableFuture<String> current = action;
            current.whenComplete((update, error) ->
                    activity.runOnUiThread(() -> finishAction(current, button, update, error)));
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    private void finishAction(CompletableFuture<String> current, ButtonOptions button, String update, Throwable error) {
        if (closed || result.isDone() || action != current) return;
        action = null;
        setBusy(false);
        if (error != null) {
            result.completeExceptionally(error);
            return;
        }
        try {
            JSONObject patch = update == null || update.isBlank() ? new JSONObject() : new JSONObject(update);
            applyPatch(patch);
            boolean close = flag(patch, "close", button.close);
            if (close) result.complete(new JSONObject().put("button", button.id).put("values", values()).toString());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private void applyPatch(JSONObject patch) throws JSONException {
        if (patch.has("message")) setMessage(patch.optString("message", ""));
        if (patch.has("qrcode")) setQrCode(patch.opt("qrcode"));
        if (patch.has("image")) setImage(patch.opt("image"));
        if (patch.has("values")) setValues(patch.getJSONObject("values"));
        if (patch.has("inputs")) setInputStates(patch.getJSONArray("inputs"));
        if (patch.has("buttons")) setButtonStates(patch.getJSONArray("buttons"));
        if (patch.has("errors")) setErrors(patch.getJSONObject("errors"));
    }

    private String values() throws JSONException {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Input> entry : inputs.entrySet()) values.put(entry.getKey(), entry.getValue().getValue());
        return values.toString();
    }

    private boolean validate(Set<String> selected) {
        Input first = null;
        for (Map.Entry<String, Input> entry : inputs.entrySet()) {
            Input input = entry.getValue();
            input.setError(null);
            if (!selected.contains(entry.getKey()) || !input.enabled) continue;
            String error = input.validationError();
            if (error != null) {
                input.setError(error);
                if (first == null) first = input;
            }
        }
        if (first != null) {
            first.view.requestFocus();
            return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        for (ButtonOptions button : buttons.values()) button.view.setEnabled(!busy && button.enabled);
        for (Input input : inputs.values()) input.setEnabled(!busy && input.enabled);
        if (copy != null) copy.setEnabled(!busy);
    }

    private TextView text(String value, int sp, int color) {
        AppCompatTextView view = new AppCompatTextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.18f);
        return view;
    }

    private ImageView addImage(LinearLayout content, int id) {
        ImageView view = new ImageView(activity);
        view.setId(id);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        content.addView(view, params);
        return view;
    }

    private void setQrCode(Object source) {
        String value = source instanceof String string ? string : "";
        qrcode.setVisibility(View.GONE);
        if (value.isBlank()) return;
        Bitmap bitmap = QrCodes.encode(value, dp(240));
        if (bitmap == null) throw new IllegalArgumentException("二维码内容无法编码");
        qrcode.setImageBitmap(bitmap);
        qrcode.setVisibility(View.VISIBLE);
    }

    private void setImage(Object source) {
        String value = source instanceof String string ? string : "";
        if (value.isBlank()) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            return;
        }
        if (!value.startsWith("data:image/") || !value.contains(";base64,")) {
            image.setVisibility(View.GONE);
            return;
        }
        image.setVisibility(View.VISIBLE);
        Glide.with(activity).asBitmap().load(value).fitCenter().into(image);
    }

    private void setMessage(String value) {
        message.setText(value == null ? "" : value);
        message.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        if (copy != null) copy.setVisibility(message.getVisibility());
    }

    private void bindInputs(LinearLayout content, @Nullable JSONArray fields, int requestId) throws JSONException {
        if (fields == null) return;
        for (int index = 0; index < fields.length(); index++) addInput(content, fields.getJSONObject(index), requestId);
    }

    private void addInput(LinearLayout content, JSONObject field, int requestId) throws JSONException {
        String name = field.getString("name");
        String type = field.optString("type", "text");
        if (name.isBlank() || inputs.containsKey(name)) throw new IllegalArgumentException("登录表单字段无效");
        boolean multiple = "multiselect".equals(type);
        boolean select = "select".equals(type);
        List<Choice> choices = select || multiple ? readChoices(field.getJSONArray("options")) : List.of();
        EditText input;
        if (select) {
            AppCompatAutoCompleteTextView autoComplete = new AppCompatAutoCompleteTextView(activity);
            autoComplete.setInputType(InputType.TYPE_NULL);
            autoComplete.setKeyListener(null);
            ArrayAdapter<Choice> adapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_dropdown_item_1line, choices);
            autoComplete.setAdapter(adapter);
            autoComplete.setOnItemClickListener((parent, view, position, id) -> {
                Input control = inputs.get(name);
                if (control != null) control.selected = adapter.getItem(position).value;
            });
            input = autoComplete;
        } else {
            AppCompatEditText edit = new AppCompatEditText(activity);
            edit.setSingleLine(!type.equals("textarea"));
            edit.setInputType(inputType(type));
            edit.setMinLines(type.equals("textarea") ? 3 : 1);
            edit.setGravity(type.equals("textarea") ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
            input = edit;
        }
        input.setHint(field.optString("label", name));
        input.setId(viewId(requestId, FIELD_BASE + inputs.size()));
        input.setHintTextColor(0xff8291a8);
        input.setTextColor(Color.WHITE);
        input.setTextSize(18);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setOnKeyListener(this::onFocusKey);
        input.setOnClickListener(view -> {
            if (multiple) showMultiSelect(inputs.get(name));
        });
        Input control = new Input(input, field.optString("label", name), field);
        inputs.put(name, control);
        content.addView(input, matchWrap(0, 0, 0, dp(10)));
        Object value = field.opt("value");
        if (value != null) control.setValue(value);
    }

    private void showMultiSelect(Input input) {
        if (input == null || action != null || !input.enabled) return;
        String[] labels = input.choices.stream().map(choice -> choice.label).toArray(String[]::new);
        boolean[] checked = new boolean[labels.length];
        for (int index = 0; index < labels.length; index++) checked[index] = input.checked.contains(input.choices.get(index).value);
        int currentPickerId = ++pickerId;
        android.app.AlertDialog current = new android.app.AlertDialog.Builder(activity)
                .setTitle(input.label)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> checked[which] = selected)
                .setPositiveButton("确定", (dialog, which) -> {
                    List<String> values = new ArrayList<>();
                    for (int index = 0; index < checked.length; index++) if (checked[index]) values.add(input.choices.get(index).value);
                    input.setValue(new JSONArray(values));
                })
                .setNegativeButton("取消", null)
                .create();
        picker = current;
        current.setOnDismissListener(dialog -> {
            if (picker == current && !result.isDone()) focusFirst();
        });
        current.show();
    }

    private List<Choice> readChoices(JSONArray array) throws JSONException {
        List<Choice> result = new ArrayList<>();
        Set<String> values = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String value = item.getString("value");
            String label = item.optString("label", value);
            if (value.isBlank() || label.isBlank() || !values.add(value))
                throw new IllegalArgumentException("登录选项无效");
            result.add(new Choice(value, label));
        }
        return result;
    }

    private void setValues(JSONObject values) throws JSONException {
        for (Iterator<String> names = values.keys(); names.hasNext(); ) {
            String name = names.next();
            Input input = inputs.get(name);
            if (input == null) throw new IllegalArgumentException("Unknown dialog field: " + name);
            input.setValue(values.get(name));
        }
    }

    private void setInputStates(JSONArray states) throws JSONException {
        for (int index = 0; index < states.length(); index++) {
            JSONObject state = states.getJSONObject(index);
            Input input = inputs.get(state.getString("name"));
            if (input == null) throw new IllegalArgumentException("Unknown dialog field");
            input.enabled = flag(state, "enabled", input.enabled);
            input.setEnabled(action == null && input.enabled);
        }
    }

    private void setButtonStates(JSONArray states) throws JSONException {
        for (int index = 0; index < states.length(); index++) {
            JSONObject state = states.getJSONObject(index);
            String id = state.getString("id");
            ButtonOptions button = buttons.values().stream()
                    .filter(item -> item.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown dialog button"));
            button.enabled = flag(state, "enabled", button.enabled);
            button.view.setEnabled(action == null && button.enabled);
        }
    }

    private void setErrors(JSONObject errors) throws JSONException {
        for (Iterator<String> names = errors.keys(); names.hasNext(); ) {
            String name = names.next();
            Input input = inputs.get(name);
            if (input == null) throw new IllegalArgumentException("Unknown dialog field");
            input.setError(errors.isNull(name) ? null : errors.optString(name, null));
        }
    }

    private boolean onFocusKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!(view instanceof EditText)) return false;
            view.performClick();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View next = view.focusSearch(keyCode == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
            return next != null && next.requestFocus();
        }
        return false;
    }

    private void focusFirst() {
        for (Input input : inputs.values()) if (input.enabled && input.view.requestFocus()) return;
        for (ButtonOptions button : buttons.values()) if (button.enabled && button.view.requestFocus()) return;
        if (qrcode != null && qrcode.getVisibility() == View.VISIBLE) qrcode.requestFocus();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (action != null) action.cancel(true);
        if (picker != null) {
            picker.dismiss();
            picker = null;
        }
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            dialog = null;
        }
        inputs.clear();
        buttons.clear();
    }

    private int inputType(String type) {
        return switch (type) {
            case "number" -> InputType.TYPE_CLASS_NUMBER;
            case "phone" -> InputType.TYPE_CLASS_PHONE;
            case "password" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            case "textarea" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            case "text", "select", "multiselect" -> InputType.TYPE_CLASS_TEXT;
            default -> InputType.TYPE_CLASS_TEXT;
        };
    }

    private GradientDrawable background() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xff262628, 0xff151517});
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), 0x33ffffff);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private int viewId(int requestId, int suffix) {
        return 0x40000000 | ((requestId & 0x1fff) << 16) | (suffix & 0xffff);
    }

    private boolean flag(JSONObject json, String key, boolean fallback) {
        return json.has(key) ? json.optBoolean(key, fallback) : fallback;
    }

    private static final class ButtonOptions {
        private final String id;
        private final Button view;
        private final boolean close;
        private final Set<String> fields;
        private boolean enabled;

        private ButtonOptions(String id, Button view, boolean close, Set<String> fields, boolean enabled) {
            this.id = id;
            this.view = view;
            this.close = close;
            this.fields = fields;
            this.enabled = enabled;
        }
    }

    private record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class Input {
        private final EditText view;
        private final String label;
        private final List<Choice> choices;
        private final boolean multiple;
        private final boolean required;
        private final int min;
        private final int max;
        private boolean enabled;
        private String selected = "";
        private List<String> checked = List.of();
        private String error;

        private Input(EditText view, String label, JSONObject options) {
            this.view = view;
            this.label = label;
            this.multiple = "multiselect".equals(options.optString("type"));
            this.choices = readChoices(options.optJSONArray("options"));
            this.required = options.optBoolean("required", false);
            this.min = options.optInt(multiple ? "minItems" : "minLength", 0);
            this.max = options.optInt(multiple ? "maxItems" : "maxLength", Integer.MAX_VALUE);
            this.enabled = options.optBoolean("enabled", true);
        }

        private static List<Choice> readChoices(@Nullable JSONArray array) {
            if (array == null) return List.of();
            List<Choice> values = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject option = array.optJSONObject(index);
                if (option == null) continue;
                String value = option.optString("value", "");
                String label = option.optString("label", value);
                if (!value.isBlank()) values.add(new Choice(value, label.isBlank() ? value : label));
            }
            return values;
        }

        private Object getValue() {
            if (multiple) return new JSONArray(checked);
            if (view instanceof AppCompatAutoCompleteTextView) return selected;
            return view.getText() == null ? "" : view.getText().toString();
        }

        private void setValue(Object value) {
            if (multiple) {
                List<String> values = new ArrayList<>();
                JSONArray array = value instanceof JSONArray json ? json : new JSONArray();
                for (int index = 0; index < array.length(); index++) values.add(array.optString(index));
                checked = values;
                view.setText(TextUtils.join(", ", choices.stream()
                        .filter(choice -> checked.contains(choice.value))
                        .map(choice -> choice.label).toList()));
            } else if (view instanceof AppCompatAutoCompleteTextView select) {
                selected = value == null ? "" : String.valueOf(value);
                String text = choices.stream().filter(choice -> choice.value.equals(selected))
                        .map(choice -> choice.label).findFirst().orElse(selected);
                select.setText(text, false);
            } else {
                view.setText(value == null ? "" : String.valueOf(value));
            }
            error = null;
        }

        private String validationError() {
            String value = multiple ? "" : String.valueOf(getValue());
            int length = multiple ? checked.size() : value.codePointCount(0, value.length());
            if (required && length == 0) return "此字段不能为空";
            if (length > 0 && length < min) return multiple ? "至少选择 " + min + " 项" : "至少输入 " + min + " 个字符";
            if (length > max) return multiple ? "最多选择 " + max + " 项" : "最多输入 " + max + " 个字符";
            return null;
        }

        private void setError(String value) {
            error = value;
            if (view.getParent() instanceof ViewGroup parent) {
                TextView helper = view.getTag() instanceof TextView text ? text : null;
                if (helper == null) {
                    helper = new TextView(view.getContext());
                    helper.setTextSize(16);
                    helper.setTextColor(0xffff6b7a);
                    ((ViewGroup) parent).addView(helper);
                    view.setTag(helper);
                }
                helper.setText(value == null ? "" : value);
                helper.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }

        private void setEnabled(boolean value) {
            view.setEnabled(value);
            view.setAlpha(value ? 1f : 0.55f);
            if (!value && view instanceof AppCompatAutoCompleteTextView select) select.dismissDropDown();
        }
    }
}
