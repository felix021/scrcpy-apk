package cn.felix021.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class EdgeGestureTest {
    @Test public void allFourEdgesMapExactlyAndRecognizeInwardMotion() {
        EdgeGesture g = new EdgeGesture();
        g.begin(200, 3, 400, 800, 8);
        assertEquals(0, g.y(3,800,1600));
        assertEquals(EdgeGesture.Edge.TOP, g.recognize(202,30,8));
        g.begin(200,796,400,800,8);
        assertEquals(1599,g.y(796,800,1600));
        assertEquals(EdgeGesture.Edge.BOTTOM,g.recognize(201,760,8));
        g.begin(3,400,400,800,8);
        assertEquals(0,g.x(3,400,720));
        assertEquals(EdgeGesture.Edge.LEFT,g.recognize(30,401,8));
        g.begin(396,400,400,800,8);
        assertEquals(719,g.x(396,400,720));
        assertEquals(EdgeGesture.Edge.RIGHT,g.recognize(360,401,8));
    }
    @Test public void translationPersistsAcrossTheWholeGestureWithoutDownMoveJump() {
        EdgeGesture g = new EdgeGesture(); g.begin(3,400,400,800,8);
        assertEquals(0,g.x(3,400,400)); assertEquals(30,g.x(33,400,400));
        assertEquals(400,g.y(400,800,800));
        assertEquals(EdgeGesture.Edge.LEFT,g.recognize(33,400,8));
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(43,400,8));
    }
    @Test public void ordinaryTouchesAndParallelOrOutwardMovesDoNotTriggerEdgeGesture() {
        EdgeGesture g = new EdgeGesture(); g.begin(200,400,400,800,8);
        assertEquals(200,g.x(200,400,400)); assertEquals(EdgeGesture.Edge.NONE,g.recognize(250,400,8));
        g.begin(3,400,400,800,8);
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(2,450,8));
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(0,400,1));
        g.begin(200,3,400,800,8);
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(200,5,8));
    }
}
