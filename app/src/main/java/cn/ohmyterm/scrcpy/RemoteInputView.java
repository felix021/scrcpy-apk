package cn.ohmyterm.scrcpy;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;

/** Transparent input plane covering video and letterboxing, without covering local controls. */
final class RemoteInputView extends View {
    interface Listener {
        void touch(int action,long pointer,int x,int y,int w,int h,float pressure);
        void navigate(EdgeGesture.Edge edge);
    }
    private enum Mode { IDLE, PENDING, TOUCH, NAVIGATION, IGNORE }
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final EdgeGesture gesture=new EdgeGesture();
    private final ArrayList<MotionEvent> pending=new ArrayList<>();
    private final SparseArray<int[]> pressed=new SparseArray<>();
    private final Rect frame=new Rect();
    private int remoteWidth,remoteHeight;
    private boolean enabled;
    private Mode mode=Mode.IDLE;
    private final Runnable longPressFallback=() -> {
        if (mode==Mode.PENDING && gesture.startedInside() && !pending.isEmpty()) {
            MotionEvent last=pending.get(pending.size()-1);
            // Only a stationary press becomes an ordinary remote long press.
            // A slow inward swipe must remain eligible after the tap timeout.
            if(gesture.distance(x(last),y(last))<=ViewConfiguration.get(getContext()).getScaledTouchSlop())flush();
        }
    };

    RemoteInputView(Context context,Listener listener) {
        super(context);this.listener=listener;setContentDescription(UiText.text(R.string.remote_touch_area));setClickable(true);
    }
    private float dp(float n) { return n*getResources().getDisplayMetrics().density; }
    void updateFrame(Rect bounds,int w,int h,boolean controlEnabled) {
        if (!frame.equals(bounds) || w!=remoteWidth || h!=remoteHeight || enabled!=controlEnabled) cancel();
        frame.set(bounds);remoteWidth=w;remoteHeight=h;enabled=controlEnabled;
    }
    void cancel() {
        handler.removeCallbacksAndMessages(null);clearPending();release();mode=Mode.IDLE;
    }
    @Override protected void onDetachedFromWindow() { cancel();super.onDetachedFromWindow(); }
    private void clearPending() { for (MotionEvent e:pending)e.recycle();pending.clear(); }
    private void remember(MotionEvent e) {
        // Keep the original DOWN and a bounded tail for a switch to ordinary touch.
        if (pending.size()>=32) pending.remove(1).recycle();
        pending.add(MotionEvent.obtain(e));
    }
    private float x(MotionEvent e) { return e.getX()-frame.left; }
    private float y(MotionEvent e) { return e.getY()-frame.top; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!enabled || remoteWidth<=0 || remoteHeight<=0 || frame.isEmpty()) return true;
        int action=e.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            cancel();getParent().requestDisallowInterceptTouchEvent(true);
            gesture.begin(x(e),y(e),frame.width(),frame.height(),dp(24));
            if (gesture.candidate()) {
                mode=Mode.PENDING;remember(e);
                if (gesture.startedInside()) handler.postDelayed(longPressFallback,ViewConfiguration.getTapTimeout());
            } else if (gesture.startedInside()) { mode=Mode.TOUCH;forward(e); }
            else mode=Mode.IGNORE;
            return true;
        }
        if (action==MotionEvent.ACTION_CANCEL) {
            Log.d("RemoteGesture","CANCEL");cancel();return true;
        }
        if (action==MotionEvent.ACTION_POINTER_DOWN) {
            if (mode==Mode.PENDING) {
                if (gesture.startedInside()) flush();
                else { clearPending();handler.removeCallbacks(longPressFallback);mode=Mode.IGNORE; }
            } else if (mode==Mode.NAVIGATION) { clearPending();mode=Mode.IGNORE; }
            if (mode==Mode.TOUCH) forward(e);
            return true;
        }
        if (action==MotionEvent.ACTION_MOVE && mode==Mode.PENDING) {
            remember(e);
            EdgeGesture.Edge edge=gesture.recognize(x(e),y(e),dp(28));
            if (edge!=EdgeGesture.Edge.NONE && gesture.inside(x(e),y(e))) {
                mode=Mode.NAVIGATION;handler.removeCallbacks(longPressFallback);clearPending();
            } else if (gesture.distance(x(e),y(e))>ViewConfiguration.get(getContext()).getScaledTouchSlop()*2
                    && gesture.recognize(x(e),y(e),0)==EdgeGesture.Edge.NONE) {
                if (gesture.startedInside()) flush();
                else {clearPending();mode=Mode.IGNORE;handler.removeCallbacks(longPressFallback);}
            }
            return true;
        }
        if (action==MotionEvent.ACTION_UP && (mode==Mode.PENDING || mode==Mode.NAVIGATION)) {
            EdgeGesture.Edge edge=gesture.recognize(x(e),y(e),dp(28));
            if (edge!=EdgeGesture.Edge.NONE && gesture.inside(x(e),y(e))) {
                Log.d("RemoteGesture","EDGE_ACTION_"+edge.name());listener.navigate(edge);
            } else if (mode==Mode.PENDING && gesture.startedInside()) {remember(e);flush();}
            handler.removeCallbacks(longPressFallback);clearPending();mode=Mode.IDLE;return true;
        }
        if (mode==Mode.TOUCH) forward(e);
        if (action==MotionEvent.ACTION_UP) {release();mode=Mode.IDLE;}
        return true;
    }
    private void flush() {
        handler.removeCallbacks(longPressFallback);mode=Mode.TOUCH;
        for (MotionEvent e:pending)forward(e);
        clearPending();
    }
    private void forward(MotionEvent e) {
        int action=e.getActionMasked(),index=e.getActionIndex();
        if (action==MotionEvent.ACTION_MOVE) for(int i=0;i<e.getPointerCount();i++)point(e,i,2);
        else if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN)point(e,index,0);
        else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)point(e,index,1);
    }
    private void point(MotionEvent e,int i,int action) {
        int id=e.getPointerId(i);
        float localX=e.getX(i)-frame.left,localY=e.getY(i)-frame.top;
        if(action==0 && (localX<0 || localY<0 || localX>=frame.width() || localY>=frame.height()))return;
        if (action!=0 && pressed.get(id)==null) return;
        int x=EdgeGesture.map(e.getX(i)-frame.left,frame.width(),remoteWidth);
        int y=EdgeGesture.map(e.getY(i)-frame.top,frame.height(),remoteHeight);
        if(action==1)pressed.remove(id);else pressed.put(id,new int[]{x,y});
        listener.touch(action,id,x,y,remoteWidth,remoteHeight,action==1?0:e.getPressure(i));
    }
    private void release() {
        for(int i=0;i<pressed.size();i++) {
            int[] p=pressed.valueAt(i);listener.touch(1,pressed.keyAt(i),p[0],p[1],remoteWidth,remoteHeight,0);
        }
        pressed.clear();
    }
}
