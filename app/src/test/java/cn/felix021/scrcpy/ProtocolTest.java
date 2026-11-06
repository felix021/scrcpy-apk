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
    @Test public void controlShiftChordPreservesOrderingAndReleasesInReverse() {
        ByteBuffer b=ByteBuffer.wrap(Protocol.chord(31,0x3041));
        int[] actions={0,0,0,1,1,1},codes={113,59,31,31,59,113},metas={0x3000,0x3041,0x3041,0x3041,0x3000,0};
        assertEquals(84,b.remaining());
        for(int i=0;i<codes.length;i++) {
            assertEquals(0,b.get());assertEquals(actions[i],b.get());assertEquals(codes[i],b.getInt());
            assertEquals(0,b.getInt());assertEquals(metas[i],b.getInt());
        }
    }
    @Test public void pasteUsesUtf8ByteLengthAndClipboardPasteFlag() {
        String text="你好🙂";byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer b=ByteBuffer.wrap(Protocol.paste(text));assertEquals(9,b.get());assertEquals(0,b.getLong());
        assertEquals(1,b.get());assertEquals(bytes.length,b.getInt());byte[] value=new byte[b.remaining()];b.get(value);assertArrayEquals(bytes,value);
    }
    @Test(expected=IllegalArgumentException.class) public void pasteIsBounded() {
        char[] chars=new char[65537];java.util.Arrays.fill(chars,'x');Protocol.paste(new String(chars));
    }
}
