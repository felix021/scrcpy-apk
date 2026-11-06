package cn.felix021.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;
public class EdgeGestureTest {
    @Test public void allFourEdgesIncludeStartsOutsideTheImage() {
        EdgeGesture g=new EdgeGesture();
        g.begin(200,-20,400,800,24);assertTrue(g.candidate());assertFalse(g.startedInside());
        assertEquals(EdgeGesture.Edge.TOP,g.recognize(200,100,28));
        g.begin(200,820,400,800,24);assertEquals(EdgeGesture.Edge.BOTTOM,g.recognize(200,700,28));
        g.begin(-20,400,400,800,24);assertEquals(EdgeGesture.Edge.LEFT,g.recognize(100,400,28));
        g.begin(420,400,400,800,24);assertEquals(EdgeGesture.Edge.RIGHT,g.recognize(300,400,28));
    }
    @Test public void ordinaryParallelOutwardAndReversedMovementsDoNotNavigate() {
        EdgeGesture g=new EdgeGesture();g.begin(200,400,400,800,24);assertFalse(g.candidate());
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(300,400,28));
        g.begin(2,400,400,800,24);
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(2,500,28));
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(-40,400,28));
        assertEquals(EdgeGesture.Edge.LEFT,g.recognize(100,400,28));
        assertEquals(EdgeGesture.Edge.NONE,g.recognize(3,400,28));
    }
    @Test public void cornerUsesActualDirectionAndOrdinaryCoordinatesAreNotShifted() {
        EdgeGesture g=new EdgeGesture();g.begin(2,2,400,800,24);
        assertEquals(EdgeGesture.Edge.LEFT,g.recognize(80,3,28));
        assertEquals(EdgeGesture.Edge.TOP,g.recognize(3,80,28));
        assertEquals(3,EdgeGesture.map(3,400,400));assertEquals(0,EdgeGesture.map(-50,400,720));
        assertEquals(719,EdgeGesture.map(500,400,720));assertEquals(719,EdgeGesture.map(399,400,720));
    }
}
