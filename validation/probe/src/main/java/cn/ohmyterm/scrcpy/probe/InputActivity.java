package cn.ohmyterm.scrcpy.probe;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class InputActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Remote keyboard validation");title.setTextSize(20);root.addView(title);
        EditText editor=new EditText(this);editor.setContentDescription("验证输入框");editor.setTextSize(24);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setShowSoftInputOnFocus(false);root.addView(editor,new LinearLayout.LayoutParams(-1,-1));
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){Log.i("ScrcpyInput","TEXT "+s);}
            public void afterTextChanged(Editable e){}
        });setContentView(root);editor.requestFocus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getAction()==KeyEvent.ACTION_DOWN)Log.i("ScrcpyInput","KEY "+event.getKeyCode()+" META "+event.getMetaState());
        return super.dispatchKeyEvent(event);
    }
}
