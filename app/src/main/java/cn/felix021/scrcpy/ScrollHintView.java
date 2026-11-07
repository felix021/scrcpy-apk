package cn.felix021.scrcpy;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/** Continuation cue that scrolls on tap and never clicks obscured settings. */
final class ScrollHintView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScrollView scroll;
    private float downY;
    private boolean dragged;
    ScrollHintView(Context context,ScrollView scroll){
        super(context);this.scroll=scroll;setClickable(true);
        setContentDescription(UiText.text(R.string.scroll_up_for_more_settings));
        setOnClickListener(v -> scroll.smoothScrollBy(0,Math.max((int)dp(140),scroll.getHeight()/2)));
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private void forward(MotionEvent event,int action){
        MotionEvent copy=MotionEvent.obtain(event);copy.setAction(action);
        copy.offsetLocation(getLeft()-scroll.getLeft(),getTop()-scroll.getTop());scroll.dispatchTouchEvent(copy);copy.recycle();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){downY=event.getY();dragged=false;}
        if(Math.abs(event.getY()-downY)>dp(8))dragged=true;
        if(action==MotionEvent.ACTION_UP && !dragged){forward(event,MotionEvent.ACTION_CANCEL);performClick();}
        else forward(event,action);
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        paint.setShader(new LinearGradient(0,0,0,getHeight(),new int[]{0x000d141b,0xf50d141b,0xff0d141b},new float[]{0,.65f,1},Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);paint.setShader(null);
        paint.setTextAlign(Paint.Align.CENTER);paint.setColor(0xff96aab9);paint.setTextSize(dp(11));
        canvas.drawText(UiText.text(R.string.scroll_up_for_more_settings),getWidth()/2f,getHeight()-dp(8),paint);
    }
}
