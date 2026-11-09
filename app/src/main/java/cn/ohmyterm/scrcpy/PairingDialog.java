package cn.ohmyterm.scrcpy;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ohmyterm.scrcpy.adb.AdbDiscovery;
import cn.ohmyterm.scrcpy.adb.AdbPairing;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.tananaev.adblib.AdbCrypto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pairing state belongs to this dialog; dismiss/mode changes cancel its socket. */
final class PairingDialog implements AutoCloseable {
    interface Connected { void use(String host,int port); }
    private static final int BG=0xff0d141b,CARD=0xff17232d,TEXT=0xffedf5f7,MUTED=0xff96aab9,ACCENT=0xff80e2c3;
    private final Activity activity;
    private final Connected connected;
    private final Dialog dialog;
    private final LinearLayout body,discovered;
    private final Button qrTab,codeTab,action;
    private final TextView status;
    private final EditText host,port,code,connectPort;
    private final LinearLayout codeFields,qrFields,successFields;
    private final ImageView qr;
    private final AdbDiscovery discovery;
    private List<AdbDiscovery.Service> services=new ArrayList<>();
    private AdbPairing.Challenge challenge;
    private AdbPairing pairing;
    private boolean closed,busy,qrMode=true,paired,suggestedPort,attemptedQR;
    private int generation;
    private String pairedHost;
    PairingDialog(Activity activity,String initialHost,Connected connected){
        this.activity=activity;this.connected=connected;
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setOwnerActivity(activity);
        LinearLayout root=column();root.setBackground(shape(CARD,22));root.setPadding(dp(20),dp(18),dp(20),dp(16));
        TextView title=text(R.string.wireless_pairing,20,TEXT);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView intro=text(R.string.pairing_intro,12,MUTED);pad(intro);
        TextView local=text(UiText.text(R.string.local_device_name)+": "+AdbIdentity.localName(activity),13,ACCENT);local.setContentDescription(UiText.text(R.string.local_device_name));pad(local);
        LinearLayout tabs=row();qrTab=button(R.string.pair_qr);codeTab=button(R.string.pair_code);equal(tabs,qrTab);equal(tabs,codeTab);root.addView(tabs);
        android.widget.FrameLayout viewport=new android.widget.FrameLayout(activity);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setScrollbarFadingEnabled(false);body=column();body.setPadding(0,dp(14),0,dp(40));scroll.addView(body);viewport.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        ScrollHintView hint=new ScrollHintView(activity,scroll);viewport.addView(hint,new android.widget.FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM));
        // A gradient over the QR can corrupt scanning; its scroll bar stays visible.
        Runnable update=() -> hint.setVisibility(scroll.canScrollVertically(1) && (!qrMode || paired)?View.VISIBLE:View.GONE);
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->update.run());scroll.getViewTreeObserver().addOnGlobalLayoutListener(()->scroll.post(update));
        qrFields=column();qr=new ImageView(activity);qr.setId(R.id.pair_qr_image);qr.setContentDescription(UiText.text(R.string.pair_qr));qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(dp(224),dp(224));image.gravity=Gravity.CENTER_HORIZONTAL;qrFields.addView(qr,image);
        TextView qrHint=text(R.string.pair_qr_hint,12,MUTED);pad(qrHint);qrFields.addView(qrHint);Button refresh=button(R.string.pair_refresh);qrFields.addView(refresh,new LinearLayout.LayoutParams(-1,dp(40)));refresh.setOnClickListener(v->mode(true));body.addView(qrFields);
        codeFields=column();host=field(codeFields,R.string.device_ip_hostname,initialHost,false,R.id.pair_host);port=field(codeFields,R.string.pairing_port,"",true,R.id.pair_port);code=field(codeFields,R.string.pairing_code,"",true,R.id.pair_code_input);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});body.addView(codeFields);
        discovered=column();body.addView(discovered);
        successFields=column();successFields.addView(text(R.string.pair_connection_port_hint,12,MUTED));connectPort=field(successFields,R.string.adb_port,"",true,R.id.pair_connect_port);body.addView(successFields);body.addView(intro);body.addView(local);
        status=text("",12,MUTED);status.setId(R.id.pair_status);pad(status);root.addView(status);
        LinearLayout actions=row();Button cancel=button(R.string.cancel);cancel.setOnClickListener(v->close());equal(actions,cancel);action=button(R.string.pair_start);action.setId(R.id.pair_action);action.setTextColor(BG);action.setBackground(shape(ACCENT,12));equal(actions,action);root.addView(actions);
        qrTab.setId(R.id.pair_qr_tab);codeTab.setId(R.id.pair_code_tab);qrTab.setOnClickListener(v->mode(true));codeTab.setOnClickListener(v->mode(false));action.setOnClickListener(v->submit());
        TextWatcher validate=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int before,int count){updateAction();}public void afterTextChanged(Editable e){}};
        host.addTextChangedListener(validate);port.addTextChangedListener(validate);code.addTextChangedListener(validate);connectPort.addTextChangedListener(validate);
        dialog.setContentView(root);dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.setOnDismissListener(ignored -> close());dialog.show();resize();
        discovery=new AdbDiscovery(activity,new AdbDiscovery.Listener(){public void changed(List<AdbDiscovery.Service> found){if(!closed){services=found;servicesChanged();}}public void unavailable(){if(!closed && !paired && !busy)status.setText(UiText.text(R.string.pair_discovery_unavailable));}});
        mode(true);discovery.start();
    }
    private void mode(boolean qrMode){
        cancelPairing();this.qrMode=qrMode;paired=false;busy=false;suggestedPort=false;attemptedQR=false;pairedHost=null;code.setText("");connectPort.setText("");
        challenge=qrMode?new AdbPairing.Challenge():null;
        qrFields.setVisibility(qrMode?View.VISIBLE:View.GONE);codeFields.setVisibility(qrMode?View.GONE:View.VISIBLE);successFields.setVisibility(View.GONE);
        qrTab.setTextColor(qrMode?BG:TEXT);qrTab.setBackground(shape(qrMode?ACCENT:0xff243642,12));codeTab.setTextColor(qrMode?TEXT:BG);codeTab.setBackground(shape(qrMode?0xff243642:ACCENT,12));
        qrTab.setEnabled(true);codeTab.setEnabled(true);host.setEnabled(true);port.setEnabled(true);code.setEnabled(true);
        status.setText(UiText.text(qrMode?R.string.pair_wait_scan:R.string.pair_code_hint));
        if(qrMode){try{
            BitMatrix matrix=new QRCodeWriter().encode(challenge.payload(),BarcodeFormat.QR_CODE,512,512,Collections.singletonMap(EncodeHintType.MARGIN,3));
            int[] pixels=new int[512*512];for(int y=0;y<512;y++)for(int x=0;x<512;x++)pixels[y*512+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
            qr.setImageBitmap(Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888));
        }catch(Exception e){status.setText(UiText.text(R.string.pair_failed));}}
        servicesChanged();updateAction();
    }
    private void servicesChanged(){
        discovered.removeAllViews();
        if(paired){
            if(!suggestedPort && connectPort.getText().toString().isEmpty())for(AdbDiscovery.Service s:services)if(!s.pairing() && s.host.equals(pairedHost)){suggestedPort=true;connectPort.setText(String.valueOf(s.port));break;}
            return;
        }
        if(qrMode){
            if(!busy && !attemptedQR && challenge!=null)for(AdbDiscovery.Service s:services)if(s.pairing() && s.name.equals(challenge.service)){attemptedQR=true;start(s.host,s.port,challenge.password);break;}
        }else if(!busy){for(AdbDiscovery.Service s:services)if(s.pairing() && !s.name.startsWith("studio-")){
            Button option=button(s.host+":"+s.port);discovered.addView(option,new LinearLayout.LayoutParams(-1,dp(40)));option.setOnClickListener(v->{host.setText(s.host);port.setText(String.valueOf(s.port));});
        }}
    }
    private void updateAction(){if(action==null)return;action.setText(UiText.text(paired?R.string.use_pair_connection:R.string.pair_start));action.setEnabled(!closed && !busy && (paired?validPort(connectPort):!qrMode && !host.getText().toString().trim().isEmpty() && validPort(port) && code.getText().toString().matches("[0-9]{6}")));action.setAlpha(action.isEnabled()?1f:.4f);}
    private boolean validPort(EditText input){try{int p=Integer.parseInt(input.getText().toString());return p>0 && p<65536;}catch(NumberFormatException e){return false;}}
    private void submit(){
        if(paired){connected.use(pairedHost,Integer.parseInt(connectPort.getText().toString()));close();return;}
        if(!busy && validPort(port) && code.getText().toString().matches("[0-9]{6}"))start(host.getText().toString().trim(),Integer.parseInt(port.getText().toString()),code.getText().toString());
    }
    private void start(String address,int pairingPort,String password){
        busy=true;host.setEnabled(false);port.setEnabled(false);code.setEnabled(false);status.setText(UiText.text(R.string.pairing_in_progress));discovered.removeAllViews();updateAction();
        int token=++generation;AdbPairing client=new AdbPairing();pairing=client;
        new Thread(()->{
            try{
                AdbCrypto identity=AdbIdentity.load(activity.getApplicationContext());client.pair(address,pairingPort,password,identity);
                activity.runOnUiThread(()->{if(closed || token!=generation)return;pairing=null;busy=false;paired=true;pairedHost=address;challenge=null;qr.setImageDrawable(null);code.setText("");qrFields.setVisibility(View.GONE);codeFields.setVisibility(View.GONE);discovered.removeAllViews();successFields.setVisibility(View.VISIBLE);qrTab.setEnabled(false);codeTab.setEnabled(false);status.setText(UiText.text(R.string.pair_success));servicesChanged();updateAction();});
            }catch(Exception e){android.util.Log.w("AdbPairing","Wireless pairing failed",e);activity.runOnUiThread(()->{if(closed || token!=generation)return;pairing=null;busy=false;host.setEnabled(true);port.setEnabled(true);code.setEnabled(true);status.setText(UiText.text(R.string.pair_failed));updateAction();});}
        },"adb-pairing").start();
    }
    private void cancelPairing(){generation++;AdbPairing old=pairing;pairing=null;if(old!=null)try{old.close();}catch(Exception ignored){}}
    @Override public void close(){if(closed)return;closed=true;cancelPairing();challenge=null;code.setText("");qr.setImageDrawable(null);if(discovery!=null)discovery.close();dialog.dismiss();}
    void resize(){
        if(!dialog.isShowing())return;
        int height=Math.min(dp(660),activity.getResources().getDisplayMetrics().heightPixels-dp(64));
        dialog.getWindow().setLayout(Math.min(dp(440),activity.getResources().getDisplayMetrics().widthPixels-dp(32)),height);
        if(qr!=null){int side=Math.min(dp(224),Math.max(dp(64),height-dp(220)));qr.getLayoutParams().width=side;qr.getLayoutParams().height=side;qr.requestLayout();}
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable s=new GradientDrawable();s.setColor(color);s.setCornerRadius(dp(radius));return s;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(int res,int sp,int color){return text(UiText.text(res),sp,color);}
    private TextView text(String value,int sp,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);return v;}
    private void pad(View view){view.setPadding(0,dp(8),0,dp(12));}
    private Button button(int res){return button(UiText.text(res));}
    private Button button(String value){Button b=new Button(activity);b.setText(value);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(TEXT);b.setBackground(shape(0xff243642,12));b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),0,dp(8),0);return b;}
    private void equal(LinearLayout row,View child){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(3),0,dp(3),0);row.addView(child,p);}
    private EditText field(LinearLayout parent,int title,String value,boolean number,int id){TextView label=text(title,11,MUTED);pad(label);parent.addView(label);EditText input=new EditText(activity);input.setId(id);input.setContentDescription(UiText.text(title));input.setText(value);input.setTextColor(TEXT);input.setTextSize(15);input.setSingleLine(true);input.setSelectAllOnFocus(true);input.setPadding(dp(12),0,dp(12),0);input.setBackground(shape(BG,10));input.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);parent.addView(input,new LinearLayout.LayoutParams(-1,dp(44)));return input;}
}
