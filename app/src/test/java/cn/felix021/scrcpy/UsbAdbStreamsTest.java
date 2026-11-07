package cn.felix021.scrcpy;

import com.tananaev.adblib.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsbAdbStreamsTest {
    static final byte[] END=new byte[0];
    static final class Message {int command,arg0;byte[] payload;}
    private static Message readMessage(InputStream input)throws IOException{
        DataInputStream in=new DataInputStream(input);byte[] bytes=new byte[24];in.readFully(bytes);
        java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Message message=new Message();message.command=header.getInt(0);message.arg0=header.getInt(4);
        assertEquals(~message.command,header.getInt(20));
        message.payload=new byte[header.getInt(12)];in.readFully(message.payload);
        int sum=0;for(byte b:message.payload)sum+=b&255;assertEquals(sum,header.getInt(16));return message;
    }
    static class Bulk implements UsbAdbStreams.BulkIo {
        final BlockingQueue<byte[]> incoming=new LinkedBlockingQueue<>();
        final List<byte[]> writes=new ArrayList<>();
        final CountDownLatch disposed=new CountDownLatch(1),ninthRead=new CountDownLatch(9);
        volatile boolean cancelled,failWrite;
        public int packetSize(){return 512;}
        public int read(byte[] bytes,int length)throws IOException{
            try{byte[] data=incoming.take();ninthRead.countDown();if(data==END)return -1;assertTrue("USB read requested the wrong frame boundary",data.length<=length);System.arraycopy(data,0,bytes,0,data.length);return data.length;}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        public void write(byte[] bytes,int offset,int length)throws IOException{
            if(failWrite)throw new IOException("short USB write");writes.add(Arrays.copyOfRange(bytes,offset,offset+length));
        }
        public void cancel(){cancelled=true;incoming.offer(END);}
        public void close(){disposed.countDown();}
        void reply(byte[] frame){
            incoming.add(Arrays.copyOf(frame,24));
            for(int i=24;i<frame.length;i+=16384)incoming.add(Arrays.copyOfRange(frame,i,Math.min(frame.length,i+16384)));
            if(frame.length>24 && (frame.length-24)%512==0)incoming.add(new byte[0]);
        }
    }
    @Test public void writeSeparatesHeaderAndPayloadAndTerminatesAlignedPayload()throws Exception{
        Bulk io=new Bulk();try(UsbAdbStreams usb=new UsbAdbStreams(io)){
            byte[] payload=new byte[16384+512];new Random(3).nextBytes(payload);
            byte[] frame=AdbProtocol.generateWrite(1,2,payload);
            usb.output.write(frame,0,9);assertTrue(io.writes.isEmpty());
            usb.output.write(frame,9,15);assertTrue(io.writes.isEmpty());
            usb.output.write(frame,24,frame.length-24);
            assertEquals(Arrays.asList(24,16384,512,0),lengths(io.writes));
            ByteArrayOutputStream sent=new ByteArrayOutputStream();for(byte[] p:io.writes)sent.write(p);assertArrayEquals(frame,sent.toByteArray());
            io.writes.clear();usb.output.write(AdbProtocol.generateReady(1,2));assertEquals(Arrays.asList(24),lengths(io.writes));
            io.writes.clear();usb.output.write(AdbProtocol.generateWrite(1,2,new byte[513]));assertEquals(Arrays.asList(24,513),lengths(io.writes));
        }
        assertTrue(io.disposed.await(1,TimeUnit.SECONDS));
    }
    private List<Integer> lengths(List<byte[]> packets){List<Integer> out=new ArrayList<>();for(byte[] p:packets)out.add(p.length);return out;}
    @Test(timeout=3000) public void readsAcrossChunksAndIgnoresZeroLengthTerminators()throws Exception{
        Bulk io=new Bulk();try(UsbAdbStreams usb=new UsbAdbStreams(io)){
            byte[] payload=new byte[65536];new Random(4).nextBytes(payload);
            io.incoming.add(new byte[0]);io.reply(AdbProtocol.generateWrite(7,3,payload));io.reply(AdbProtocol.generateReady(7,3));
            Message first=readMessage(usb.input);
            assertArrayEquals(payload,first.payload);
            assertEquals(AdbProtocol.CMD_OKAY,readMessage(usb.input).command);
        }
    }
    @Test(timeout=3000) public void inputRequestsExactLengthsWithoutRequiringDeviceZlp()throws Exception{
        List<Integer> requested=Collections.synchronizedList(new ArrayList<>());
        Bulk io=new Bulk(){@Override public int read(byte[] bytes,int length)throws IOException{requested.add(length);return super.read(bytes,length);}};
        try(UsbAdbStreams usb=new UsbAdbStreams(io)){
            byte[] frame=AdbProtocol.generateWrite(1,2,new byte[512]);
            io.incoming.add(Arrays.copyOf(frame,24));io.incoming.add(Arrays.copyOfRange(frame,24,frame.length));
            assertEquals(512,readMessage(usb.input).payload.length);
            assertEquals(Integer.valueOf(24),requested.get(0));assertEquals(Integer.valueOf(512),requested.get(1));
        }
    }
    @Test(timeout=3000) public void cancellationUnblocksPendingReadAndDisposesBulkIo()throws Exception{
        Bulk io=new Bulk();UsbAdbStreams usb=new UsbAdbStreams(io);ExecutorService pool=Executors.newSingleThreadExecutor();
        try{
            Future<?> read=pool.submit(() -> {try{usb.input.read();fail("Read survived cancellation");}catch(IOException expected){}});
            usb.close();read.get(1,TimeUnit.SECONDS);assertTrue(io.cancelled);assertTrue(io.disposed.await(1,TimeUnit.SECONDS));
        }finally{usb.close();pool.shutdownNow();}
    }
    @Test(timeout=3000) public void backpressureCanBeCancelledWithoutLeakingReader()throws Exception{
        Bulk io=new Bulk();UsbAdbStreams usb=new UsbAdbStreams(io);
        for(int i=0;i<20;i++)io.incoming.add(AdbProtocol.generateReady(1,2));
        assertTrue(io.ninthRead.await(1,TimeUnit.SECONDS));usb.close();assertTrue(io.disposed.await(1,TimeUnit.SECONDS));
    }
    @Test(timeout=3000) public void failedWriteClosesBothDirections()throws Exception{
        Bulk io=new Bulk();try(UsbAdbStreams usb=new UsbAdbStreams(io)){
            io.failWrite=true;
            try{usb.output.write(AdbProtocol.generateReady(1,2));fail();}catch(IOException expected){}
            try{usb.input.read();fail();}catch(IOException expected){}
            assertTrue(io.cancelled);assertTrue(io.disposed.await(1,TimeUnit.SECONDS));
        }
    }
    @Test public void invalidFrameNeverReachesUsbDevice()throws Exception{
        Bulk io=new Bulk();try(UsbAdbStreams usb=new UsbAdbStreams(io)){
            byte[] frame=AdbProtocol.generateReady(1,2);frame[20]^=1;
            try{usb.output.write(frame);fail();}catch(IOException expected){}
            assertTrue(io.writes.isEmpty());assertTrue(io.cancelled);
        }
    }
    @Test(timeout=10000) public void rsaAuthorizationAndLogicalAdbStreamsRunOverBulkTransport()throws Exception{
        PipedInputStream peerInput=new PipedInputStream(131072);PipedOutputStream hostOutput=new PipedOutputStream(peerInput);
        Bulk io=new Bulk(){@Override public void write(byte[] b,int o,int n)throws IOException{if(n>0)hostOutput.write(b,o,n);}};
        ExecutorService pool=Executors.newSingleThreadExecutor();
        byte[] video=new byte[70000],controls=new byte[50000];new Random(5).nextBytes(video);new Random(6).nextBytes(controls);
        Future<?> peer=pool.submit(() -> {
            try{
                assertEquals(AdbProtocol.CMD_CNXN,readMessage(peerInput).command);
                io.reply(AdbProtocol.generateAuth(1,new byte[20]));
                Message signature=readMessage(peerInput);assertEquals(2,signature.arg0);assertEquals(256,signature.payload.length);
                io.reply(AdbProtocol.generateAuth(1,new byte[20]));
                Message key=readMessage(peerInput);assertEquals(3,key.arg0);assertEquals(0,key.payload[key.payload.length-1]);
                io.reply(AdbProtocol.generateMessage(AdbProtocol.CMD_CNXN,AdbProtocol.CONNECT_VERSION,65536,"device::\0".getBytes("UTF-8")));
                Message open=readMessage(peerInput);assertEquals(AdbProtocol.CMD_OPEN,open.command);
                io.reply(AdbProtocol.generateReady(9,open.arg0));io.reply(AdbProtocol.generateWrite(9,open.arg0,video));
                assertEquals(AdbProtocol.CMD_OKAY,readMessage(peerInput).command);
                Message write=readMessage(peerInput);assertArrayEquals(controls,write.payload);
                io.reply(AdbProtocol.generateReady(9,open.arg0));io.reply(AdbProtocol.generateClose(9,open.arg0));
            }catch(Exception e){throw new AssertionError(e);}
        });
        try(UsbAdbStreams usb=new UsbAdbStreams(io);AdbConnection adb=AdbConnection.create(usb.input,usb.output,usb,AdbCrypto.generateAdbKeyPair(b -> Base64.getEncoder().encodeToString(b)))){
            assertTrue(adb.connect(3,TimeUnit.SECONDS,false));
            AdbStream stream=adb.open("localabstract:usb-validation");assertArrayEquals(video,stream.read());stream.write(controls);
            peer.get(3,TimeUnit.SECONDS);
            try{stream.read();fail("Expected remote close");}catch(IOException expected){}
        }finally{pool.shutdownNow();hostOutput.close();peerInput.close();}
        assertTrue(io.disposed.await(1,TimeUnit.SECONDS));
    }
}
