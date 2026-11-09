package cn.ohmyterm.scrcpy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts USB bulk transfers to ADB byte streams while retaining USB packet boundaries. */
final class UsbAdbStreams implements Closeable {
    static final int CHUNK=16384;
    interface BulkIo extends Closeable {
        int read(byte[] bytes,int length)throws IOException;
        void write(byte[] bytes,int offset,int length)throws IOException;
        int packetSize();
        /** Abort pending I/O; close() runs on the reader after cancellation completes. */
        void cancel();
    }
    private final BulkIo io;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ArrayDeque<byte[]> received=new ArrayDeque<>();
    private IOException failure;
    private final Thread reader;
    private byte[] current=new byte[0];
    private int position;
    final InputStream input=new InputStream(){
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            if(offset<0 || length<0 || offset>bytes.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;
            synchronized(received){
                while(position==current.length){
                    if(!received.isEmpty()){current=received.removeFirst();position=0;received.notifyAll();break;}
                    if(failure!=null)throw failure;
                    if(closed.get())throw new IOException("USB closed");
                    try{received.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("USB read interrupted",e);}
                }
                int count=Math.min(length,current.length-position);System.arraycopy(current,position,bytes,offset,count);position+=count;return count;
            }
        }
    };
    final OutputStream output=new OutputStream(){
        private byte[] frame=new byte[24];
        private int used;
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
        @Override public synchronized void write(byte[] bytes,int offset,int length)throws IOException{
            if(offset<0 || length<0 || offset>bytes.length-length)throw new IndexOutOfBoundsException();
            try{
                while(length>0){
                    if(closed.get())throw new IOException("USB closed");
                    int count=Math.min(length,frame.length-used);System.arraycopy(bytes,offset,frame,used,count);used+=count;offset+=count;length-=count;
                    if(used==24 && frame.length==24){
                        ByteBuffer header=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
                        int payload=header.getInt(12);
                        if(payload<0 || payload>1024*1024 || header.getInt(20)!=(header.getInt(0)^-1))throw new IOException("Invalid USB ADB frame");
                        if(payload>0)frame=Arrays.copyOf(frame,24+payload);
                    }
                    if(used==frame.length){
                        // adbd reads the 24-byte header separately from its payload.
                        io.write(frame,0,24);
                        for(int start=24;start<frame.length;start+=CHUNK)io.write(frame,start,Math.min(CHUNK,frame.length-start));
                        int payload=frame.length-24;
                        if(payload>0 && payload%io.packetSize()==0)io.write(frame,24,0);
                        frame=new byte[24];used=0;
                    }
                }
            }catch(IOException e){fail(e);throw e;}
        }
    };
    UsbAdbStreams(BulkIo io){
        if(io.packetSize()<=0 || CHUNK%io.packetSize()!=0)throw new IllegalArgumentException("Invalid USB packet size");
        this.io=io;reader=new Thread(this::receive,"adb-usb-read");reader.start();
    }
    private void receive(){
        try{
            byte[] buffer=new byte[CHUNK],header=new byte[24];int headerUsed=0,remaining=0;
            while(!closed.get()){
                // Device-to-host writes need not include a terminating ZLP.
                // Request exactly the header or remaining payload, as native adb does.
                int wanted=remaining>0?Math.min(CHUNK,remaining):24-headerUsed;
                int count=io.read(buffer,wanted);
                if(count<0 || count>wanted)throw new IOException("USB disconnected");
                if(count==0)continue; // A zero-length USB packet is a terminator, not EOF.
                byte[] packet;
                if(remaining==0){
                    System.arraycopy(buffer,0,header,headerUsed,count);headerUsed+=count;
                    if(headerUsed<24)continue;
                    ByteBuffer fields=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);remaining=fields.getInt(12);
                    if(remaining<0 || remaining>1024*1024 || fields.getInt(20)!=(fields.getInt(0)^-1))throw new IOException("Invalid USB ADB header");
                    packet=header.clone();headerUsed=0;
                }else{remaining-=count;packet=Arrays.copyOf(buffer,count);}
                synchronized(received){
                    while(received.size()>=8 && !closed.get())received.wait();
                    if(!closed.get()){received.addLast(packet);received.notifyAll();}
                }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(IOException e){fail(e);}
        finally{close();try{io.close();}catch(IOException ignored){}}
    }
    private void fail(IOException error){synchronized(received){if(failure==null)failure=error;received.notifyAll();}close();}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        synchronized(received){received.notifyAll();}
        io.cancel();if(reader!=Thread.currentThread())reader.interrupt();
    }
}
