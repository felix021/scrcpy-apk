package cn.felix021.scrcpy;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Test public void keyMatchesScrcpyWireFormat() {
        assertArrayEquals(new byte[]{0,0,0,0,0,82,0,0,0,0,0,0,0,0}, Protocol.key(0,82));
        byte[] up = Protocol.key(1,4); assertEquals(14,up.length); assertEquals(1,up[1]); assertEquals(4,up[5]);
    }
    @Test public void touchEncodesBigEndianPositionAndPointerIdentity() {
        ByteBuffer b = ByteBuffer.wrap(Protocol.touch(2,7,400,900,720,1600,.5f));
        assertEquals(32,b.remaining()); assertEquals(2,b.get()); assertEquals(2,b.get());
        assertEquals(7,b.getLong()); assertEquals(400,b.getInt()); assertEquals(900,b.getInt());
        assertEquals(720,b.getShort() & 65535); assertEquals(1600,b.getShort() & 65535);
        assertEquals(32768,b.getShort() & 65535); assertEquals(0,b.getInt()); assertEquals(0,b.getInt());
    }
}
