package cn.felix021.scrcpy;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;

/** Native full keyboard inspired by oh-my-term's one-shot/locked modifiers and Fn layer. */
final class RemoteKeyboard extends LinearLayout {
    interface Listener {
        void key(int code, int meta);
        void text();
        void paste();
        void close();
    }
    private final Listener listener;
    private final KeyboardState state = new KeyboardState();
    private final Handler repeats = new Handler(Looper.getMainLooper());
    private boolean fillHeight;
    private Button ctrlButton, altButton, shiftButton, fnButton;
    private final int text = 0xffedf5f7, accent = 0xff80e2c3;

    RemoteKeyboard(Context context, Listener listener) {
        super(context); this.listener = listener;
        setOrientation(VERTICAL); setBackgroundColor(0xff111c25);
        setPadding(dp(4), dp(5), dp(4), dp(5)); setContentDescription(UiText.text(R.string.full_virtual_keyboard)); render();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(6)); return d;
    }
    void setFillHeight(boolean fill) {
        if (fillHeight != fill) { fillHeight = fill; repeats.removeCallbacksAndMessages(null); render(); }
    }
    void refreshLabels(){setContentDescription(UiText.text(R.string.full_virtual_keyboard));render();}
    void reset() { repeats.removeCallbacksAndMessages(null); state.clear(); render(); }
    @Override protected void onDetachedFromWindow() {
        repeats.removeCallbacksAndMessages(null); state.clear(); super.onDetachedFromWindow();
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lp = new LayoutParams(-1, fillHeight ? 0 : dp(38), fillHeight ? 1 : 0);
        addView(row, lp); return row;
    }
    private Button button(LinearLayout row, String label, String description, float weight, Runnable action) {
        Button b = new Button(getContext()); b.setText(label); b.setAllCaps(false); b.setTextSize(description.equals("Left") || description.equals("Down") || description.equals("Up") || description.equals("Right") ? 18 : 12);
        b.setTypeface(Typeface.MONOSPACE); b.setTextColor(text); b.setContentDescription(description);
        b.setTooltipText(description); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(0,0,0,0); b.setStateListAnimator(null); b.setBackground(bg(0xff253541));
        LayoutParams lp = new LayoutParams(0,-1,weight); lp.setMargins(dp(2),dp(2),dp(2),dp(2)); row.addView(b,lp);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void modifierAppearance(Button b, int value) {
        b.setTextColor(value > 0 ? 0xff0d141b : text); b.setBackground(bg(value > 0 ? accent : 0xff253541));
        b.setSelected(value > 0); if (value == 2) b.setText(b.getText() + "•");
    }
    private void emit(int code, int extraMeta) {
        listener.key(code, state.meta() | extraMeta); state.consume(); render();
    }
    private void key(LinearLayout row, String label, String description, int code, int extraMeta, float weight, boolean repeat) {
        Button b = button(row,label,description,weight,() -> emit(code,extraMeta));
        if (repeat) {
            final Runnable[] repeater = new Runnable[1];
            final boolean[] held = {false};
            repeater[0] = () -> {
                if (!isAttachedToWindow() || !isShown()) return;
                // Repeating must not rebuild/remove the pressed button.
                listener.key(code, state.meta() | extraMeta); repeats.postDelayed(repeater[0],65);
            };
            b.setOnLongClickListener(v -> { held[0]=true; repeats.post(repeater[0]); return true; });
            b.setOnTouchListener((v,e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    repeats.removeCallbacks(repeater[0]);
                    if (held[0]) { held[0]=false;state.consume();repeats.post(this::render); }
                }
                return false;
            });
        }
    }
    private void character(LinearLayout row, char c) {
        char shown = Character.isLetter(c) && state.upper() ? Character.toUpperCase(c) : c;
        if (!Character.isLetter(c) && state.shift > 0) shown = KeyboardState.shifted(c);
        final char value = shown;
        int extra = (KeyboardState.needsShift(value)) ? 0x41 : 0;
        // Caps only affects letters, without applying Shift to punctuation/numbers.
        int code = KeyboardState.code(Character.toLowerCase(value));
        key(row,String.valueOf(value),UiText.text(R.string.character) + value,code,extra,1,false);
    }
    private void render() {
        repeats.removeCallbacksAndMessages(null); removeAllViews();
        // oh-my-term's two control rows: keep the navigation cross and paired
        // Home/End, PgUp/PgDn in fixed columns, independent of the Fn layer.
        LinearLayout firstControls = row(), secondControls = row();
        key(firstControls,state.fn>0 ? "Menu" : "Esc",state.fn>0 ? UiText.text(R.string.menu_key) : "Escape",state.fn>0 ? 82 : 111,0,1,true);
        ctrlButton = button(firstControls,"Ctrl","Ctrl",1,() -> { state.ctrl=KeyboardState.toggle(state.ctrl); render(); });
        ctrlButton.setOnLongClickListener(v -> { state.ctrl=KeyboardState.lock(state.ctrl); render(); return true; }); modifierAppearance(ctrlButton,state.ctrl);
        key(firstControls,"Home",UiText.text(R.string.home_key),122,0,1,true);
        key(firstControls,"↑","Up",19,0,1,true);
        key(firstControls,"End","End",123,0,1,true);
        key(firstControls,"PgUp","PgUp",92,0,1,true);
        if (state.fn > 0) key(firstControls,"F11","F11",141,0,1,false);
        else button(firstControls,UiText.text(R.string.text_2),UiText.text(R.string.enter_text),1,() -> { reset();listener.text(); });
        key(firstControls,"Del","Delete",112,0,1,true);
        key(secondControls,state.fn>0 ? "Ins" : "Tab",state.fn>0 ? "Insert" : "Tab",state.fn>0 ? 124 : 61,0,1,true);
        altButton = button(secondControls,"Alt","Alt",1,() -> { state.alt=KeyboardState.toggle(state.alt); render(); });
        altButton.setOnLongClickListener(v -> { state.alt=KeyboardState.lock(state.alt); render(); return true; }); modifierAppearance(altButton,state.alt);
        key(secondControls,"←","Left",21,0,1,true);
        key(secondControls,"↓","Down",20,0,1,true);
        key(secondControls,"→","Right",22,0,1,true);
        key(secondControls,"PgDn","PgDn",93,0,1,true);
        if (state.fn > 0) key(secondControls,"F12","F12",142,0,1,false);
        else button(secondControls,UiText.text(R.string.paste),UiText.text(R.string.paste),1,() -> { reset();listener.paste(); });
        button(secondControls,UiText.text(R.string.hide),UiText.text(R.string.hide_keyboard),1,listener::close);
        LinearLayout numbers = row();
        String digits = "1234567890";
        for (int i=0;i<digits.length();i++) {
            if (state.fn > 0) key(numbers,"F"+(i+1),"F"+(i+1),131+i,0,1,false);
            else character(numbers,digits.charAt(i));
        }
        key(numbers,"⌫","Backspace",67,0,1.35f,true);
        LinearLayout first = row(), second = row(), third = row();
        if (state.symbols) {
            for (char c : "`~!@#$%^&*".toCharArray()) character(first,c);
            for (char c : "()[]{}<>/\\".toCharArray()) character(second,c);
            for (char c : "+=-_:;'\"|?".toCharArray()) character(third,c);
            key(second,"Enter","Enter",66,0,1.3f,false);
        } else {
            for (char c : "qwertyuiop".toCharArray()) character(first,c);
            for (char c : "asdfghjkl".toCharArray()) character(second,c);
            key(second,"Enter","Enter",66,0,1.3f,false);
            for (char c : "zxcvbnm,./".toCharArray()) character(third,c);
        }
        LinearLayout bottom = row();
        button(bottom,state.symbols ? "ABC" : "#+=",UiText.text(R.string.switch_symbols),1.4f,() -> { state.symbols=!state.symbols; render(); });
        Button caps = button(bottom,"Caps","Caps Lock",1.4f,() -> { state.caps=!state.caps; render(); });
        modifierAppearance(caps,state.caps ? 2 : 0);
        shiftButton = button(bottom,"Shift","Shift",1.4f,() -> { state.shift=KeyboardState.toggle(state.shift); render(); });
        shiftButton.setOnLongClickListener(v -> { state.shift=KeyboardState.lock(state.shift); render(); return true; }); modifierAppearance(shiftButton,state.shift);
        key(bottom,UiText.text(R.string.space),"Space",62,0,4,false);
        fnButton = button(bottom,"Fn","Fn",1.4f,() -> { state.fn=KeyboardState.toggle(state.fn); render(); });
        fnButton.setOnLongClickListener(v -> { state.fn=KeyboardState.lock(state.fn); render(); return true; }); modifierAppearance(fnButton,state.fn);
    }
}
