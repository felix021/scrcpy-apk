package cn.felix021.scrcpy;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.ImageButton;
import android.widget.ImageView;
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
    private Switch viewOnly, stayAwake, showNavigation, forwardAudio, forwardMic;
    private Profile permissionProfile;
    private Profile profile;
    private ScrcpySession session;
    private boolean mirroring;
    private int generation, videoWidth, videoHeight;
    private FrameLayout videoArea;
    private SurfaceView surface;
    private TextView connectionStatus, configLabel;
    private String errorMessage;
    private RemoteInputView remoteInput;
    private LinearLayout mirrorBody, navigation, mirrorActions;
    private FrameLayout mirrorRoot, dock;
    private SafeRail statusRail, controlRail;
    private final java.util.List<View> navigationKeys = new java.util.ArrayList<>();
    private final java.util.List<View> actionKeys = new java.util.ArrayList<>();
    private boolean landscapeLayout;
    private int safeLeft, safeRight, safeTop, safeBottom;
    private RemoteKeyboard remoteKeyboard;
    private ImageButton keyboardToggle;
    private boolean keyboardVisible;
    private Dialog disconnectDialog, textDialog;

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
        configLabel=label(profile.name.isEmpty() ? "连接配置" : "连接配置 · " + profile.name, 12, MUTED);
        add(device, configLabel, -2); gap(device, 8);
        LinearLayout configs = row();
        Button load = button("切换配置  ▾", false), save = button("保存配置", false);
        equal(configs, load, false); equal(configs, save, true);
        load.setHeight(dp(40)); save.setHeight(dp(40)); add(device, configs, -2); gap(device, 10);
        save.setOnClickListener(v -> saveProfile()); load.setOnClickListener(v -> loadProfiles());
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
        ArrayAdapter<String> codecs = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[]{"H.264", "H.265"}) {
            @Override public View getView(int position,View convert,ViewGroup parent) {
                TextView value=label(getItem(position),16,TEXT);value.setSingleLine(true);value.setGravity(Gravity.CENTER_VERTICAL);
                value.setPadding(dp(4),0,0,0);return value;
            }
            @Override public View getDropDownView(int position,View convert,ViewGroup parent) {
                boolean selected=position==codec.getSelectedItemPosition();
                LinearLayout option=row();option.setPadding(dp(12),0,dp(12),0);
                option.setBackground(background(selected?0xff203e3d:CARD,10));
                option.setLayoutParams(new AbsListView.LayoutParams(-1,dp(48)));
                option.addView(label(getItem(position),15,selected?ACCENT:TEXT),new LinearLayout.LayoutParams(0,-2,1));
                ImageView mark=new ImageView(MainActivity.this);
                mark.setImageResource(selected?R.drawable.ic_check_circle:R.drawable.ic_circle_outline);
                mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(20),dp(20));mp.setMarginStart(dp(8));option.addView(mark,mp);
                return option;
            }
        };
        codec.setAdapter(codecs); codec.setSelection(profile.codec.equals("h265") ? 1 : 0);
        codec.setBackground(getDrawable(R.drawable.spinner_background)); codec.setPadding(dp(8), 0, dp(30), 0);
        codec.setPopupBackgroundDrawable(background(CARD, 12)); add(d, codec, 44);
        add(parameters, more, -2); gap(parameters, 8);
        add(parameters, label("最长边 0 = 原始尺寸 · H.264 兼容性更好", 10, MUTED), -2); gap(parameters, 4);
        showNavigation = new Switch(this); showNavigation.setText("展示虚拟按键"); showNavigation.setTextSize(14); showNavigation.setTextColor(TEXT); showNavigation.setChecked(profile.showNavigation); add(parameters, showNavigation, 40);
        viewOnly = new Switch(this); viewOnly.setText("仅观看"); viewOnly.setTextSize(14); viewOnly.setTextColor(TEXT); viewOnly.setChecked(profile.viewOnly); add(parameters, viewOnly, 40);
        LinearLayout sound=row();
        forwardAudio=new Switch(this);forwardAudio.setText("播放远端声音");forwardAudio.setTextSize(13);forwardAudio.setTextColor(TEXT);forwardAudio.setChecked(profile.forwardAudio);
        forwardMic=new Switch(this);forwardMic.setText("转发本机麦克风");forwardMic.setTextSize(13);forwardMic.setTextColor(TEXT);forwardMic.setChecked(profile.forwardMic);
        equal(sound,forwardAudio,false);equal(sound,forwardMic,true);add(parameters,sound,44);
        forwardMic.setEnabled(!profile.viewOnly);forwardMic.setAlpha(profile.viewOnly?.4f:1);
        viewOnly.setOnCheckedChangeListener((button,watching) -> {forwardMic.setEnabled(!watching);forwardMic.setAlpha(watching?.4f:1);});
        add(parameters,label("麦克风转发会替代远端应用的录音输入；仅观看时关闭",10,MUTED),-2);
        stayAwake = new Switch(this); stayAwake.setText("充电时保持远端唤醒"); stayAwake.setTextSize(14); stayAwake.setTextColor(TEXT); stayAwake.setChecked(profile.stayAwake); add(parameters, stayAwake, 40);
        LinearLayout dock = column(); dock.setPadding(dp(16), dp(10), dp(16), dp(12));
        Button connect = button("连接设备   →", true); connect.setTextSize(17); add(dock, connect, 52); add(root, dock, -2);
        connect.setOnClickListener(v -> {
            try { Profile p = readForm(); persist(p); connectProfile(p); }
            catch (Exception e) { toast(e.getMessage()); }
        });
    }

    private Profile readForm() {
        Profile p = new Profile(); p.name = profile.name; p.host = host.getText().toString().trim();
        try {
            p.port = Integer.parseInt(port.getText().toString()); p.maxSize = Integer.parseInt(size.getText().toString());
            p.bitrate = Integer.parseInt(bitrate.getText().toString()); p.fps = Integer.parseInt(fps.getText().toString());
        } catch (NumberFormatException e) { throw new IllegalArgumentException("请填入完整的数字参数"); }
        if (!p.host.equals(profile.host) || p.port != profile.port) p.name = "";
        p.codec = codec.getSelectedItemPosition() == 1 ? "h265" : "h264";
        p.showNavigation = showNavigation.isChecked(); p.viewOnly = viewOnly.isChecked(); p.stayAwake = stayAwake.isChecked();p.forwardAudio=forwardAudio.isChecked();p.forwardMic=forwardMic.isChecked(); p.validate(); return p;
    }
    private void connectProfile(Profile p) {
        if(p.forwardMic && !p.viewOnly && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionProfile=p;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},41);return;
        }
        showMirror(p);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=41)return;
        Profile pending=permissionProfile;permissionProfile=null;
        if(pending!=null && results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)showMirror(pending);
        else toast("需要录音权限才能转发麦克风，也可关闭该选项后连接");
    }
    private void persist(Profile p) {
        try { prefs.edit().putString("last", p.json().toString()).apply(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void applyPreset(int i) {
        int[] sizes = {1024, 1600, 2400}, bits = {2, 6, 12}, rates = {30, 60, 60};
        size.setText("" + sizes[i]); bitrate.setText("" + bits[i]); fps.setText("" + rates[i]); codec.setSelection(0);
        toast("已应用" + new String[]{"省流", "均衡", "高清"}[i] + "画质，可继续修改参数");
    }
    private JSONArray savedProfiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void saveProfile() {
        final Profile p;
        try { p=readForm(); } catch(Exception e) { toast(e.getMessage());return; }
        LinearLayout body=configPanel("保存连接配置","保存主机、端口、画面参数及全部控制选项。同名配置会更新。");
        EditText name=field(body,"配置名称",p.name.isEmpty()?p.host+":"+p.port:p.name,false);
        name.setInputType(InputType.TYPE_CLASS_TEXT);name.setSingleLine(true);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        name.setHint("例如：工作手机");name.setHintTextColor(MUTED);
        TextView error=inputError(body,name);
        LinearLayout actions=row();Button cancel=button("取消",false),save=button("保存",true);
        configAction(actions,cancel,1,false);configAction(actions,save,1.4f,true);
        Dialog dialog=showConfigDialog(editorPanel(body,actions),dp(260));
        cancel.setOnClickListener(v -> dismissEditorDialog(dialog));
        save.setOnClickListener(v -> {
            String n=name.getText().toString().trim();
            if(n.isEmpty()){showInputError(error,"请输入配置名称");return;}
            p.name=n;
            try {
                JSONArray old=savedProfiles(),result=new JSONArray();
                for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).optString("name").equals(n))result.put(old.getJSONObject(i));
                result.put(p.json());prefs.edit().putString("profiles",result.toString()).apply();persist(p);
                dismissEditorDialog(dialog);showForm();toast("已保存「"+n+"」");
            } catch(Exception e){showInputError(error,"保存失败："+e.getMessage());}
        });
    }
    private LinearLayout editorPanel(LinearLayout body, LinearLayout actions) {
        LinearLayout panel=column();panel.setBackground(background(CARD,22));panel.setClipToOutline(true);
        body.setBackground(null);body.setPadding(dp(20),dp(20),dp(20),dp(16));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        actions.setPadding(dp(20),0,dp(20),dp(20));add(panel,actions,-2);return panel;
    }
    private TextView inputError(LinearLayout body, EditText editor) {
        TextView error=label("",12,0xffffb6aa);error.setPadding(0,dp(8),0,0);error.setVisibility(View.GONE);add(body,error,-2);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { error.setVisibility(View.GONE); }
            @Override public void afterTextChanged(Editable s) { }
        });return error;
    }
    private void showInputError(TextView error, String message) {
        error.setText(message);error.setVisibility(View.VISIBLE);
        error.post(() -> error.requestRectangleOnScreen(new Rect(0,0,error.getWidth(),error.getHeight()),true));
    }
    private void dismissEditorDialog(Dialog dialog) {
        if(dialog==null)return;
        View decor=dialog.getWindow().getDecorView();
        if(decor.getWindowToken()!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(decor.getWindowToken(),0);
        dialog.dismiss();
    }
    private LinearLayout configPanel(String title, String description) {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(20), dp(20), dp(20));
        panel.setBackground(background(CARD, 22));
        TextView heading = label(title, 20, TEXT); heading.setTypeface(null, Typeface.BOLD); add(panel, heading, -2);
        gap(panel, 8); add(panel, label(description, 12, MUTED), -2); gap(panel, 16);
        return panel;
    }
    private final class DialogFrame extends FrameLayout {
        DialogFrame(LinearLayout panel) {
            super(MainActivity.this);addView(panel,new FrameLayout.LayoutParams(-1,-1));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int desired=(Integer)getChildAt(0).getTag();
            if(desired>=0) {
                int available=MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED?desired:MeasureSpec.getSize(heightSpec);
                heightSpec=MeasureSpec.makeMeasureSpec(Math.min(desired,available),MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private Dialog showConfigDialog(LinearLayout panel, int height) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel.setTag(height);
        dialog.setOwnerActivity(this); dialog.setContentView(new DialogFrame(panel)); dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes(); attrs.dimAmount = .68f; window.setAttributes(attrs);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if(dialog.isShowing())resizeConfigDialog(dialog);
        });
        dialog.show(); resizeConfigDialog(dialog); return dialog;
    }
    private void resizeConfigDialog(Dialog dialog) {
        int width=Math.min(dp(440),getResources().getDisplayMetrics().widthPixels-dp(32));
        Window window=dialog.getWindow();WindowManager.LayoutParams attrs=window.getAttributes();
        // WRAP_CONTENT lets Android supply the IME's available frame to the bounded content.
        if(attrs.width!=width || attrs.height!=-2)window.setLayout(width,-2);
    }
    private void configAction(LinearLayout row, Button button, float weight, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(44),weight);
        if (!last) lp.setMarginEnd(dp(8)); row.addView(button,lp);
    }
    private boolean isCurrentConfig(Profile p) {
        return !p.name.isEmpty() && p.name.equals(profile.name) && p.host.equals(profile.host) && p.port == profile.port;
    }
    private void loadProfiles() {
        JSONArray saved = savedProfiles();
        LinearLayout panel = configPanel("切换连接配置", "选择设备后确认，主机、画面与控制设置一并切换。");
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column(); scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); gap(panel,16);
        LinearLayout actions = row();
        Button cancel = button("取消",false), delete = button("删除",false), use = button("使用配置",true);
        delete.setTextColor(0xffffb6aa); delete.setBackground(background(0xff352a31,14));
        configAction(actions,cancel,1,false); configAction(actions,delete,1,false); configAction(actions,use,1.4f,true);
        add(panel,actions,-2);
        Dialog picker = showConfigDialog(panel,dp(168)+Math.max(dp(96),saved.length()*dp(76)));
        final int[] selected = {-1};
        for (int i=0;i<saved.length();i++) if (isCurrentConfig(Profile.from(saved.optJSONObject(i)))) selected[0]=i;
        java.util.ArrayList<LinearLayout> rows = new java.util.ArrayList<>();
        java.util.ArrayList<ImageView> marks = new java.util.ArrayList<>();
        Runnable selection = () -> {
            for (int i=0;i<rows.size();i++) {
                boolean active = i==selected[0];
                GradientDrawable bg = background(active ? 0xff203e3d : 0xff101c25,14);
                bg.setStroke(dp(1),active ? ACCENT : 0xff2b3c47);
                rows.get(i).setBackground(bg); rows.get(i).setSelected(active);
                marks.get(i).setImageResource(active ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            }
            boolean enabled = selected[0]>=0;
            use.setEnabled(enabled); use.setAlpha(enabled ? 1 : .35f);
            delete.setEnabled(enabled); delete.setAlpha(enabled ? 1 : .35f);
        };
        Runnable rebuild = () -> {
            list.removeAllViews(); rows.clear(); marks.clear();
            if (saved.length()==0) {
                TextView empty = label("还没有保存的连接配置\n在主界面填写设备地址后，点击「保存配置」。",13,MUTED);
                empty.setGravity(Gravity.CENTER); empty.setPadding(dp(8),dp(16),dp(8),dp(16)); add(list,empty,-2);
            }
            for (int i=0;i<saved.length();i++) {
                final int index=i; Profile p=Profile.from(saved.optJSONObject(i));
                LinearLayout entry=row(); entry.setPadding(dp(12),dp(12),dp(12),dp(12));
                entry.setContentDescription(p.name+"\n"+p.host+":"+p.port); entry.setFocusable(true); entry.setClickable(true);
                ImageView mark=new ImageView(this); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(22),dp(22));mp.setMarginEnd(dp(12));entry.addView(mark,mp);
                LinearLayout details=column();
                TextView name=label(p.name.isEmpty()?p.host:p.name,15,TEXT);name.setTypeface(null,Typeface.BOLD);
                name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);add(details,name,-2);gap(details,3);
                TextView address=label(p.host+":"+p.port,12,MUTED);address.setSingleLine(true);
                address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);add(details,address,-2);
                entry.addView(details,new LinearLayout.LayoutParams(0,-2,1));
                if (isCurrentConfig(p)) {
                    TextView badge=label("当前",10,ACCENT);badge.setPadding(dp(6),dp(4),dp(6),dp(4));
                    badge.setBackground(background(0xff25453f,6));badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.setMarginStart(dp(8));entry.addView(badge,bp);
                }
                entry.setOnClickListener(v -> {selected[0]=index;selection.run();});
                rows.add(entry); marks.add(mark);add(list,entry,-2);if(i<saved.length()-1)gap(list,8);
            }
            selection.run(); panel.setTag(dp(168)+Math.max(dp(96),saved.length()*dp(76))); resizeConfigDialog(picker);
        };
        cancel.setOnClickListener(v -> picker.dismiss());
        use.setOnClickListener(v -> {
            if (selected[0]<0) return;
            persist(Profile.from(saved.optJSONObject(selected[0]))); picker.dismiss(); showForm();
        });
        delete.setOnClickListener(v -> {
            if (selected[0]<0) return;
            final int index=selected[0]; Profile p=Profile.from(saved.optJSONObject(index));
            LinearLayout confirmPanel=configPanel("删除「"+p.name+"」？",p.host+":"+p.port+"\n将删除这台设备保存的连接设置。");
            LinearLayout confirmActions=row();Button keep=button("取消",false), remove=button("删除配置",false);
            remove.setTextColor(BG);remove.setBackground(background(0xffef9292,14));
            configAction(confirmActions,keep,1,false);configAction(confirmActions,remove,1.4f,true);add(confirmPanel,confirmActions,-2);
            Dialog confirm=showConfigDialog(confirmPanel,-2);
            keep.setOnClickListener(w -> confirm.dismiss());
            remove.setOnClickListener(w -> {
                saved.remove(index);prefs.edit().putString("profiles",saved.toString()).apply();
                if (isCurrentConfig(p)) {
                    profile.name="";configLabel.setText("连接配置");
                    Profile last=lastProfile();
                    if(last.name.equals(p.name) && last.host.equals(p.host) && last.port==p.port){last.name="";persist(last);}
                }
                selected[0]=-1;confirm.dismiss();rebuild.run();toast("连接配置已删除");
            });
        });
        rebuild.run();
        panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if ((r-l!=or-ol || b-t!=ob-ot) && selected[0]>=0 && selected[0]<rows.size()) {
                View chosen=rows.get(selected[0]);
                chosen.requestRectangleOnScreen(new Rect(0,0,chosen.getWidth(),chosen.getHeight()),true);
            }
        });
    }

    private void showMirror(Profile p) {
        profile = p; keyboardVisible = false; mirroring = true; final int token = ++generation; videoWidth = videoHeight = 0;
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        FrameLayout root = new FrameLayout(this); mirrorRoot=root;root.setBackgroundColor(BG);setContentView(root);
        safeLeft=safeRight=safeBottom=0;safeTop=statusBarHeight();landscapeLayout=false;
        navigationKeys.clear();actionKeys.clear();
        mirrorBody = row(); root.addView(mirrorBody,new FrameLayout.LayoutParams(-1,-1));
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
        dock = new FrameLayout(this); dock.setPadding(dp(8),0,dp(8),0);
        root.addView(dock,new FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM));
        statusRail=new SafeRail(this,false,true);controlRail=new SafeRail(this,true,false);
        root.addView(statusRail);root.addView(controlRail);
        statusRail.setVisibility(View.GONE);controlRail.setVisibility(View.GONE);
        connectionStatus=label("正在连接…",9,ACCENT);connectionStatus.setSingleLine(false);connectionStatus.setMaxLines(3);
        connectionStatus.setIncludeFontPadding(false);connectionStatus.setLineSpacing(0,1.05f);
        connectionStatus.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,TypedValue.COMPLEX_UNIT_SP);
        connectionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionStatus.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(connectionStatus,new FrameLayout.LayoutParams(dp(90),-1,Gravity.START|Gravity.CENTER_VERTICAL));
        navigation=row();navigation.setGravity(Gravity.CENTER);
        if(p.showNavigation) {
            int[] icons={R.drawable.ic_back,R.drawable.ic_home,R.drawable.ic_recents}, codes={4,3,187};
            String[] names={"Back","Home","最近任务"};
            for(int i=0;i<icons.length;i++) {
                final int code=codes[i];ImageButton key=iconButton(icons[i],names[i]);
                key.setEnabled(!p.viewOnly);key.setAlpha(p.viewOnly?.35f:1);
                key.setOnClickListener(v -> {if(session!=null)session.key(code);});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));
                if(i<2)lp.setMarginEnd(dp(4));navigation.addView(key,lp);navigationKeys.add(key);
            }
        }
        dock.addView(navigation,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));
        LinearLayout actions=row();mirrorActions=actions;
        keyboardToggle=iconButton(R.drawable.ic_keyboard,"虚拟键盘");keyboardToggle.setEnabled(!p.viewOnly);
        keyboardToggle.setAlpha(p.viewOnly?.35f:1);keyboardToggle.setOnClickListener(v -> showKeyboard(!keyboardVisible));
        LinearLayout.LayoutParams keyboardParams=new LinearLayout.LayoutParams(dp(44),dp(44));keyboardParams.setMarginEnd(dp(4));
        actions.addView(keyboardToggle,keyboardParams);actionKeys.add(keyboardToggle);
        ImageButton exit=iconButton(R.drawable.ic_disconnect,"断开连接");
        actions.addView(exit,new LinearLayout.LayoutParams(dp(44),dp(44)));actionKeys.add(exit);
        exit.setOnClickListener(v -> confirmDisconnect());
        dock.addView(actions,new FrameLayout.LayoutParams(-2,-1,Gravity.END|Gravity.CENTER_VERTICAL));
        dock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if(landscapeLayout)return;
            int dockWidth=r-l,navWidth=navigation.getMeasuredWidth();
            float start=(dockWidth-navWidth)/2f;
            // Compensate asymmetric cutouts so the three keys stay on the window's centerline.
            float shift=(safeRight-safeLeft)/2f;
            shift=Math.max(dock.getPaddingLeft()-start,Math.min(shift,dockWidth-dock.getPaddingRight()-start-navWidth));
            navigation.setTranslationX(shift);
            int leftSpace=Math.max(0,Math.round(start+shift)-dock.getPaddingLeft()-dp(8));
            int rightSpace=Math.max(0,Math.round(dockWidth-dock.getPaddingRight()-start-shift-navWidth)-dp(8));
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)connectionStatus.getLayoutParams();
            if(lp.width!=leftSpace){lp.width=leftSpace;connectionStatus.setLayoutParams(lp);}
            int actionWidth=Math.min(dp(44),Math.max(dp(24),(rightSpace-dp(4))/2));
            for(int i=0;i<actions.getChildCount();i++) {
                LinearLayout.LayoutParams ap=(LinearLayout.LayoutParams)actions.getChildAt(i).getLayoutParams();
                if(ap.width!=actionWidth){ap.width=actionWidth;actions.getChildAt(i).setLayoutParams(ap);}
            }
        });
        videoArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitVideo());
        surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updateInputFrame());
        remoteInput.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> excludeLocalBackGestures());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, right = 0, top = statusBarHeight(), bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets mandatory = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures());
                android.graphics.Insets navInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
                android.graphics.Insets gestures = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
                left = Math.max(mandatory.left, navInsets.left); right = Math.max(mandatory.right, navInsets.right);
                bottom = Math.max(Math.max(mandatory.bottom, navInsets.bottom), gestures.bottom);
                top = Math.max(top, Math.max(gestures.top, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top));
            } else {
                left = insets.getStableInsetLeft(); right = insets.getStableInsetRight();
                bottom = insets.getStableInsetBottom();
                top = Math.max(top, insets.getStableInsetTop());
            }
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft()); right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            safeLeft=left;safeRight=right;safeTop=top;safeBottom=bottom;
            updateMirrorLayout();statusRail.requestLayout();controlRail.requestLayout();
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
                    @Override public void status(String text) { ui(() -> updateConnectionStatus(text)); }
                    @Override public void size(int w, int h) { ui(() -> { videoWidth = w; videoHeight = h; fitVideo(); }); }
                    @Override public void ready(String device) { ui(() -> {updateConnectionStatus("● " + (p.viewOnly ? "仅观看" : "已连接") + "\n" + device);syncOrientation();}); }
                    @Override public void warning(String message) { ui(() -> toast(message)); }
                    @Override public void error(String message) { ui(() -> disconnect("连接结束：" + message)); }
                }); session.start();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                if (token == generation && session != null) disconnect("画面已关闭，请重新连接");
            }
        });
    }
    private void updateConnectionStatus(String text) {
        connectionStatus.setText(text);connectionStatus.setTooltipText(text.replace('\n',' '));
    }
    private void fitVideo() {
        if (!mirroring || videoWidth == 0 || videoArea.getWidth() == 0 || videoArea.getHeight() == 0) return;
        float scale = Math.min((float) videoArea.getWidth() / videoWidth, (float) videoArea.getHeight() / videoHeight);
        int w = Math.max(1, Math.round(videoWidth * scale)), h = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (old.width != w || old.height != h) surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        surface.post(this::updateInputFrame);
    }
    private int statusBarHeight() {
        // Fullscreen may report zero visible status/gesture insets on some systems.
        int resource = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resource == 0 ? dp(24) : Math.max(dp(24), getResources().getDimensionPixelSize(resource));
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
        boolean landscape=isLandscape();
        if(landscapeLayout!=landscape) {
            landscapeLayout=landscape;
            if(landscape) {
                detach(connectionStatus);statusRail.addView(connectionStatus);
                for(View key:navigationKeys){detach(key);controlRail.addView(key);}
                for(View key:actionKeys){detach(key);controlRail.addView(key);}
            } else {
                detach(connectionStatus);dock.addView(connectionStatus,new FrameLayout.LayoutParams(dp(90),-1,Gravity.START|Gravity.CENTER_VERTICAL));
                for(int i=0;i<navigationKeys.size();i++) {
                    View key=navigationKeys.get(i);detach(key);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));
                    if(i<2)lp.setMarginEnd(dp(4));navigation.addView(key,lp);
                }
                for(int i=0;i<actionKeys.size();i++) {
                    View key=actionKeys.get(i);detach(key);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(44),dp(44));
                    if(i==0)lp.setMarginEnd(dp(4));mirrorActions.addView(key,lp);
                }
            }
            dock.setVisibility(landscape?View.GONE:View.VISIBLE);
            statusRail.setVisibility(landscape?View.VISIBLE:View.GONE);controlRail.setVisibility(landscape?View.VISIBLE:View.GONE);
        }
        int left=landscape?Math.max(dp(52),safeLeft+dp(8)):safeLeft;
        int right=landscape?Math.max(dp(56),safeRight+dp(8)):safeRight;
        FrameLayout.LayoutParams body=new FrameLayout.LayoutParams(-1,-1);
        body.setMargins(left,safeTop,right,safeBottom+(landscape?0:dp(48)));
        setFrame(mirrorBody,body);
        FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM);
        bottom.setMargins(safeLeft,0,safeRight,safeBottom);setFrame(dock,bottom);
        // Rails occupy the original status/cutout band. Their contents avoid the
        // actual camera rectangles instead of wasting that whole side's height.
        setFrame(statusRail,new FrameLayout.LayoutParams(left,-1,Gravity.LEFT));
        setFrame(controlRail,new FrameLayout.LayoutParams(right,-1,Gravity.RIGHT));
        boolean side=keyboardVisible && landscape;
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
    private boolean isLandscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    private void detach(View view) { if(view.getParent()!=null)((ViewGroup)view.getParent()).removeView(view); }
    private void setFrame(View view,FrameLayout.LayoutParams next) {
        FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)view.getLayoutParams();
        if(old==null || old.width!=next.width || old.height!=next.height || old.gravity!=next.gravity
                || old.leftMargin!=next.leftMargin || old.topMargin!=next.topMargin
                || old.rightMargin!=next.rightMargin || old.bottomMargin!=next.bottomMargin)view.setLayoutParams(next);
    }
    private void syncOrientation() {
        if(mirroring && session!=null && !profile.viewOnly)session.orientation(isLandscape());
    }
    private void showKeyboard(boolean visible) {
        if(!mirroring || profile.viewOnly)return;
        remoteInput.cancel();keyboardVisible=visible;
        if(!visible)remoteKeyboard.reset();
        remoteKeyboard.setVisibility(visible?View.VISIBLE:View.GONE);keyboardToggle.setSelected(visible);
        keyboardToggle.setBackground(background(visible?0xff34594e:0xff17232d,8));updateMirrorLayout();
    }
    private void inputText() {
        if(!mirroring || profile.viewOnly || session==null || textDialog!=null)return;
        remoteInput.cancel();final int token=generation;final ScrcpySession target=session;
        LinearLayout body=configPanel("输入文字","使用本机输入法编辑，发送到远端。最多 64 KB 文本。");
        add(body,label("文字内容",11,MUTED),-2);gap(body,4);
        EditText editor=new EditText(this);editor.setTextColor(TEXT);editor.setTextSize(16);editor.setHintTextColor(MUTED);
        editor.setPadding(dp(12),dp(10),dp(12),dp(10));editor.setBackground(background(0xff0e1921,10));
        editor.setHint("输入要发送到远端的文字");editor.setContentDescription("发送文本");
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);editor.setGravity(Gravity.TOP);add(body,editor,120);
        TextView error=inputError(body,editor);
        LinearLayout actions=row();Button cancel=button("取消",false),send=button("发送",true);
        configAction(actions,cancel,1,false);configAction(actions,send,1.4f,true);
        Dialog dialog=showConfigDialog(editorPanel(body,actions),dp(320));textDialog=dialog;
        dialog.setOnDismissListener(ignored -> {if(textDialog==dialog)textDialog=null;});
        cancel.setOnClickListener(v -> dismissEditorDialog(dialog));
        send.setOnClickListener(v -> {
            if(!mirroring || generation!=token || session!=target){dismissEditorDialog(dialog);return;}
            try {target.paste(editor.getText().toString());dismissEditorDialog(dialog);}
            catch(IllegalArgumentException e){showInputError(error,e.getMessage());}
        });
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
    private void confirmDisconnect() {
        if (!mirroring || disconnectDialog!=null) return;
        remoteInput.cancel(); final int token=generation;
        LinearLayout panel=configPanel("断开连接？","确认后将结束当前远程控制。");
        LinearLayout actions=row();Button cancel=button("取消",false), confirm=button("确认断开",false);
        confirm.setTextColor(BG);confirm.setBackground(background(0xffef9292,14));
        configAction(actions,cancel,1,false);configAction(actions,confirm,1.4f,true);add(panel,actions,-2);
        Dialog dialog=showConfigDialog(panel,-2);disconnectDialog=dialog;
        dialog.setOnDismissListener(ignored -> {if(disconnectDialog==dialog)disconnectDialog=null;});
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();if(mirroring && generation==token)disconnect(null);
        });
    }
    private void disconnect(String error) {
        Dialog dialog=disconnectDialog;disconnectDialog=null;if(dialog!=null)dialog.dismiss();
        Dialog editor=textDialog;textDialog=null;dismissEditorDialog(editor);
        if(remoteInput!=null)remoteInput.cancel();
        if(remoteKeyboard!=null)remoteKeyboard.reset();
        generation++; mirroring=false;keyboardVisible=false;
        ScrcpySession s = session; session = null; if (s != null) s.closeGracefully();
        errorMessage = error; showForm();
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); if(mirroring){remoteInput.cancel();remoteKeyboard.reset();syncOrientation();mirrorBody.post(() -> {updateMirrorLayout();mirrorRoot.requestApplyInsets();});} }
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
