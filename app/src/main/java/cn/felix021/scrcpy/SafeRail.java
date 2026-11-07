package cn.felix021.scrcpy;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import java.util.ArrayList;
import java.util.List;

/** Places a side rail's contents in free vertical segments between corners and
 * camera cutouts, including a camera in the middle of the edge. */
final class SafeRail extends ViewGroup {
    private final boolean controls, left;
    SafeRail(Context context, boolean controls, boolean left) {
        super(context); this.controls=controls; this.left=left;
        setContentDescription(controls ? "横屏操作栏" : "横屏连接状态");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h));
        for (int i=0;i<getChildCount();i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(Math.max(0,getMeasuredWidth()-dp(8)),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(controls ? dp(44) : Math.min(dp(120),getMeasuredHeight()),controls ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
        }
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        if (getChildCount()==0) return;
        int[] location=new int[2]; getLocationOnScreen(location);
        int top=dp(16), bottom=getHeight()-dp(16);
        WindowInsets insets=getRootWindowInsets();
        if (insets!=null && Build.VERSION.SDK_INT>=31) {
            RoundedCorner upper=insets.getRoundedCorner(left ? RoundedCorner.POSITION_TOP_LEFT : RoundedCorner.POSITION_TOP_RIGHT);
            RoundedCorner lower=insets.getRoundedCorner(left ? RoundedCorner.POSITION_BOTTOM_LEFT : RoundedCorner.POSITION_BOTTOM_RIGHT);
            if (upper!=null) top=Math.max(top,upper.getRadius()+dp(4)-location[1]);
            if (lower!=null) bottom=Math.min(bottom,getHeight()-lower.getRadius()-dp(4));
        }
        List<Rect> spans=new ArrayList<>(); spans.add(new Rect(0,top,getWidth(),bottom));
        if (insets!=null && Build.VERSION.SDK_INT>=28 && insets.getDisplayCutout()!=null) {
            for (Rect cutout:insets.getDisplayCutout().getBoundingRects()) {
                if (cutout.right<=location[0] || cutout.left>=location[0]+getWidth()) continue;
                int cutTop=cutout.top-location[1]-dp(8), cutBottom=cutout.bottom-location[1]+dp(8);
                List<Rect> free=new ArrayList<>();
                for (Rect span:spans) {
                    if (cutBottom<=span.top || cutTop>=span.bottom) {free.add(span);continue;}
                    if (cutTop>span.top) free.add(new Rect(0,span.top,getWidth(),cutTop));
                    if (cutBottom<span.bottom) free.add(new Rect(0,cutBottom,getWidth(),span.bottom));
                }
                spans=free;
            }
        }
        if (!controls) {
            View status=getChildAt(0);
            Rect best=null;
            for (Rect span:spans) if (best==null || (best.height()<status.getMeasuredHeight() && span.height()>best.height())) best=span;
            if (best!=null) status.layout(dp(4),best.top,getWidth()-dp(4),Math.min(best.bottom,best.top+status.getMeasuredHeight()));
            return;
        }
        // Divide the buttons between available segments; no button straddles a cutout.
        int cell=dp(48), capacity=0;
        for (Rect span:spans) capacity+=(span.height()+dp(4))/cell;
        if (capacity<getChildCount()) {
            int free=0;for(Rect span:spans)free+=Math.max(0,span.height());
            cell=Math.max(1,free/(getChildCount()+spans.size()-1));
        }
        int remaining=getChildCount(), index=0, slots=0;
        for(Rect span:spans)slots+=Math.max(0,(span.height()+dp(4))/cell);
        for (Rect span:spans) {
            int available=Math.max(0,(span.height()+dp(4))/cell);
            int count=slots==0 ? 0 : Math.min(available,Math.round((float)remaining*available/slots));
            slots-=available;remaining-=count;
            int y=span.top+(span.height()-count*cell+dp(4))/2;
            for(int i=0;i<count;i++) {
                View child=getChildAt(index++);child.layout(dp(4),y,getWidth()-dp(4),Math.min(span.bottom,y+cell-dp(4)));y+=cell;
            }
        }
    }
}
