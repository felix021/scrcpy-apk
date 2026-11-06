package com.tananaev.adblib;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AdbStreamTest {
    private AdbStream stream() throws Exception {
        Constructor<AdbConnection> constructor = AdbConnection.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AdbConnection connection = constructor.newInstance();
        connection.outputStream = new ByteArrayOutputStream();
        return new AdbStream(connection, 1);
    }

    @Test(timeout=2000) public void finalWriteWaitsForAckAndHasABoundedTimeout() throws Exception {
        AdbStream s=stream();s.readyForWrite();s.write(new byte[]{42});
        assertFalse(s.awaitWriteComplete(10));
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> waiting=pool.submit(() -> s.awaitWriteComplete(1000));
            synchronized(s){s.readyForWrite();s.notifyAll();}
            assertTrue(waiting.get(1,TimeUnit.SECONDS));
            s.notifyClose(true);assertFalse(s.awaitWriteComplete(10));
        } finally {pool.shutdownNow();}
    }

    @Test public void closedPeerPreservesLastPayloadThenSignalsEof() throws Exception {
        AdbStream s = stream(); s.addPayload(new byte[]{42}); s.notifyClose(true);
        assertArrayEquals(new byte[]{42}, s.read());
        try { s.read(); fail("expected EOF"); } catch (IOException expected) { }
    }

    @Test(timeout=10000) public void concurrentReadAndPeerCloseCannotStrandReader() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                AdbStream s = stream(); s.addPayload(new byte[]{42});
                CountDownLatch go = new CountDownLatch(1);
                Future<?> reader = pool.submit(() -> {
                    try {
                        go.await(); assertArrayEquals(new byte[]{42}, s.read());
                        try { s.read(); fail("expected EOF"); } catch (IOException expected) { }
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    catch (IOException e) { throw new AssertionError(e); }
                });
                Future<?> closer = pool.submit(() -> { try { go.await(); s.notifyClose(true); } catch (InterruptedException e) { throw new AssertionError(e); } });
                go.countDown(); reader.get(1, TimeUnit.SECONDS); closer.get(1, TimeUnit.SECONDS);
            }
        } finally { pool.shutdownNow(); }
    }
}
