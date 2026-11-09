package cn.ohmyterm.scrcpy.probe;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    TextView text;
    int touches;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this); text.setTextSize(24); text.setTextColor(0xff80e2c3);
        text.setGravity(Gravity.CENTER); text.setBackgroundColor(getIntent().getBooleanExtra("bright",false)?0xffd8b463:0xff17232d); setContentView(text);
        show("READY");
    }
    private void show(String event) { text.setText("scrcpy validation\n" + event); Log.i("ScrcpyProbe", event); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) Log.i("ScrcpyProbe", "TOUCH_DOWN");
        if (e.getActionMasked() == MotionEvent.ACTION_UP) show("TOUCH " + ++touches + " " + (int)e.getX() + "," + (int)e.getY());
        if (e.getPointerCount() > 1) show("MULTITOUCH " + e.getPointerCount());
        return true;
    }
    @Override public boolean onKeyDown(int keycode, KeyEvent e) {
        if (keycode == KeyEvent.KEYCODE_MENU) { show("MENU 82"); return true; }
        if (keycode == KeyEvent.KEYCODE_BACK) show("BACK 4");
        return super.onKeyDown(keycode, e);
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); show("ROTATION " + c.orientation); }
}
