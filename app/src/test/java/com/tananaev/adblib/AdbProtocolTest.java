package com.tananaev.adblib;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class AdbProtocolTest {
    @Test public void packetSurvivesFragmentedReads() throws Exception {
        byte[] original = AdbProtocol.generateWrite(1,2,new byte[]{3,4,5});
        ByteArrayInputStream fragments = new ByteArrayInputStream(original) {
            @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(1,len)); }
        };
        AdbProtocol.AdbMessage m = AdbProtocol.AdbMessage.parseAdbMessage(fragments);
        assertTrue(AdbProtocol.validateMessage(m)); assertArrayEquals(new byte[]{3,4,5},m.payload);
    }
    @Test(expected=IOException.class) public void rejectsUnboundedPeerAllocation() throws Exception {
        byte[] packet = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(0)
                .putInt(Integer.MAX_VALUE).putInt(0).putInt(0).array();
        AdbProtocol.AdbMessage.parseAdbMessage(new ByteArrayInputStream(packet));
    }
}
