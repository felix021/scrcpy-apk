package cn.felix021.scrcpy;

/** Maps a gesture starting at a displayed video edge onto the exact remote edge. */
final class EdgeGesture {
    enum Edge { NONE, TOP, BOTTOM, LEFT, RIGHT }
    private Edge edge = Edge.NONE;
    private float startX, startY, offsetX, offsetY;
    private boolean recognized;

    void begin(float x, float y, int width, int height, float band) {
        startX = x; startY = y; offsetX = offsetY = 0; recognized = false;
        edge = Edge.NONE;
        float closest = band;
        if (y <= closest) { edge = Edge.TOP; closest = y; }
        if (height - 1 - y < closest) { edge = Edge.BOTTOM; closest = height - 1 - y; }
        if (x < closest) { edge = Edge.LEFT; closest = x; }
        if (width - 1 - x < closest) edge = Edge.RIGHT;
        if (edge == Edge.LEFT) offsetX = -x;
        if (edge == Edge.RIGHT) offsetX = width - 1 - x;
        if (edge == Edge.TOP) offsetY = -y;
        if (edge == Edge.BOTTOM) offsetY = height - 1 - y;
    }

    Edge recognize(float x, float y, float slop) {
        if (recognized) return Edge.NONE;
        float dx = x - startX, dy = y - startY;
        boolean inward = (edge == Edge.TOP && dy > slop && dy > Math.abs(dx))
                || (edge == Edge.BOTTOM && -dy > slop && -dy > Math.abs(dx))
                || (edge == Edge.LEFT && dx > slop && dx > Math.abs(dy))
                || (edge == Edge.RIGHT && -dx > slop && -dx > Math.abs(dy));
        if (!inward) return Edge.NONE;
        recognized = true;
        return edge;
    }

    int x(float x, int localWidth, int remoteWidth) { return map(x + offsetX, localWidth, remoteWidth); }
    int y(float y, int localHeight, int remoteHeight) { return map(y + offsetY, localHeight, remoteHeight); }
    private static int map(float value, int local, int remote) {
        if (local <= 1 || remote <= 1) return 0;
        return Math.round(Math.max(0, Math.min(local - 1, value)) * (remote - 1) / (local - 1));
    }
}
