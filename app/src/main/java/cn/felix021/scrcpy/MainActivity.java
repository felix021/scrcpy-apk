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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.ImageButton;
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
    private RemoteInputView remoteInput;
    private LinearLayout mirrorBody;
    private RemoteKeyboard remoteKeyboard;
    private ImageButton keyboardToggle;
    private boolean keyboardVisible;

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
        profile = p; keyboardVisible = false; mirroring = true; final int token = ++generation; videoWidth = videoHeight = 0;
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column(); root.setBackgroundColor(BG); setContentView(root);
        mirrorBody = row(); root.addView(mirrorBody,new LinearLayout.LayoutParams(-1,0,1));
        videoArea = new FrameLayout(this); videoArea.setBackgroundColor(Color.BLACK);
        mirrorBody.addView(videoArea,new LinearLayout.LayoutParams(-1,0,1));
        surface = new SurfaceView(this); surface.setContentDescription("远控屏幕");
        videoArea.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        remoteInput = new RemoteInputView(this,new RemoteInputView.Listener() {
            @Override public void touch(int action,long id,int x,int y,int w,int h,float pressure) {
                if (session!=null) session.touch(action,id,x,y,w,h,pressure);
            }
            @Override public void navigate(EdgeGesture.Edge edge) {
                if (session==null || p.viewOnly) return;
                if (edge==EdgeGesture.Edge.TOP) session.expandSettings();
                else if (edge==EdgeGesture.Edge.BOTTOM) session.key(3);
                else session.key(4);
            }
        });
        videoArea.addView(remoteInput,new FrameLayout.LayoutParams(-1,-1));
        remoteKeyboard = new RemoteKeyboard(this,new RemoteKeyboard.Listener() {
            @Override public void key(int code,int meta) { if(session!=null && !p.viewOnly)session.key(code,meta); }
            @Override public void text() { inputText(); }
            @Override public void paste() { pasteClipboard(); }
            @Override public void close() { showKeyboard(false); }
        });
        remoteKeyboard.setVisibility(View.GONE);mirrorBody.addView(remoteKeyboard);
        FrameLayout dock = new FrameLayout(this); dock.setPadding(dp(8),0,dp(8),0); add(root,dock,48);
        connectionStatus=label("正在连接…",10,ACCENT);connectionStatus.setSingleLine(true);
        connectionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionStatus.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(connectionStatus,new FrameLayout.LayoutParams(dp(90),-1,Gravity.START|Gravity.CENTER_VERTICAL));
        LinearLayout navigation=row();navigation.setGravity(Gravity.CENTER);
        if(p.showNavigation) {
            int[] icons={R.drawable.ic_back,R.drawable.ic_home,R.drawable.ic_recents}, codes={4,3,187};
            String[] names={"Back","Home","最近任务"};
            for(int i=0;i<icons.length;i++) {
                final int code=codes[i];ImageButton key=iconButton(icons[i],names[i]);
                key.setEnabled(!p.viewOnly);key.setAlpha(p.viewOnly?.35f:1);
                key.setOnClickListener(v -> {if(session!=null)session.key(code);});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));
                if(i<2)lp.setMarginEnd(dp(4));navigation.addView(key,lp);
            }
        }
        dock.addView(navigation,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));
        LinearLayout actions=row();
        keyboardToggle=iconButton(R.drawable.ic_keyboard,"虚拟键盘");keyboardToggle.setEnabled(!p.viewOnly);
        keyboardToggle.setAlpha(p.viewOnly?.35f:1);keyboardToggle.setOnClickListener(v -> showKeyboard(!keyboardVisible));
        actions.addView(keyboardToggle,new LinearLayout.LayoutParams(dp(36),dp(44)));
        Button exit=button("断开",false);exit.setTextSize(11);exit.setPadding(0,0,0,0);
        actions.addView(exit,new LinearLayout.LayoutParams(dp(40),dp(44)));
        exit.setOnClickListener(v -> disconnect(null));
        dock.addView(actions,new FrameLayout.LayoutParams(-2,-1,Gravity.END|Gravity.CENTER_VERTICAL));
        dock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)connectionStatus.getLayoutParams();
            int width=Math.max(dp(30),(r-l-(p.showNavigation?dp(152):0))/2-dp(8));
            if(lp.width!=width){lp.width=width;connectionStatus.setLayoutParams(lp);}
        });
        videoArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitVideo());
        surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updateInputFrame());
        remoteInput.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> excludeLocalBackGestures());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, right = 0, top = 0, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets mandatory = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures());
                android.graphics.Insets navInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
                android.graphics.Insets gestures = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
                left = Math.max(mandatory.left, navInsets.left); right = Math.max(mandatory.right, navInsets.right);
                bottom = Math.max(Math.max(mandatory.bottom, navInsets.bottom), gestures.bottom);
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
            root.setPadding(left,top,right,bottom);
            remoteInput.post(this::excludeLocalBackGestures);
            return insets;
        });
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r-l!=or-ol || b-t!=ob-ot) {view.post(view::requestApplyInsets);view.post(this::updateMirrorLayout);}
        });
        updateMirrorLayout();enterRemoteFullscreen(); root.requestApplyInsets();
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
        surface.post(this::updateInputFrame);
    }
    private ImageButton iconButton(int drawable,String description) {
        ImageButton button=new ImageButton(this);button.setImageResource(drawable);
        button.setContentDescription(description);button.setTooltipText(description);button.setPadding(dp(10),dp(9),dp(10),dp(9));
        button.setBackground(background(0xff17232d,8));return button;
    }
    private void updateInputFrame() {
        if(mirroring && remoteInput!=null)remoteInput.updateFrame(new Rect(surface.getLeft(),surface.getTop(),surface.getRight(),surface.getBottom()),videoWidth,videoHeight,!profile.viewOnly);
    }
    private void updateMirrorLayout() {
        if(!mirroring || mirrorBody==null) return;
        boolean side=keyboardVisible && getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        mirrorBody.setOrientation(side?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        LinearLayout.LayoutParams vp=(LinearLayout.LayoutParams)videoArea.getLayoutParams();
        int w=side?0:-1,h=side?-1:0;
        if(vp.width!=w || vp.height!=h){vp.width=w;vp.height=h;vp.weight=1;videoArea.setLayoutParams(vp);}
        int keyboardWidth=side?Math.min(dp(420),Math.round(mirrorBody.getWidth()*.48f)):-1;
        LinearLayout.LayoutParams kp=(LinearLayout.LayoutParams)remoteKeyboard.getLayoutParams();
        int keyboardHeight=side?-1:-2;
        if(kp.width!=keyboardWidth || kp.height!=keyboardHeight){kp.width=keyboardWidth;kp.height=keyboardHeight;remoteKeyboard.setLayoutParams(kp);}
        remoteKeyboard.setFillHeight(side);videoArea.post(this::fitVideo);
    }
    private void showKeyboard(boolean visible) {
        if(!mirroring || profile.viewOnly)return;
        remoteInput.cancel();keyboardVisible=visible;
        if(!visible)remoteKeyboard.reset();
        remoteKeyboard.setVisibility(visible?View.VISIBLE:View.GONE);keyboardToggle.setSelected(visible);
        keyboardToggle.setBackground(background(visible?0xff34594e:0xff17232d,8));updateMirrorLayout();
    }
    private void inputText() {
        if(!mirroring || profile.viewOnly)return;
        EditText editor=new EditText(this);editor.setTextColor(TEXT);editor.setMinLines(2);editor.setMaxLines(6);
        editor.setHint("输入要发送到远端的文字");editor.setContentDescription("发送文本");
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("输入文字").setView(editor).setNegativeButton("取消",null).setPositiveButton("发送",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if(session!=null)session.paste(editor.getText().toString());
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(),0);
                dialog.dismiss();
            } catch(IllegalArgumentException e){editor.setError(e.getMessage());}
        }));dialog.show();
    }
    private void pasteClipboard() {
        if(!mirroring || session==null || profile.viewOnly)return;
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip=clipboard.getPrimaryClip();
        if(clip==null || clip.getItemCount()==0){toast("剪贴板为空");return;}
        try {session.paste(clip.getItemAt(0).coerceToText(this).toString());}
        catch(IllegalArgumentException e){toast(e.getMessage());}
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
        if (Build.VERSION.SDK_INT >= 29 && mirroring && remoteInput!=null && remoteInput.getWidth() > 0) {
            // Full-height exclusion is permitted while navigation bars are hidden
            // with transient-by-swipe behavior. Mandatory top/bottom zones remain local.
            remoteInput.setSystemGestureExclusionRects(java.util.Collections.singletonList(new Rect(0,0,remoteInput.getWidth(),remoteInput.getHeight())));
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && mirroring) enterRemoteFullscreen();
    }
    private void disconnect(String error) {
        if(remoteInput!=null)remoteInput.cancel();
        if(remoteKeyboard!=null)remoteKeyboard.reset();
        generation++; mirroring=false;keyboardVisible=false;
        ScrcpySession s = session; session = null; if (s != null) s.closeGracefully();
        errorMessage = error; showForm();
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); if(mirroring){remoteInput.cancel();remoteKeyboard.reset();mirrorBody.post(this::updateMirrorLayout);} }
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
