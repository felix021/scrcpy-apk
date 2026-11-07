package cn.felix021.scrcpy;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
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
    private Switch viewOnly, stayAwake, showNavigation, forwardAudio, forwardMic, syncRotation;
    private Profile permissionProfile;
    private UsbManager usbManager;
    private UsbDevice selectedUsb,activeUsb;
    private String connectionType="tcp";
    private LinearLayout networkAddress,usbAddress;
    private TextView networkHint,usbHint;
    private Button transportPicker,usbPicker;
    private Profile pendingUsbProfile;
    private PendingIntent usbPermission;
    private Uri usbPermissionUri;
    private boolean resumed,usbGranted;
    private final BroadcastReceiver usbEvents=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){handleUsbEvent(intent);}
    };
    private boolean permissionForRuntimeMic;
    private int permissionGeneration;
    private Dialog settingsDialog;
    private android.widget.PopupWindow choicePopup;
    private final java.util.Map<String,Switch> runtimeSwitches=new java.util.HashMap<>();
    private boolean updatingRuntime;
    private Button qualityButton;
    private ScrollView formScroll;
    private String connectedDevice;
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
        UiText.init(this,prefs.getString("language","system"));
        usbManager=(UsbManager)getSystemService(USB_SERVICE);
        IntentFilter usbFilter=new IntentFilter(getPackageName()+".USB_PERMISSION");
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // RECEIVER_NOT_EXPORTED is API 33's flag; the overload itself is available on API 26.
        registerUsbReceiver(usbFilter);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        showForm();
        usbAttachedIntent(getIntent());
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @android.annotation.SuppressLint("WrongConstant")
    private void registerUsbReceiver(IntentFilter filter){registerReceiver(usbEvents,filter,Build.VERSION.SDK_INT>=33?4:0);}
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
        cancelUsbPermission();selectedUsb=null;
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
        FrameLayout scrollArea=new FrameLayout(this);root.addView(scrollArea,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll = new ScrollView(this);formScroll=scroll;scroll.setId(R.id.form_scroll);scroll.setFillViewport(false);
        scroll.setScrollbarFadingEnabled(false);scrollArea.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        ScrollHintView hint=new ScrollHintView(this,scroll);hint.setId(R.id.scroll_hint);
        scrollArea.addView(hint,new FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM));
        Runnable updateHint=() -> hint.setVisibility(scroll.canScrollVertically(1)?View.VISIBLE:View.GONE);
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY) -> updateHint.run());
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> scroll.post(updateHint));
        LinearLayout page = column(); page.setPadding(dp(16), dp(14), dp(16), dp(12));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        LinearLayout heading=row();TextView title=label(UiText.text(R.string.remote_control),22,TEXT);title.setTypeface(null,Typeface.BOLD);title.setSingleLine(true);
        heading.addView(title,new LinearLayout.LayoutParams(-2,-2));
        TextView brand=label("SCRCPY / ANDROID",8,ACCENT);brand.setSingleLine(true);brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(0,-1,1);brandParams.setMarginStart(dp(8));heading.addView(brand,brandParams);
        Button language=button(UiText.text(R.string.en),false);language.setId(R.id.language_picker);language.setTextSize(11);language.setPadding(dp(4),0,dp(4),0);
        language.setContentDescription(UiText.text(R.string.interface_language));heading.addView(language,new LinearLayout.LayoutParams(dp(52),dp(32)));
        language.setOnClickListener(v -> languagePicker(language));add(page,heading,34);gap(page,12);
        if (errorMessage != null) {
            TextView error = label(errorMessage, 13, 0xffffb6aa); error.setPadding(dp(12), dp(10), dp(12), dp(10));
            error.setBackground(background(0xff37272b, 12)); add(page, error, -2); gap(page, 10); errorMessage = null;
        }
        LinearLayout device = card(page);
        configLabel=label(profile.name.isEmpty() ? UiText.text(R.string.connection_profile) : UiText.text(R.string.profile) + profile.name, 12, MUTED);
        add(device, configLabel, -2); gap(device, 8);
        LinearLayout configs = row();
        Button load = button(UiText.text(R.string.switch_profile), false), save = button(UiText.text(R.string.save_profile), false);
        equal(configs, load, false); equal(configs, save, true);
        load.setHeight(dp(40)); save.setHeight(dp(40)); add(device, configs, -2); gap(device, 10);
        save.setOnClickListener(v -> saveProfile()); load.setOnClickListener(v -> loadProfiles());
        connectionType=profile.transport;
        LinearLayout transportRow=row();transportRow.addView(label(UiText.text(R.string.connection_method),12,MUTED),new LinearLayout.LayoutParams(0,-2,1));
        transportPicker=button("",false);transportPicker.setId(R.id.transport_picker);transportPicker.setTextSize(12);
        transportRow.addView(transportPicker,new LinearLayout.LayoutParams(-2,dp(36)));add(device,transportRow,36);gap(device,8);
        transportPicker.setOnClickListener(v -> choiceMenu(transportPicker,new String[]{UiText.text(R.string.network_adb),UiText.text(R.string.usb_adb)},connectionType.equals("usb")?1:0,index -> {
            cancelUsbPermission();connectionType=index==1?"usb":"tcp";updateTransportUi();
        }));
        LinearLayout address = row(); LinearLayout hostGroup = column(), portGroup = column();
        networkAddress=address;
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1); hp.setMarginEnd(dp(10));
        address.addView(hostGroup, hp); address.addView(portGroup, new LinearLayout.LayoutParams(dp(80), -2));
        host = field(hostGroup, UiText.text(R.string.device_ip_hostname), profile.host, false); host.setHint(UiText.text(R.string.enter_device_address)); host.setHintTextColor(MUTED);
        port = field(portGroup, UiText.text(R.string.adb_port), "" + profile.port, true); add(device, address, -2);
        gap(device, 8);networkHint=label(UiText.text(R.string.allow_usb_debugging_on_the_remote_device_when_connecting_for_the),11,MUTED);add(device,networkHint,-2);
        usbAddress=column();usbPicker=button("",false);usbPicker.setId(R.id.usb_device);usbPicker.setTextSize(14);usbPicker.setSingleLine(true);usbPicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(usbAddress,usbPicker,44);gap(usbAddress,8);usbHint=label("",11,MUTED);add(usbAddress,usbHint,-2);add(device,usbAddress,-2);
        usbPicker.setOnClickListener(v -> chooseUsbDevice());updateTransportUi();gap(page, 10);
        LinearLayout parameters = card(page);
        LinearLayout parameterHeading=row();
        TextView paramsTitle=label(UiText.text(R.string.video_controls),15,TEXT);paramsTitle.setTypeface(null,Typeface.BOLD);
        parameterHeading.addView(paramsTitle,new LinearLayout.LayoutParams(0,-2,1));
        qualityButton=button(UiText.text(R.string.balanced),false);qualityButton.setId(R.id.quality_picker);qualityButton.setContentDescription(UiText.text(R.string.video_quality));qualityButton.setTextSize(12);
        parameterHeading.addView(qualityButton,new LinearLayout.LayoutParams(dp(116),dp(34)));
        qualityButton.setOnClickListener(v -> choiceMenu(qualityButton,qualityNames(),currentQuality(),index -> {if(index<3)applyPreset(index);}));
        add(parameters,parameterHeading,34);gap(parameters,10);
        LinearLayout numbers = row(); LinearLayout a = column(), b = column(); equal(numbers, a, false); equal(numbers, b, true);
        size = field(a, UiText.text(R.string.max_edge_px), "" + profile.maxSize, true);
        bitrate = field(b, UiText.text(R.string.bit_rate_mbps), "" + profile.bitrate, true); add(parameters, numbers, -2); gap(parameters, 10);
        LinearLayout more = row(); LinearLayout c = column(), d = column(); equal(more, c, false); equal(more, d, true);
        fps = field(c, UiText.text(R.string.frame_limit_fps), "" + profile.fps, true);
        add(d, label(UiText.text(R.string.video_codec), 11, MUTED), -2); gap(d, 4);
        codec = new Spinner(this); codec.setContentDescription(UiText.text(R.string.video_codec));
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
        add(parameters, label(UiText.text(R.string.max_edge_0_original_size_h_264_offers_better_compatibility), 10, MUTED), -2); gap(parameters, 4);
        showNavigation = new Switch(this); showNavigation.setText(UiText.text(R.string.show_navigation_keys)); showNavigation.setTextSize(14); showNavigation.setTextColor(TEXT); showNavigation.setChecked(profile.showNavigation); add(parameters, showNavigation, 40);
        viewOnly = new Switch(this); viewOnly.setText(UiText.text(R.string.view_only)); viewOnly.setTextSize(14); viewOnly.setTextColor(TEXT); viewOnly.setChecked(profile.viewOnly); add(parameters, viewOnly, 40);
        LinearLayout sound=row();
        forwardAudio=new Switch(this);forwardAudio.setText(UiText.text(R.string.play_remote_audio));forwardAudio.setTextSize(13);forwardAudio.setTextColor(TEXT);forwardAudio.setChecked(profile.forwardAudio);
        forwardMic=new Switch(this);forwardMic.setText(UiText.text(R.string.forward_local_microphone));forwardMic.setTextSize(13);forwardMic.setTextColor(TEXT);forwardMic.setChecked(profile.forwardMic);
        equal(sound,forwardAudio,false);equal(sound,forwardMic,true);add(parameters,sound,44);
        forwardMic.setEnabled(!profile.viewOnly);forwardMic.setAlpha(profile.viewOnly?.4f:1);
        viewOnly.setOnCheckedChangeListener((button,watching) -> {forwardMic.setEnabled(!watching);forwardMic.setAlpha(watching?.4f:1);});
        add(parameters,label(UiText.text(R.string.replaces_recording_input_on_the_remote_device_disabled_in_view_o),10,MUTED),-2);
        syncRotation=new Switch(this);syncRotation.setText(UiText.text(R.string.sync_remote_orientation));syncRotation.setTextSize(14);syncRotation.setTextColor(TEXT);syncRotation.setChecked(profile.syncRotation);add(parameters,syncRotation,40);
        stayAwake = new Switch(this); stayAwake.setText(UiText.text(R.string.keep_remote_awake_while_charging)); stayAwake.setTextSize(14); stayAwake.setTextColor(TEXT); stayAwake.setChecked(profile.stayAwake); add(parameters, stayAwake, 40);
        LinearLayout dock = column(); dock.setPadding(dp(16), dp(10), dp(16), dp(12));
        Button connect = button(UiText.text(R.string.connect), true); connect.setTextSize(17); add(dock, connect, 52); add(root, dock, -2);
        TextWatcher qualityWatcher=new TextWatcher(){
            public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            public void onTextChanged(CharSequence t,int start,int before,int count){updateQuality();}
            public void afterTextChanged(Editable text){}
        };
        size.addTextChangedListener(qualityWatcher);bitrate.addTextChangedListener(qualityWatcher);fps.addTextChangedListener(qualityWatcher);
        codec.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){updateQuality();}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });updateQuality();scroll.post(updateHint);
        connect.setOnClickListener(v -> {
            try { Profile p = readForm(); persist(p); connectProfile(p); }
            catch (Exception e) { toast(UiText.error(e)); }
        });
    }

    private String[] qualityNames(){return new String[]{UiText.text(R.string.economy),UiText.text(R.string.balanced),UiText.text(R.string.high_quality),UiText.text(R.string.custom)};}
    private void updateTransportUi(){
        boolean usb=connectionType.equals("usb");
        transportPicker.setText(UiText.text(usb?R.string.usb_adb:R.string.network_adb)+"  ▾");
        networkAddress.setVisibility(usb?View.GONE:View.VISIBLE);networkHint.setVisibility(usb?View.GONE:View.VISIBLE);usbAddress.setVisibility(usb?View.VISIBLE:View.GONE);
        java.util.List<UsbDevice> devices=UsbAdb.devices(usbManager);
        if(selectedUsb!=null && devices.stream().noneMatch(d -> d.getDeviceName().equals(selectedUsb.getDeviceName())))selectedUsb=null;
        if(selectedUsb==null){
            java.util.List<UsbDevice> matching=new java.util.ArrayList<>();
            for(UsbDevice d:devices)if(d.getVendorId()==profile.usbVendor && d.getProductId()==profile.usbProduct
                    && (profile.usbSerial.isEmpty() || profile.usbSerial.equals(UsbAdb.serial(usbManager,d))))matching.add(d);
            if(matching.size()==1)selectedUsb=matching.get(0);
            else if(devices.size()==1)selectedUsb=devices.get(0);
        }
        usbPicker.setText((selectedUsb==null?UiText.text(R.string.choose_usb_device):UsbAdb.label(selectedUsb))+"  ▾");
        usbHint.setText(UiText.text(getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)?R.string.usb_instructions:R.string.usb_host_unavailable));
    }
    private void chooseUsbDevice(){
        updateTransportUi();java.util.List<UsbDevice> devices=UsbAdb.devices(usbManager);
        if(devices.isEmpty()){toast(UiText.text(R.string.usb_no_devices));return;}
        String[] labels=new String[devices.size()];int selected=-1;
        for(int i=0;i<devices.size();i++){
            UsbDevice d=devices.get(i);String serial=UsbAdb.serial(usbManager,d);
            labels[i]=UsbAdb.label(d)+(serial.isEmpty()?(devices.size()>1?" · #"+(i+1):""):" · "+serial);
            if(selectedUsb!=null && d.getDeviceName().equals(selectedUsb.getDeviceName()))selected=i;
        }
        choiceMenu(usbPicker,labels,selected,index -> {cancelUsbPermission();selectedUsb=devices.get(index);updateTransportUi();});
    }
    private void usbAttachedIntent(Intent intent){
        if(mirroring || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()))return;
        Object value=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(value instanceof UsbDevice && UsbAdb.adbInterface((UsbDevice)value)!=null){
            cancelUsbPermission();connectionType="usb";selectedUsb=(UsbDevice)value;updateTransportUi();
        }
    }
    private void handleUsbEvent(Intent intent){
        Object value=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        UsbDevice device=value instanceof UsbDevice?(UsbDevice)value:null;
        if((getPackageName()+".USB_PERMISSION").equals(intent.getAction())){
            if(pendingUsbProfile==null || usbPermissionUri==null || !usbPermissionUri.equals(intent.getData()))return;
            if(device!=null && device.getDeviceName().equals(pendingUsbProfile.usbDeviceName)
                    && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usbManager.hasPermission(device)){
                usbGranted=true;completeUsbPermission();
            }else{cancelUsbPermission();toast(UiText.text(R.string.usb_permission_denied));}
            return;
        }
        if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && device!=null){
            if(pendingUsbProfile!=null && device.getDeviceName().equals(pendingUsbProfile.usbDeviceName))cancelUsbPermission();
            if(activeUsb!=null && device.getDeviceName().equals(activeUsb.getDeviceName()))disconnect(UiText.text(R.string.usb_disconnected));
        }
        if(!mirroring && usbPicker!=null)updateTransportUi();
    }
    private void completeUsbPermission(){
        if(!resumed || !usbGranted || pendingUsbProfile==null)return;
        Profile p=pendingUsbProfile;UsbDevice device=usbManager==null?null:usbManager.getDeviceList().get(p.usbDeviceName);cancelUsbPermission();
        if(device==null || !usbManager.hasPermission(device)){toast(UiText.text(R.string.usb_not_connected));return;}
        usbReady(p,device);
    }
    private void usbReady(Profile p,UsbDevice device){
        String serial=UsbAdb.serial(usbManager,device);
        if(!p.name.isEmpty() && profile.isUsb() && !profile.usbSerial.isEmpty() && !serial.isEmpty() && !serial.equals(profile.usbSerial))p.name="";
        p.usbSerial=serial;p.usbLabel=UsbAdb.label(device);p.usbVendor=device.getVendorId();p.usbProduct=device.getProductId();p.usbDeviceName=device.getDeviceName();
        selectedUsb=device;persist(p);showMirror(p);
    }
    private void cancelUsbPermission(){
        if(usbPermission!=null)usbPermission.cancel();usbPermission=null;usbPermissionUri=null;pendingUsbProfile=null;usbGranted=false;
    }
    private int currentQuality(){
        if(size==null || bitrate==null || fps==null || codec==null)return 3;
        int[] sizes={1024,1600,2400},bits={2,6,12},rates={30,60,60};
        for(int i=0;i<3;i++)if(size.getText().toString().equals(""+sizes[i]) && bitrate.getText().toString().equals(""+bits[i])
                && fps.getText().toString().equals(""+rates[i]) && codec.getSelectedItemPosition()==0)return i;
        return 3;
    }
    private void updateQuality(){if(qualityButton!=null)qualityButton.setText(qualityNames()[currentQuality()]+"  ▾");}
    private void choiceMenu(View anchor,String[] names,int selected,java.util.function.IntConsumer select){
        if(choicePopup!=null)choicePopup.dismiss();
        LinearLayout menu=column();menu.setPadding(dp(6),dp(6),dp(6),dp(6));menu.setBackground(background(CARD,14));
        android.widget.PopupWindow popup=new android.widget.PopupWindow(menu,Math.min(dp(260),getResources().getDisplayMetrics().widthPixels-dp(32)),-2,true);
        popup.setBackgroundDrawable(background(CARD,14));popup.setElevation(dp(14));popup.setOutsideTouchable(true);
        choicePopup=popup;popup.setOnDismissListener(() -> {if(choicePopup==popup)choicePopup=null;});
        for(int i=0;i<names.length;i++){
            final int index=i;boolean active=i==selected;
            LinearLayout option=row();option.setPadding(dp(12),0,dp(12),0);option.setBackground(background(active?0xff203e3d:CARD,10));
            option.setSelected(active);option.setContentDescription(names[i]);option.setFocusable(true);
            option.addView(label(names[i],14,active?ACCENT:TEXT),new LinearLayout.LayoutParams(0,-2,1));
            ImageView mark=new ImageView(this);mark.setImageResource(active?R.drawable.ic_check_circle:R.drawable.ic_circle_outline);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);option.addView(mark,new LinearLayout.LayoutParams(dp(20),dp(20)));
            add(menu,option,46);option.setOnClickListener(v -> {popup.dismiss();select.accept(index);});
        }
        popup.showAsDropDown(anchor,0,dp(5),Gravity.END);
    }
    private String[] languageNames(){return new String[]{UiText.text(R.string.follow_system),UiText.text(R.string.language_label),"English"};}
    private void languagePicker(View anchor){
        String[] options={"system","zh","en"};int selected=java.util.Arrays.asList(options).indexOf(UiText.language());
        choiceMenu(anchor,languageNames(),selected,index -> {
            prefs.edit().putString("language",options[index]).apply();UiText.setLanguage(options[index]);
            if(mirroring)refreshMirrorLanguage();else refreshFormLanguage();
        });
    }
    private void refreshFormLanguage(){
        String method=connectionType;UsbDevice selection=selectedUsb;
        String[] values={host.getText().toString(),port.getText().toString(),size.getText().toString(),bitrate.getText().toString(),fps.getText().toString()};
        boolean[] flags={showNavigation.isChecked(),viewOnly.isChecked(),forwardAudio.isChecked(),forwardMic.isChecked(),syncRotation.isChecked(),stayAwake.isChecked()};
        int encoding=codec.getSelectedItemPosition(),scrollY=formScroll.getScrollY();
        showForm();EditText[] fields={host,port,size,bitrate,fps};for(int i=0;i<fields.length;i++)fields[i].setText(values[i]);
        showNavigation.setChecked(flags[0]);viewOnly.setChecked(flags[1]);forwardAudio.setChecked(flags[2]);forwardMic.setChecked(flags[3]);syncRotation.setChecked(flags[4]);stayAwake.setChecked(flags[5]);codec.setSelection(encoding);
        connectionType=method;selectedUsb=selection;updateTransportUi();
        formScroll.post(() -> formScroll.scrollTo(0,scrollY));updateQuality();
    }
    private void refreshMirrorLanguage(){
        surface.setContentDescription(UiText.text(R.string.remote_screen));remoteInput.setContentDescription(UiText.text(R.string.remote_touch_area));
        statusRail.setContentDescription(UiText.text(R.string.landscape_connection_status));controlRail.setContentDescription(UiText.text(R.string.landscape_controls));
        navigationKeys.get(2).setContentDescription(UiText.text(R.string.recent_apps));keyboardToggle.setContentDescription(UiText.text(R.string.virtual_keyboard));actionKeys.get(1).setContentDescription(UiText.text(R.string.remote_settings));
        navigationKeys.get(2).setTooltipText(navigationKeys.get(2).getContentDescription());
        keyboardToggle.setTooltipText(keyboardToggle.getContentDescription());actionKeys.get(1).setTooltipText(actionKeys.get(1).getContentDescription());
        remoteKeyboard.refreshLabels();
        updateConnectionStatus(connectedDevice==null?UiText.text(R.string.connecting):"● "+(profile.viewOnly?UiText.text(R.string.view_only):UiText.text(R.string.connected))+"\n"+connectedDevice);
        if(settingsDialog!=null && settingsDialog.isShowing()){
            Dialog previous=settingsDialog;settingsDialog=null;previous.dismiss();showRuntimeSettings();
        }
    }
    private void showRuntimeSettings(){
        if(!mirroring || settingsDialog!=null)return;
        remoteInput.cancel();LinearLayout body=configPanel(UiText.text(R.string.remote_settings),UiText.text(R.string.changes_take_effect_immediately_and_are_remembered_for_this_conn));
        LinearLayout rotations=row();
        Button left=button(UiText.text(R.string.rotate_left),false),right=button(UiText.text(R.string.rotate_right),false);
        left.setId(R.id.rotate_left);right.setId(R.id.rotate_right);
        equal(rotations,left,false);equal(rotations,right,true);add(body,rotations,42);gap(body,8);
        for(Button rotation:new Button[]{left,right}){rotation.setHeight(dp(42));rotation.setEnabled(!profile.viewOnly);rotation.setAlpha(profile.viewOnly?.4f:1);}
        left.setOnClickListener(v -> {if(session!=null)session.rotate(false);});
        right.setOnClickListener(v -> {if(session!=null)session.rotate(true);});
        add(body,label(UiText.text(R.string.manual_rotation_hint),11,MUTED),-2);gap(body,10);
        runtimeSwitches.clear();
        runtimeSwitch(body,"showNavigation",UiText.text(R.string.show_navigation_keys),true);
        runtimeSwitch(body,"forwardAudio",UiText.text(R.string.play_remote_audio),true);
        runtimeSwitch(body,"forwardMic",UiText.text(R.string.forward_local_microphone),!profile.viewOnly);
        runtimeSwitch(body,"stayAwake",UiText.text(R.string.keep_remote_awake_while_charging),true);
        runtimeSwitch(body,"syncRotation",UiText.text(R.string.sync_remote_orientation),!profile.viewOnly);
        add(body,label(UiText.text(R.string.turning_off_orientation_sync_or_keep_awake_restores_the_remote_d),11,MUTED),-2);gap(body,6);
        runtimeSwitch(body,"keyboard",UiText.text(R.string.show_virtual_keyboard),!profile.viewOnly);
        LinearLayout language=row();language.addView(label(UiText.text(R.string.interface_language),14,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        String[] options={"system","zh","en"};int selected=Math.max(0,java.util.Arrays.asList(options).indexOf(UiText.language()));
        Button picker=button(languageNames()[selected]+"  ▾",false);picker.setId(R.id.language_picker);picker.setTextSize(12);
        language.addView(picker,new LinearLayout.LayoutParams(-2,dp(40)));picker.setOnClickListener(v -> languagePicker(picker));add(body,language,48);gap(body,8);
        add(body,label(profile.viewOnly?UiText.text(R.string.view_only_mode_does_not_send_keys_microphone_input_or_orientatio):UiText.text(R.string.disconnect_to_change_the_address_video_quality_or_view_only_mode),11,MUTED),-2);
        LinearLayout actions=row();Button disconnect=button(UiText.text(R.string.disconnect),false),done=button(UiText.text(R.string.done),true);
        disconnect.setTextColor(0xffffb6aa);disconnect.setBackground(background(0xff352a31,14));
        disconnect.setId(R.id.runtime_disconnect);done.setId(R.id.runtime_done);
        configAction(actions,disconnect,1.2f,false);configAction(actions,done,1,true);
        LinearLayout content=editorPanel(body,actions);content.setBackground(background(0xc817232d,22));
        ScrollView settingScroll=(ScrollView)content.getChildAt(0);
        settingScroll.setId(R.id.runtime_scroll);settingScroll.setScrollbarFadingEnabled(false);settingScroll.setVerticalFadingEdgeEnabled(true);settingScroll.setFadingEdgeLength(dp(16));
        Dialog dialog=showConfigDialog(content,dp(610));settingsDialog=dialog;
        WindowManager.LayoutParams window=dialog.getWindow().getAttributes();window.dimAmount=.08f;dialog.getWindow().setAttributes(window);
        dialog.setOnDismissListener(ignored -> {if(settingsDialog==dialog){settingsDialog=null;runtimeSwitches.clear();}});
        disconnect.setOnClickListener(v -> confirmDisconnect());done.setOnClickListener(v -> dialog.dismiss());updateRuntimeSwitches();
    }
    private void runtimeSwitch(LinearLayout parent,String key,String title,boolean enabled){
        Switch toggle=new Switch(this);toggle.setText(title);toggle.setTextSize(14);toggle.setTextColor(TEXT);toggle.setEnabled(enabled);toggle.setAlpha(enabled?1:.4f);
        int id=getResources().getIdentifier("option_"+key,"id",getPackageName());if(id!=0)toggle.setId(id);
        add(parent,toggle,44);runtimeSwitches.put(key,toggle);
        toggle.setOnCheckedChangeListener((button,checked) -> {
            if(updatingRuntime)return;
            if(key.equals("forwardMic") && checked && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                updateRuntimeSwitches();permissionForRuntimeMic=true;permissionGeneration=generation;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},41);return;
            }
            applyRuntime(key,checked);
        });
    }
    private void applyRuntime(String key,boolean value){
        if(!mirroring || session==null)return;
        switch(key){
            case "showNavigation":profile.showNavigation=value;refreshNavigation();break;
            case "forwardAudio":profile.forwardAudio=value;session.playback(value);break;
            case "forwardMic":profile.forwardMic=value;session.microphone(value);break;
            case "stayAwake":profile.stayAwake=value;session.stayAwake(value);break;
            case "syncRotation":profile.syncRotation=value;session.syncRotation(value);syncOrientation();break;
            case "keyboard":showKeyboard(value);break;
            default:return;
        }
        persistRuntime();updateRuntimeSwitches();
    }
    private void updateRuntimeSwitches(){
        updatingRuntime=true;
        try{
            for(java.util.Map.Entry<String,Switch> entry:runtimeSwitches.entrySet()){
                boolean value=false;
                switch(entry.getKey()){
                    case "showNavigation":value=profile.showNavigation;break;
                    case "forwardAudio":value=profile.forwardAudio;break;
                    case "forwardMic":value=profile.forwardMic;break;
                    case "stayAwake":value=profile.stayAwake;break;
                    case "syncRotation":value=profile.syncRotation;break;
                    case "keyboard":value=keyboardVisible;break;
                }
                entry.getValue().setChecked(value);
            }
        }finally{updatingRuntime=false;}
    }
    private void refreshNavigation(){
        remoteInput.cancel();for(View key:navigationKeys)detach(key);
        if(profile.showNavigation)for(int i=0;i<navigationKeys.size();i++){
            View key=navigationKeys.get(i);
            if(landscapeLayout)controlRail.addView(key,i);
            else {LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));if(i<2)lp.setMarginEnd(dp(4));navigation.addView(key,lp);}
        }
        controlRail.requestLayout();navigation.requestLayout();dock.requestLayout();
    }
    private void persistRuntime(){
        persist(profile);if(profile.name.isEmpty())return;
        try{
            JSONArray saved=savedProfiles();JSONObject current=profile.json();
            for(int i=0;i<saved.length();i++){
                JSONObject item=saved.getJSONObject(i);
                if(item.optString("name").equals(profile.name) && Profile.from(item).sameEndpoint(profile)){
                    for(String key:new String[]{"showNavigation","forwardAudio","forwardMic","stayAwake","syncRotation"})item.put(key,current.get(key));
                }
            }
            prefs.edit().putString("profiles",saved.toString()).apply();
        }catch(Exception e){toast(UiText.text(R.string.could_not_save_session_settings));}
    }

    private Profile readForm() {
        Profile p = new Profile(); p.name = profile.name; p.host = host.getText().toString().trim();
        p.transport=connectionType;
        if(p.isUsb() && selectedUsb!=null){
            p.usbDeviceName=selectedUsb.getDeviceName();p.usbLabel=UsbAdb.label(selectedUsb);p.usbVendor=selectedUsb.getVendorId();p.usbProduct=selectedUsb.getProductId();p.usbSerial=UsbAdb.serial(usbManager,selectedUsb);
        }
        try {
            p.port = p.isUsb()?profile.port:Integer.parseInt(port.getText().toString()); p.maxSize = Integer.parseInt(size.getText().toString());
            p.bitrate = Integer.parseInt(bitrate.getText().toString()); p.fps = Integer.parseInt(fps.getText().toString());
        } catch (NumberFormatException e) { throw new IllegalArgumentException(UiText.text(R.string.enter_valid_numbers_in_all_numeric_fields)); }
        if (!p.sameEndpoint(profile)) p.name = "";
        p.codec = codec.getSelectedItemPosition() == 1 ? "h265" : "h264";
        p.showNavigation = showNavigation.isChecked(); p.viewOnly = viewOnly.isChecked(); p.stayAwake = stayAwake.isChecked();p.forwardAudio=forwardAudio.isChecked();p.forwardMic=forwardMic.isChecked();p.syncRotation=syncRotation.isChecked(); p.validate(); return p;
    }
    private void connectProfile(Profile p) {
        UsbDevice device=p.isUsb() && usbManager!=null?usbManager.getDeviceList().get(p.usbDeviceName):null;
        if(p.isUsb() && (device==null || UsbAdb.adbInterface(device)==null)){toast(UiText.text(R.string.usb_not_connected));return;}
        if(p.forwardMic && !p.viewOnly && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionProfile=p;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},41);return;
        }
        if(!p.isUsb()){showMirror(p);return;}
        if(usbManager.hasPermission(device)){usbReady(p,device);return;}
        cancelUsbPermission();pendingUsbProfile=p;usbGranted=false;
        usbPermissionUri=Uri.parse("scrcpy-usb://permission/"+java.util.UUID.randomUUID());
        Intent grant=new Intent(getPackageName()+".USB_PERMISSION").setPackage(getPackageName()).setData(usbPermissionUri);
        usbPermission=PendingIntent.getBroadcast(this,0,grant,Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        try{usbManager.requestPermission(device,usbPermission);}catch(RuntimeException e){cancelUsbPermission();toast(UiText.text(R.string.usb_permission_denied));}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=41)return;
        if(permissionForRuntimeMic) {
            permissionForRuntimeMic=false;
            if(mirroring && permissionGeneration==generation && session!=null) {
                if(results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)applyRuntime("forwardMic",true);
                else toast(UiText.text(R.string.microphone_permission_denied_microphone_stays_off));
                updateRuntimeSwitches();
            }
            return;
        }
        Profile pending=permissionProfile;permissionProfile=null;
        if(pending!=null && results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)connectProfile(pending);
        else toast(UiText.text(R.string.microphone_permission_is_required_you_can_turn_off_microphone_fo));
    }
    private void persist(Profile p) {
        try { prefs.edit().putString("last", p.json().toString()).apply(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void applyPreset(int i) {
        int[] sizes = {1024, 1600, 2400}, bits = {2, 6, 12}, rates = {30, 60, 60};
        size.setText("" + sizes[i]); bitrate.setText("" + bits[i]); fps.setText("" + rates[i]); codec.setSelection(0);
        updateQuality();toast(UiText.text(R.string.applied) + new String[]{UiText.text(R.string.economy), UiText.text(R.string.balanced), UiText.text(R.string.high_quality)}[i] + UiText.text(R.string.quality_you_can_adjust_the_values));
    }
    private JSONArray savedProfiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void saveProfile() {
        final Profile p;
        try { p=readForm(); } catch(Exception e) { toast(UiText.error(e));return; }
        LinearLayout body=configPanel(UiText.text(R.string.save_connection_profile),UiText.text(R.string.saves_the_address_video_and_control_settings_saving_the_same_nam));
        EditText name=field(body,UiText.text(R.string.profile_name),p.name.isEmpty()?p.endpoint():p.name,false);
        name.setInputType(InputType.TYPE_CLASS_TEXT);name.setSingleLine(true);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        name.setHint(UiText.text(R.string.e_g_work_phone));name.setHintTextColor(MUTED);
        TextView error=inputError(body,name);
        LinearLayout actions=row();Button cancel=button(UiText.text(R.string.cancel),false),save=button(UiText.text(R.string.save),true);
        configAction(actions,cancel,1,false);configAction(actions,save,1.4f,true);
        Dialog dialog=showConfigDialog(editorPanel(body,actions),dp(260));
        cancel.setOnClickListener(v -> dismissEditorDialog(dialog));
        save.setOnClickListener(v -> {
            String n=name.getText().toString().trim();
            if(n.isEmpty()){showInputError(error,UiText.text(R.string.enter_a_profile_name));return;}
            p.name=n;
            try {
                JSONArray old=savedProfiles(),result=new JSONArray();
                for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).optString("name").equals(n))result.put(old.getJSONObject(i));
                result.put(p.json());prefs.edit().putString("profiles",result.toString()).apply();persist(p);
                dismissEditorDialog(dialog);showForm();toast(UiText.format(R.string.saved_named,n));
            } catch(Exception e){showInputError(error,UiText.text(R.string.could_not_save)+e.getMessage());}
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
        return !p.name.isEmpty() && p.name.equals(profile.name) && p.sameEndpoint(profile);
    }
    private void loadProfiles() {
        JSONArray saved = savedProfiles();
        LinearLayout panel = configPanel(UiText.text(R.string.switch_connection_profile), UiText.text(R.string.select_a_device_and_confirm_to_switch_all_connection_settings));
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column(); scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); gap(panel,16);
        LinearLayout actions = row();
        Button cancel = button(UiText.text(R.string.cancel),false), delete = button(UiText.text(R.string.delete),false), use = button(UiText.text(R.string.use_profile),true);
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
                TextView empty = label(UiText.text(R.string.no_saved_profiles_yet_enter_a_device_address_then_tap_save_profi),13,MUTED);
                empty.setGravity(Gravity.CENTER); empty.setPadding(dp(8),dp(16),dp(8),dp(16)); add(list,empty,-2);
            }
            for (int i=0;i<saved.length();i++) {
                final int index=i; Profile p=Profile.from(saved.optJSONObject(i));
                LinearLayout entry=row(); entry.setPadding(dp(12),dp(12),dp(12),dp(12));
                entry.setContentDescription(p.name+"\n"+p.endpoint()); entry.setFocusable(true); entry.setClickable(true);
                ImageView mark=new ImageView(this); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(22),dp(22));mp.setMarginEnd(dp(12));entry.addView(mark,mp);
                LinearLayout details=column();
                TextView name=label(p.name.isEmpty()?p.endpoint():p.name,15,TEXT);name.setTypeface(null,Typeface.BOLD);
                name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);add(details,name,-2);gap(details,3);
                TextView address=label(p.endpoint(),12,MUTED);address.setSingleLine(true);
                address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);add(details,address,-2);
                entry.addView(details,new LinearLayout.LayoutParams(0,-2,1));
                if (isCurrentConfig(p)) {
                    TextView badge=label(UiText.text(R.string.current),10,ACCENT);badge.setPadding(dp(6),dp(4),dp(6),dp(4));
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
            LinearLayout confirmPanel=configPanel(UiText.format(R.string.delete_named_title,p.name),p.endpoint()+UiText.text(R.string.this_removes_the_saved_connection_settings_for_this_device));
            LinearLayout confirmActions=row();Button keep=button(UiText.text(R.string.cancel),false), remove=button(UiText.text(R.string.delete_profile),false);
            remove.setTextColor(BG);remove.setBackground(background(0xffef9292,14));
            configAction(confirmActions,keep,1,false);configAction(confirmActions,remove,1.4f,true);add(confirmPanel,confirmActions,-2);
            Dialog confirm=showConfigDialog(confirmPanel,-2);
            keep.setOnClickListener(w -> confirm.dismiss());
            remove.setOnClickListener(w -> {
                saved.remove(index);prefs.edit().putString("profiles",saved.toString()).apply();
                if (isCurrentConfig(p)) {
                    profile.name="";configLabel.setText(UiText.text(R.string.connection_profile));
                    Profile last=lastProfile();
                    if(last.name.equals(p.name) && last.sameEndpoint(p)){last.name="";persist(last);}
                }
                selected[0]=-1;confirm.dismiss();rebuild.run();toast(UiText.text(R.string.profile_deleted));
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
        activeUsb=p.isUsb()?selectedUsb:null;final UsbDevice usb=activeUsb;
        profile = p;connectedDevice=null; keyboardVisible = false; mirroring = true; final int token = ++generation; videoWidth = videoHeight = 0;
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        FrameLayout root = new FrameLayout(this); mirrorRoot=root;root.setBackgroundColor(BG);setContentView(root);
        safeLeft=safeRight=safeBottom=0;safeTop=statusBarHeight();landscapeLayout=false;
        navigationKeys.clear();actionKeys.clear();
        mirrorBody = row(); root.addView(mirrorBody,new FrameLayout.LayoutParams(-1,-1));
        videoArea = new FrameLayout(this); videoArea.setBackgroundColor(Color.BLACK);
        mirrorBody.addView(videoArea,new LinearLayout.LayoutParams(-1,0,1));
        surface = new SurfaceView(this); surface.setContentDescription(UiText.text(R.string.remote_screen));
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
        connectionStatus=label(UiText.text(R.string.connecting),9,ACCENT);connectionStatus.setSingleLine(false);connectionStatus.setMaxLines(3);
        connectionStatus.setIncludeFontPadding(false);connectionStatus.setLineSpacing(0,1.05f);
        connectionStatus.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,TypedValue.COMPLEX_UNIT_SP);
        connectionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionStatus.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(connectionStatus,new FrameLayout.LayoutParams(dp(90),-1,Gravity.START|Gravity.CENTER_VERTICAL));
        navigation=row();navigation.setGravity(Gravity.CENTER);
        {
            int[] icons={R.drawable.ic_back,R.drawable.ic_home,R.drawable.ic_recents}, codes={4,3,187};
            String[] names={"Back","Home",UiText.text(R.string.recent_apps)};
            for(int i=0;i<icons.length;i++) {
                final int code=codes[i];ImageButton key=iconButton(icons[i],names[i]);
                key.setEnabled(!p.viewOnly);key.setAlpha(p.viewOnly?.35f:1);
                key.setOnClickListener(v -> {if(session!=null)session.key(code);});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));
                if(i<2)lp.setMarginEnd(dp(4));if(p.showNavigation)navigation.addView(key,lp);navigationKeys.add(key);
            }
        }
        dock.addView(navigation,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));
        LinearLayout actions=row();mirrorActions=actions;
        keyboardToggle=iconButton(R.drawable.ic_keyboard,UiText.text(R.string.virtual_keyboard));keyboardToggle.setEnabled(!p.viewOnly);
        keyboardToggle.setAlpha(p.viewOnly?.35f:1);keyboardToggle.setOnClickListener(v -> showKeyboard(!keyboardVisible));
        LinearLayout.LayoutParams keyboardParams=new LinearLayout.LayoutParams(dp(44),dp(44));keyboardParams.setMarginEnd(dp(4));
        actions.addView(keyboardToggle,keyboardParams);actionKeys.add(keyboardToggle);
        ImageButton exit=iconButton(R.drawable.ic_settings,UiText.text(R.string.remote_settings));exit.setId(R.id.runtime_settings);
        actions.addView(exit,new LinearLayout.LayoutParams(dp(44),dp(44)));actionKeys.add(exit);
        exit.setOnClickListener(v -> showRuntimeSettings());
        dock.addView(actions,new FrameLayout.LayoutParams(-2,-1,Gravity.END|Gravity.CENTER_VERTICAL));
        Runnable updateDock=() -> {
            if(landscapeLayout)return;
            int dockWidth=dock.getWidth(),navWidth=navigation.getMeasuredWidth();
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
        };
        // Changing sibling widths during layout can discard their next measure.
        // Reflow after this pass when keys are attached or detached at runtime.
        dock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> dock.post(updateDock));
        navigation.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> dock.post(updateDock));
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
                session = new ScrcpySession(MainActivity.this, p, usb, holder.getSurface(), new ScrcpySession.Listener() {
                    private void ui(Runnable r) { runOnUiThread(() -> { if (token == generation) r.run(); }); }
                    @Override public void status(String text) { ui(() -> updateConnectionStatus(text)); }
                    @Override public void size(int w, int h) { ui(() -> { videoWidth = w; videoHeight = h; fitVideo(); }); }
                    @Override public void ready(String device) { ui(() -> {connectedDevice=device;updateConnectionStatus("● " + (p.viewOnly ? UiText.text(R.string.view_only) : UiText.text(R.string.connected)) + "\n" + device);syncOrientation();}); }
                    @Override public void settingFailed(String key){ui(() -> {
                        if(key.equals("forwardMic"))profile.forwardMic=false;else if(key.equals("forwardAudio"))profile.forwardAudio=false;
                        persistRuntime();updateRuntimeSwitches();
                    });}
                    @Override public void warning(String message) { ui(() -> toast(message)); }
                    @Override public void error(String message) { ui(() -> disconnect(UiText.text(R.string.connection_ended) + message)); }
                });session.orientation(isLandscape());session.start();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                if (token == generation && session != null) disconnect(UiText.text(R.string.screen_closed_please_reconnect));
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
                for(View key:navigationKeys){detach(key);if(profile.showNavigation)controlRail.addView(key);}
                for(View key:actionKeys){detach(key);controlRail.addView(key);}
            } else {
                detach(connectionStatus);dock.addView(connectionStatus,new FrameLayout.LayoutParams(dp(90),-1,Gravity.START|Gravity.CENTER_VERTICAL));
                for(int i=0;i<navigationKeys.size();i++) {
                    View key=navigationKeys.get(i);detach(key);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));
                    if(i<2)lp.setMarginEnd(dp(4));if(profile.showNavigation)navigation.addView(key,lp);
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
        if(mirroring && session!=null)session.orientation(isLandscape());
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
        LinearLayout body=configPanel(UiText.text(R.string.enter_text),UiText.text(R.string.use_your_local_input_method_then_send_to_the_remote_device_up_to));
        add(body,label(UiText.text(R.string.text),11,MUTED),-2);gap(body,4);
        EditText editor=new EditText(this);editor.setTextColor(TEXT);editor.setTextSize(16);editor.setHintTextColor(MUTED);
        editor.setPadding(dp(12),dp(10),dp(12),dp(10));editor.setBackground(background(0xff0e1921,10));
        editor.setHint(UiText.text(R.string.enter_text_to_send_to_the_remote_device));editor.setContentDescription(UiText.text(R.string.text_to_send));
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);editor.setGravity(Gravity.TOP);add(body,editor,120);
        TextView error=inputError(body,editor);
        LinearLayout actions=row();Button cancel=button(UiText.text(R.string.cancel),false),send=button(UiText.text(R.string.send),true);
        configAction(actions,cancel,1,false);configAction(actions,send,1.4f,true);
        Dialog dialog=showConfigDialog(editorPanel(body,actions),dp(320));textDialog=dialog;
        dialog.setOnDismissListener(ignored -> {if(textDialog==dialog)textDialog=null;});
        cancel.setOnClickListener(v -> dismissEditorDialog(dialog));
        send.setOnClickListener(v -> {
            if(!mirroring || generation!=token || session!=target){dismissEditorDialog(dialog);return;}
            try {target.paste(editor.getText().toString());dismissEditorDialog(dialog);}
            catch(IllegalArgumentException e){showInputError(error,UiText.error(e));}
        });
    }
    private void pasteClipboard() {
        if(!mirroring || session==null || profile.viewOnly)return;
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip=clipboard.getPrimaryClip();
        if(clip==null || clip.getItemCount()==0){toast(UiText.text(R.string.clipboard_is_empty));return;}
        try {session.paste(clip.getItemAt(0).coerceToText(this).toString());}
        catch(IllegalArgumentException e){toast(UiText.error(e));}
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
        LinearLayout panel=configPanel(UiText.text(R.string.disconnect_2),UiText.text(R.string.this_will_end_the_current_remote_session));
        LinearLayout actions=row();Button cancel=button(UiText.text(R.string.cancel),false), confirm=button(UiText.text(R.string.confirm_disconnect),false);
        cancel.setId(R.id.disconnect_cancel);confirm.setId(R.id.disconnect_confirm);
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
        activeUsb=null;
        permissionForRuntimeMic=false;
        if(choicePopup!=null)choicePopup.dismiss();
        Dialog settings=settingsDialog;settingsDialog=null;if(settings!=null)settings.dismiss();
        Dialog dialog=disconnectDialog;disconnectDialog=null;if(dialog!=null)dialog.dismiss();
        Dialog editor=textDialog;textDialog=null;dismissEditorDialog(editor);
        if(remoteInput!=null)remoteInput.cancel();
        if(remoteKeyboard!=null)remoteKeyboard.reset();
        generation++; mirroring=false;keyboardVisible=false;
        ScrcpySession s = session; session = null; if (s != null) s.closeGracefully();
        errorMessage = error; showForm();
    }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);boolean languageChanged=UiText.refresh();
        if(mirroring){remoteInput.cancel();remoteKeyboard.reset();syncOrientation();mirrorBody.post(() -> {updateMirrorLayout();mirrorRoot.requestApplyInsets();});if(languageChanged)refreshMirrorLanguage();}
        else if(languageChanged)refreshFormLanguage();
    }
    @Override protected void onStop() { super.onStop(); if (mirroring) disconnect(UiText.text(R.string.left_the_remote_screen_connection_closed)); }
    @Override protected void onResume(){super.onResume();resumed=true;completeUsbPermission();if(!mirroring && usbPicker!=null)updateTransportUi();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);usbAttachedIntent(intent);}
    @Override protected void onDestroy() { cancelUsbPermission();unregisterReceiver(usbEvents);if (session != null) session.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (mirroring) {
            android.util.Log.d("RemoteGesture", "LOCAL_BACK_FORWARD");
            if (session != null) session.key(4);
        } else super.onBackPressed();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
