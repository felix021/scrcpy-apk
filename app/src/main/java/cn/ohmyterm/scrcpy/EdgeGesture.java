package cn.ohmyterm.scrcpy;

/** Single-finger edge navigation, in coordinates relative to the displayed video. */
final class EdgeGesture {
    enum Edge { NONE, TOP, BOTTOM, LEFT, RIGHT }
    private float startX, startY;
    private int width, height;
    private boolean top, bottom, left, right;

    void begin(float x, float y, int width, int height, float band) {
        startX=x; startY=y; this.width=width; this.height=height;
        top=y<=band; bottom=y>=height-1-band; left=x<=band; right=x>=width-1-band;
    }
    boolean candidate() { return top || bottom || left || right; }
    boolean startedInside() { return inside(startX,startY); }
    boolean inside(float x,float y) { return x>=0 && y>=0 && x<width && y<height; }
    float distance(float x,float y) { return (float)Math.hypot(x-startX,y-startY); }
    Edge recognize(float x,float y,float threshold) {
        float dx=x-startX,dy=y-startY;
        if (Math.abs(dy)>Math.abs(dx)*1.15f) {
            if (top && dy>threshold) return Edge.TOP;
            if (bottom && -dy>threshold) return Edge.BOTTOM;
        } else if (Math.abs(dx)>Math.abs(dy)*1.15f) {
            if (left && dx>threshold) return Edge.LEFT;
            if (right && -dx>threshold) return Edge.RIGHT;
        }
        return Edge.NONE;
    }
    static int map(float value,int local,int remote) {
        if (local<=1 || remote<=1) return 0;
        return Math.round(Math.max(0,Math.min(local-1,value))*(remote-1)/(local-1));
    }
}
