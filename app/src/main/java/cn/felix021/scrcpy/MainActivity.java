package cn.felix021.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0d141b, CARD = 0xff17232d, TEXT = 0xffedf5f7;
    private static final int MUTED = 0xff96aab9, ACCENT = 0xff80e2c3;
    private SharedPreferences prefs;
    private EditText host, port, size, bitrate, fps;
    private Button codec;
    private Switch viewOnly, stayAwake;
    private Profile profile;
    private ScrcpySession session;
    private boolean mirroring;
    private int generation, videoWidth, videoHeight;
    private FrameLayout videoArea;
    private SurfaceView surface;
    private TextView connectionStatus;
    private String errorMessage;

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
        LinearLayout c = column(); c.setBackground(background(CARD, 22)); c.setPadding(dp(20), dp(20), dp(20), dp(20));
        add(parent, c, -2); return c;
    }
    private EditText field(LinearLayout parent, String title, String value, boolean numeric) {
        add(parent, label(title, 12, MUTED), -2); gap(parent, 6);
        EditText e = new EditText(this); e.setText(value); e.setTextColor(TEXT); e.setTextSize(17);
        e.setSingleLine(true); e.setSelectAllOnFocus(true); e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(background(0xff0e1921, 10));
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setContentDescription(title); add(parent, e, 50); return e;
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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        profile = lastProfile();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = column(); page.setPadding(dp(24), dp(24), dp(24), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2)); setContentView(scroll);
        TextView brand = label("●  SCRCPY / ANDROID", 12, ACCENT); brand.setLetterSpacing(.12f); add(page, brand, -2);
        gap(page, 16);
        TextView title = label("远处的屏幕，\n触手可及。", 32, TEXT); title.setTypeface(null, Typeface.BOLD); add(page, title, -2);
        gap(page, 10); add(page, label("通过 EasyTier 网络，连接你的 Android 设备", 13, MUTED), -2); gap(page, 24);
        if (errorMessage != null) {
            TextView error = label(errorMessage, 14, 0xffffb6aa); error.setPadding(dp(16), dp(14), dp(16), dp(14));
            error.setBackground(background(0xff37272b, 14)); add(page, error, -2); gap(page, 16); errorMessage = null;
        }
        LinearLayout device = card(page);
        TextView deviceTitle = label("01  /  连接设备", 17, TEXT); deviceTitle.setTypeface(null, Typeface.BOLD); add(device, deviceTitle, -2);
        gap(device, 18);
        host = field(device, "EasyTier IP / 主机名", profile.host, false); host.setHint("输入设备 IP"); host.setHintTextColor(MUTED);
        gap(device, 14); port = field(device, "ADB 端口", "" + profile.port, true);
        gap(device, 12); add(device, label("设备需开启 ADB 网络调试；首次连接时，请在设备上允许调试。", 12, MUTED), -2);
        gap(page, 16);
        LinearLayout parameters = card(page);
        TextView paramsTitle = label("02  /  画面与控制", 17, TEXT); paramsTitle.setTypeface(null, Typeface.BOLD); add(parameters, paramsTitle, -2); gap(parameters, 16);
        LinearLayout presets = row();
        String[] names = {"省流", "均衡", "高清"};
        for (int i = 0; i < names.length; i++) {
            final int preset = i; Button b = button(names[i], false);
            b.setOnClickListener(v -> applyPreset(preset));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); if (i < 2) p.setMarginEnd(dp(8)); presets.addView(b, p);
        }
        add(parameters, presets, -2); gap(parameters, 18);
        LinearLayout numbers = row(); LinearLayout a = column(), b = column(); equal(numbers, a, false); equal(numbers, b, true);
        size = field(a, "最长边 · px", "" + profile.maxSize, true);
        bitrate = field(b, "码率 · Mbps", "" + profile.bitrate, true); add(parameters, numbers, -2);
        gap(parameters, 14);
        LinearLayout more = row(); LinearLayout c = column(), d = column(); equal(more, c, false); equal(more, d, true);
        fps = field(c, "帧率上限 · FPS", "" + profile.fps, true);
        add(d, label("视频编码", 12, MUTED), -2); gap(d, 6);
        codec = button(profile.codec.toUpperCase(java.util.Locale.ROOT), false); add(d, codec, 50);
        codec.setOnClickListener(v -> codec.setText(codec.getText().toString().equals("H264") ? "H265" : "H264"));
        add(parameters, more, -2); gap(parameters, 12);
        add(parameters, label("最长边填 0 保留原始尺寸；H.264 兼容性更好。", 12, MUTED), -2); gap(parameters, 12);
        viewOnly = new Switch(this); viewOnly.setText("仅观看"); viewOnly.setTextColor(TEXT); viewOnly.setChecked(profile.viewOnly); add(parameters, viewOnly, 48);
        stayAwake = new Switch(this); stayAwake.setText("充电时保持远端唤醒"); stayAwake.setTextColor(TEXT); stayAwake.setChecked(profile.stayAwake); add(parameters, stayAwake, 48);
        gap(page, 16);
        LinearLayout saved = row(); Button save = button("保存为预设", false), load = button("我的预设", false); equal(saved, save, false); equal(saved, load, true);
        save.setHeight(dp(48)); load.setHeight(dp(48)); add(page, saved, -2);
        save.setOnClickListener(v -> saveProfile()); load.setOnClickListener(v -> loadProfiles());
        gap(page, 18); Button connect = button("连接设备   →", true); connect.setTextSize(17); add(page, connect, 58);
        connect.setOnClickListener(v -> {
            try { Profile p = readForm(); persist(p); showMirror(p); }
            catch (Exception e) { toast(e.getMessage()); }
        });
        gap(page, 16); TextView note = label("端到端直连  ·  参数自动记住  ·  Home / Menu / Back 虚拟按键", 11, MUTED); note.setGravity(Gravity.CENTER); add(page, note, -2);
    }

    private Profile readForm() {
        Profile p = new Profile(); p.name = profile.name; p.host = host.getText().toString().trim();
        try {
            p.port = Integer.parseInt(port.getText().toString()); p.maxSize = Integer.parseInt(size.getText().toString());
            p.bitrate = Integer.parseInt(bitrate.getText().toString()); p.fps = Integer.parseInt(fps.getText().toString());
        } catch (NumberFormatException e) { throw new IllegalArgumentException("请填入完整的数字参数"); }
        p.codec = codec.getText().toString().toLowerCase(java.util.Locale.ROOT);
        p.viewOnly = viewOnly.isChecked(); p.stayAwake = stayAwake.isChecked(); p.validate(); return p;
    }
    private void persist(Profile p) {
        try { prefs.edit().putString("last", p.json().toString()).apply(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void applyPreset(int i) {
        int[] sizes = {1024, 1600, 2400}, bits = {2, 6, 12}, rates = {30, 60, 60};
        size.setText("" + sizes[i]); bitrate.setText("" + bits[i]); fps.setText("" + rates[i]); codec.setText("H264");
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
        profile = p; mirroring = true; final int token = ++generation; videoWidth = videoHeight = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column(); root.setBackgroundColor(BG); setContentView(root);
        LinearLayout toolbar = row(); toolbar.setPadding(dp(16), 0, dp(8), 0);
        connectionStatus = label("正在连接…", 13, ACCENT);
        toolbar.addView(connectionStatus, new LinearLayout.LayoutParams(0, -2, 1));
        Button exit = button("断开", false); toolbar.addView(exit, new LinearLayout.LayoutParams(dp(72), dp(36)));
        exit.setOnClickListener(v -> disconnect(null)); add(root, toolbar, 56);
        videoArea = new FrameLayout(this); videoArea.setBackgroundColor(Color.BLACK);
        root.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1));
        surface = new SurfaceView(this); videoArea.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        surface.setOnTouchListener((v, e) -> touch(e));
        LinearLayout nav = row(); nav.setPadding(dp(12), dp(10), dp(12), dp(10));
        String[] titles = {"‹  Back", "○  Home", "≡  Menu", "□  最近"}; int[] codes = {4, 3, 82, 187};
        for (int i = 0; i < titles.length; i++) {
            final int key = codes[i]; Button b = button(titles[i], false); b.setTextSize(12); b.setEnabled(!p.viewOnly);
            b.setAlpha(p.viewOnly ? .35f : 1); b.setOnClickListener(v -> { if (session != null) session.key(key); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1); if (i < 3) lp.setMarginEnd(dp(6)); nav.addView(b, lp);
        }
        add(root, nav, -2);
        if (p.viewOnly) connectionStatus.setText("正在连接 · 仅观看");
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (token != generation || session != null) return;
                session = new ScrcpySession(MainActivity.this, p, holder.getSurface(), new ScrcpySession.Listener() {
                    private void ui(Runnable r) { runOnUiThread(() -> { if (token == generation) r.run(); }); }
                    @Override public void status(String text) { ui(() -> connectionStatus.setText(text)); }
                    @Override public void size(int w, int h) { ui(() -> { videoWidth = w; videoHeight = h; fitVideo(); }); }
                    @Override public void ready(String device) { ui(() -> connectionStatus.setText("● " + device + " · " + (p.viewOnly ? "仅观看" : "已连接"))); }
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
    }
    private boolean touch(MotionEvent e) {
        if (session == null || profile.viewOnly || videoWidth == 0) return true;
        int action = e.getActionMasked(), index = e.getActionIndex();
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) sendTouch(e, i, action == MotionEvent.ACTION_CANCEL ? MotionEvent.ACTION_UP : action);
        } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) sendTouch(e, index, MotionEvent.ACTION_DOWN);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) sendTouch(e, index, MotionEvent.ACTION_UP);
        return true;
    }
    private void sendTouch(MotionEvent e, int index, int action) {
        int x = Math.max(0, Math.min(videoWidth - 1, (int) (e.getX(index) * videoWidth / surface.getWidth())));
        int y = Math.max(0, Math.min(videoHeight - 1, (int) (e.getY(index) * videoHeight / surface.getHeight())));
        session.touch(action, e.getPointerId(index), x, y, videoWidth, videoHeight, action == MotionEvent.ACTION_UP ? 0 : e.getPressure(index));
    }
    private void disconnect(String error) {
        generation++; mirroring = false;
        ScrcpySession s = session; session = null; if (s != null) s.close();
        errorMessage = error; showForm();
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); if (mirroring) videoArea.post(this::fitVideo); }
    @Override protected void onStop() { super.onStop(); if (mirroring) disconnect("已离开远控界面，连接已断开"); }
    @Override protected void onDestroy() { if (session != null) session.close(); super.onDestroy(); }
    @Override public void onBackPressed() { if (mirroring) disconnect(null); else super.onBackPressed(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
