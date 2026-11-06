package cn.felix021.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0d141b, CARD = 0xff17232d, TEXT = 0xffedf5f7;
    private static final int MUTED = 0xff96aab9, ACCENT = 0xff80e2c3;
    private SharedPreferences prefs;
    private EditText host, port, size, bitrate, fps;
    private Spinner codec;
    private Switch viewOnly, stayAwake, showNavigation;
    private Profile profile;
    private ScrcpySession session;
    private boolean mirroring;
    private int generation, videoWidth, videoHeight;
    private FrameLayout videoArea;
    private SurfaceView surface;
    private TextView connectionStatus;
    private String errorMessage;
    private final android.util.SparseArray<EdgeGesture> touchGestures = new android.util.SparseArray<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("connections", MODE_PRIVATE);
        showForm();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(sp); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); return t;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    private void gap(LinearLayout l, int height) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void add(LinearLayout parent, View view, int height) {
        parent.addView(view, new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height)));
    }
    private Button button(String text, boolean primary) {
        Button b = new Button(this); b.setText(text); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(primary ? BG : TEXT); b.setTypeface(null, Typeface.BOLD);
        b.setBackground(background(primary ? ACCENT : 0xff243642, 14));
        b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setBackground(background(CARD, 18)); c.setPadding(dp(14), dp(14), dp(14), dp(14));
        add(parent, c, -2); return c;
    }
    private EditText field(LinearLayout parent, String title, String value, boolean numeric) {
        add(parent, label(title, 11, MUTED), -2); gap(parent, 4);
        EditText e = new EditText(this); e.setText(value); e.setTextColor(TEXT); e.setTextSize(16);
        e.setSingleLine(true); e.setSelectAllOnFocus(true); e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(background(0xff0e1921, 10));
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setContentDescription(title); add(parent, e, 44); return e;
    }
    private void equal(LinearLayout r, View v, boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        if (!last) p.setMarginEnd(dp(10)); r.addView(v, p);
    }
    private Profile lastProfile() {
        try { return Profile.from(new JSONObject(prefs.getString("last", "{}"))); }
        catch (Exception e) { return new Profile(); }
    }
    private void showForm() {
        mirroring = false; generation++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(attrs);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        }
        profile = lastProfile();
        LinearLayout root = column(); root.setBackgroundColor(BG); setContentView(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = column(); page.setPadding(dp(16), dp(14), dp(16), dp(12));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        TextView title = label("远程控制", 24, TEXT); title.setTypeface(null, Typeface.BOLD); add(page, title, -2);
        gap(page, 3); add(page, label("SCRCPY  /  ANDROID", 10, ACCENT), -2); gap(page, 12);
        if (errorMessage != null) {
            TextView error = label(errorMessage, 13, 0xffffb6aa); error.setPadding(dp(12), dp(10), dp(12), dp(10));
            error.setBackground(background(0xff37272b, 12)); add(page, error, -2); gap(page, 10); errorMessage = null;
        }
        LinearLayout device = card(page);
        LinearLayout address = row(); LinearLayout hostGroup = column(), portGroup = column();
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1); hp.setMarginEnd(dp(10));
        address.addView(hostGroup, hp); address.addView(portGroup, new LinearLayout.LayoutParams(dp(80), -2));
        host = field(hostGroup, "设备 IP / 主机名", profile.host, false); host.setHint("输入设备地址"); host.setHintTextColor(MUTED);
        port = field(portGroup, "ADB 端口", "" + profile.port, true); add(device, address, -2);
        gap(device, 8); add(device, label("首次连接，请在远端允许调试。", 11, MUTED), -2); gap(page, 10);
        LinearLayout parameters = card(page);
        TextView paramsTitle = label("画面与控制", 15, TEXT); paramsTitle.setTypeface(null, Typeface.BOLD); add(parameters, paramsTitle, -2); gap(parameters, 10);
        LinearLayout presets = row();
        String[] names = {"省流", "均衡", "高清"};
        for (int i = 0; i < names.length; i++) {
            final int preset = i; Button button = button(names[i], false);
            button.setOnClickListener(v -> applyPreset(preset));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1); if (i < 2) lp.setMarginEnd(dp(8)); presets.addView(button, lp);
        }
        add(parameters, presets, -2); gap(parameters, 12);
        LinearLayout numbers = row(); LinearLayout a = column(), b = column(); equal(numbers, a, false); equal(numbers, b, true);
        size = field(a, "最长边 · px", "" + profile.maxSize, true);
        bitrate = field(b, "码率 · Mbps", "" + profile.bitrate, true); add(parameters, numbers, -2); gap(parameters, 10);
        LinearLayout more = row(); LinearLayout c = column(), d = column(); equal(more, c, false); equal(more, d, true);
        fps = field(c, "帧率上限 · FPS", "" + profile.fps, true);
        add(d, label("视频编码", 11, MUTED), -2); gap(d, 4);
        codec = new Spinner(this); codec.setContentDescription("视频编码");
        ArrayAdapter<String> codecs = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"H.264", "H.265"});
        codec.setAdapter(codecs); codec.setSelection(profile.codec.equals("h265") ? 1 : 0);
        codec.setBackground(getDrawable(R.drawable.spinner_background)); codec.setPadding(dp(8), 0, dp(30), 0);
        codec.setPopupBackgroundDrawable(background(CARD, 12)); add(d, codec, 44);
        add(parameters, more, -2); gap(parameters, 8);
        add(parameters, label("最长边 0 = 原始尺寸 · H.264 兼容性更好", 10, MUTED), -2); gap(parameters, 4);
        showNavigation = new Switch(this); showNavigation.setText("展示虚拟按键"); showNavigation.setTextSize(14); showNavigation.setTextColor(TEXT); showNavigation.setChecked(profile.showNavigation); add(parameters, showNavigation, 40);
        viewOnly = new Switch(this); viewOnly.setText("仅观看"); viewOnly.setTextSize(14); viewOnly.setTextColor(TEXT); viewOnly.setChecked(profile.viewOnly); add(parameters, viewOnly, 40);
        stayAwake = new Switch(this); stayAwake.setText("充电时保持远端唤醒"); stayAwake.setTextSize(14); stayAwake.setTextColor(TEXT); stayAwake.setChecked(profile.stayAwake); add(parameters, stayAwake, 40);
        gap(page, 10);
        LinearLayout saved = row(); Button save = button("保存为预设", false), load = button("我的预设", false); equal(saved, save, false); equal(saved, load, true);
        save.setHeight(dp(44)); load.setHeight(dp(44)); add(page, saved, -2);
        save.setOnClickListener(v -> saveProfile()); load.setOnClickListener(v -> loadProfiles());
        LinearLayout dock = column(); dock.setPadding(dp(16), dp(10), dp(16), dp(12));
        Button connect = button("连接设备   →", true); connect.setTextSize(17); add(dock, connect, 52); add(root, dock, -2);
        connect.setOnClickListener(v -> {
            try { Profile p = readForm(); persist(p); showMirror(p); }
            catch (Exception e) { toast(e.getMessage()); }
        });
    }

    private Profile readForm() {
        Profile p = new Profile(); p.name = profile.name; p.host = host.getText().toString().trim();
        try {
            p.port = Integer.parseInt(port.getText().toString()); p.maxSize = Integer.parseInt(size.getText().toString());
            p.bitrate = Integer.parseInt(bitrate.getText().toString()); p.fps = Integer.parseInt(fps.getText().toString());
        } catch (NumberFormatException e) { throw new IllegalArgumentException("请填入完整的数字参数"); }
        p.codec = codec.getSelectedItemPosition() == 1 ? "h265" : "h264";
        p.showNavigation = showNavigation.isChecked(); p.viewOnly = viewOnly.isChecked(); p.stayAwake = stayAwake.isChecked(); p.validate(); return p;
    }
    private void persist(Profile p) {
        try { prefs.edit().putString("last", p.json().toString()).apply(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void applyPreset(int i) {
        int[] sizes = {1024, 1600, 2400}, bits = {2, 6, 12}, rates = {30, 60, 60};
        size.setText("" + sizes[i]); bitrate.setText("" + bits[i]); fps.setText("" + rates[i]); codec.setSelection(0);
        profile.name = new String[]{"省流", "均衡", "高清"}[i]; toast("已应用" + profile.name + "，可继续修改参数");
    }
    private JSONArray savedProfiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void saveProfile() {
        final Profile p;
        try { p = readForm(); } catch (Exception e) { toast(e.getMessage()); return; }
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(p.name); name.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("保存连接预设").setMessage("包含设备地址和当前画面参数；同名预设会更新。")
                .setView(name).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(); if (n.isEmpty()) { name.setError("请输入名称"); return; }
            p.name = n;
            try {
                JSONArray old = savedProfiles(), result = new JSONArray();
                for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).optString("name").equals(n)) result.put(old.getJSONObject(i));
                result.put(p.json()); prefs.edit().putString("profiles", result.toString()).apply(); persist(p); profile = p;
                dialog.dismiss(); toast("已保存「" + n + "」");
            } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
        })); dialog.show();
    }
    private void loadProfiles() {
        JSONArray saved = savedProfiles(); if (saved.length() == 0) { toast("还没有保存的预设"); return; }
        String[] labels = new String[saved.length()];
        for (int i = 0; i < labels.length; i++) {
            Profile p = Profile.from(saved.optJSONObject(i)); labels[i] = p.name + "  ·  " + p.maxSize + "px / " + p.bitrate + "Mbps";
        }
        new AlertDialog.Builder(this).setTitle("我的预设").setItems(labels, (dialog, which) -> {
            Profile p = Profile.from(saved.optJSONObject(which));
            new AlertDialog.Builder(this).setTitle(p.name).setMessage(p.host + ":" + p.port)
                    .setPositiveButton("使用", (d, w) -> { persist(p); showForm(); })
                    .setNeutralButton("删除", (d, w) -> {
                        saved.remove(which); prefs.edit().putString("profiles", saved.toString()).apply(); toast("预设已删除");
                    }).setNegativeButton("取消", null).show();
        }).setNegativeButton("关闭", null).show();
    }

    private void showMirror(Profile p) {
        profile = p; touchGestures.clear(); mirroring = true; final int token = ++generation; videoWidth = videoHeight = 0;
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column(); root.setBackgroundColor(BG); setContentView(root);
        LinearLayout statusBar = row(); statusBar.setPadding(dp(8), 0, dp(4), 0);
        connectionStatus = label("正在连接…", 11, ACCENT); connectionStatus.setSingleLine(true);
        connectionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusBar.addView(connectionStatus, new LinearLayout.LayoutParams(0, -2, 1));
        if (p.showNavigation) {
            String[] titles = {"‹", "○", "≡", "□"}, descriptions = {"Back", "Home", "Menu", "最近任务"};
            int[] codes = {4, 3, 82, 187};
            for (int i = 0; i < titles.length; i++) {
                final int key = codes[i]; Button keyButton = button(titles[i], false);
                keyButton.setTextSize(19); keyButton.setPadding(0, 0, 0, 0);
                keyButton.setContentDescription(descriptions[i]); keyButton.setTooltipText(descriptions[i]);
                keyButton.setEnabled(!p.viewOnly); keyButton.setAlpha(p.viewOnly ? .35f : 1);
                keyButton.setOnClickListener(v -> { if (session != null) session.key(key); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(32)); lp.setMarginEnd(dp(3)); statusBar.addView(keyButton, lp);
            }
        }
        Button exit = button("断开", false); exit.setTextSize(11); exit.setPadding(0, 0, 0, 0);
        statusBar.addView(exit, new LinearLayout.LayoutParams(dp(46), dp(32)));
        exit.setOnClickListener(v -> disconnect(null)); add(root, statusBar, 36);
        videoArea = new FrameLayout(this); videoArea.setBackgroundColor(Color.BLACK);
        root.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1));
        surface = new SurfaceView(this); surface.setContentDescription("远控屏幕");
        videoArea.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> excludeLocalBackGestures());
        surface.setOnTouchListener((v, e) -> touch(e));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, right = 0, top = 0, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets mandatory = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures());
                android.graphics.Insets navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
                android.graphics.Insets gestures = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
                left = Math.max(mandatory.left, navigation.left); right = Math.max(mandatory.right, navigation.right);
                bottom = Math.max(Math.max(mandatory.bottom, navigation.bottom), gestures.bottom);
                top = gestures.top;
            } else {
                left = insets.getStableInsetLeft(); right = insets.getStableInsetRight();
                bottom = insets.getStableInsetBottom();
                top = insets.getStableInsetTop();
            }
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft()); right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            // The status strip protects the video from the top mandatory region;
            // only the bottom safe area is reserved beneath the video.
            root.setPadding(left, 0, right, bottom);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) statusBar.getLayoutParams();
            int barHeight = Math.max(dp(36), top);
            int barLeft = dp(8), barRight = dp(4), barTop = 0;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null && insets.getDisplayCutout().getSafeInsetTop() > 0) {
                int blockedLeft = 0, blockedRight = 0;
                int width = root.getWidth();
                for (Rect hole : insets.getDisplayCutout().getBoundingRects()) {
                    if (hole.top >= insets.getDisplayCutout().getSafeInsetTop()) continue;
                    if (hole.centerX() < width / 2) blockedLeft = Math.max(blockedLeft, hole.right);
                    else blockedRight = Math.max(blockedRight, width - hole.left);
                }
                if (width - left - right - blockedLeft - blockedRight >= dp(p.showNavigation ? 285 : 140)) {
                    barLeft = Math.max(barLeft, blockedLeft + dp(4)); barRight = Math.max(barRight, blockedRight + dp(4));
                } else {
                    barTop = insets.getDisplayCutout().getSafeInsetTop(); barHeight = barTop + dp(36);
                }
            }
            statusBar.setPadding(barLeft, barTop, barRight, 0);
            if (lp.height != barHeight) { lp.height = barHeight; statusBar.setLayoutParams(lp); }
            surface.post(this::excludeLocalBackGestures);
            return insets;
        });
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) view.post(view::requestApplyInsets);
        });
        enterRemoteFullscreen(); root.requestApplyInsets();
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (token != generation || session != null) return;
                session = new ScrcpySession(MainActivity.this, p, holder.getSurface(), new ScrcpySession.Listener() {
                    private void ui(Runnable r) { runOnUiThread(() -> { if (token == generation) r.run(); }); }
                    @Override public void status(String text) { ui(() -> connectionStatus.setText(text)); }
                    @Override public void size(int w, int h) { ui(() -> { videoWidth = w; videoHeight = h; fitVideo(); }); }
                    @Override public void ready(String device) { ui(() -> connectionStatus.setText("● " + (p.viewOnly ? "仅观看" : "已连接") + " · " + device)); }
                    @Override public void error(String message) { ui(() -> disconnect("连接结束：" + message)); }
                }); session.start();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                if (token == generation && session != null) disconnect("画面已关闭，请重新连接");
            }
        });
    }
    private void fitVideo() {
        if (!mirroring || videoWidth == 0 || videoArea.getWidth() == 0 || videoArea.getHeight() == 0) return;
        float scale = Math.min((float) videoArea.getWidth() / videoWidth, (float) videoArea.getHeight() / videoHeight);
        int w = Math.max(1, Math.round(videoWidth * scale)), h = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (old.width != w || old.height != h) surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        surface.post(this::excludeLocalBackGestures);
    }
    private boolean touch(MotionEvent e) {
        if (session == null || profile.viewOnly || videoWidth == 0) return true;
        int action = e.getActionMasked(), index = e.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) android.util.Log.d("RemoteGesture", "CANCEL");
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (action == MotionEvent.ACTION_DOWN) touchGestures.clear();
            EdgeGesture gesture = new EdgeGesture();
            gesture.begin(e.getX(index), e.getY(index), surface.getWidth(), surface.getHeight(), dp(8));
            touchGestures.put(e.getPointerId(index), gesture);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                EdgeGesture gesture = touchGestures.get(e.getPointerId(i));
                if (gesture == null) continue;
                EdgeGesture.Edge edge = gesture.recognize(e.getX(i), e.getY(i), ViewConfiguration.get(this).getScaledTouchSlop());
                if (edge != EdgeGesture.Edge.NONE) android.util.Log.d("RemoteGesture", edge.name());
            }
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) sendTouch(e, i, action == MotionEvent.ACTION_CANCEL ? MotionEvent.ACTION_UP : action);
        } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) sendTouch(e, index, MotionEvent.ACTION_DOWN);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) sendTouch(e, index, MotionEvent.ACTION_UP);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchGestures.clear();
        else if (action == MotionEvent.ACTION_POINTER_UP) touchGestures.remove(e.getPointerId(index));
        return true;
    }
    private void sendTouch(MotionEvent e, int index, int action) {
        EdgeGesture gesture = touchGestures.get(e.getPointerId(index));
        if (gesture == null) return;
        int x = gesture.x(e.getX(index), surface.getWidth(), videoWidth);
        int y = gesture.y(e.getY(index), surface.getHeight(), videoHeight);
        session.touch(action, e.getPointerId(index), x, y, videoWidth, videoHeight, action == MotionEvent.ACTION_UP ? 0 : e.getPressure(index));
    }
    private void enterRemoteFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    private void excludeLocalBackGestures() {
        if (Build.VERSION.SDK_INT >= 29 && mirroring && surface.getWidth() > 0) {
            // Full-height exclusion is permitted while navigation bars are hidden
            // with transient-by-swipe behavior. Mandatory top/bottom zones remain local.
            surface.setSystemGestureExclusionRects(java.util.Collections.singletonList(new Rect(0, 0, surface.getWidth(), surface.getHeight())));
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && mirroring) enterRemoteFullscreen();
    }
    private void disconnect(String error) {
        generation++; mirroring = false; touchGestures.clear();
        ScrcpySession s = session; session = null; if (s != null) s.close();
        errorMessage = error; showForm();
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); if (mirroring) videoArea.post(this::fitVideo); }
    @Override protected void onStop() { super.onStop(); if (mirroring) disconnect("已离开远控界面，连接已断开"); }
    @Override protected void onDestroy() { if (session != null) session.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (mirroring) {
            android.util.Log.d("RemoteGesture", "LOCAL_BACK_FORWARD");
            if (session != null) session.key(4);
        } else super.onBackPressed();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
