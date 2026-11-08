package cn.felix021.scrcpy.adb;

import com.tananaev.adblib.AdbCrypto;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

/** One reader and serialized writers. Network reads never hold the SSL lock. */
public final class AdbTlsTransport implements Closeable {
    private final Socket socket;
    private final InputStream rawInput;
    private final OutputStream rawOutput;
    private final Object sslLock=new Object(),writeLock=new Object();
    private volatile boolean closed,secure;
    private long tls;
    public final InputStream input=new InputStream(){
        @Override public int read() throws IOException { byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            bounds(b,off,len);if(len==0)return 0;
            if(!secure)return rawInput.read(b,off,len);
            while(true){
                int result;
                synchronized(sslLock){ensureOpen();result=NativeCrypto.tlsIO(tls,b,off,len,false);drain();}
                if(result>0)return result;
                if(result==0)return -1;
                if(result!=-2)throw new IOException("ADB TLS read failed");
                receive();
            }
        }
    };
    public final OutputStream output=new OutputStream(){
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
        @Override public void write(byte[] b,int off,int len) throws IOException {
            bounds(b,off,len);
            synchronized(writeLock){
                if(!secure){rawOutput.write(b,off,len);return;}
                while(len>0){
                    int n=Math.min(len,16384),result;
                    synchronized(sslLock){ensureOpen();result=NativeCrypto.tlsIO(tls,b,off,n,true);drain();}
                    if(result<=0)throw new IOException("ADB TLS write failed");off+=result;len-=result;
                }
            }
        }
        @Override public void flush() throws IOException { synchronized(writeLock){rawOutput.flush();} }
    };
    public AdbTlsTransport(Socket socket) throws IOException {
        this.socket=socket;socket.setTcpNoDelay(true);rawInput=socket.getInputStream();rawOutput=socket.getOutputStream();
    }
    public void upgrade(AdbCrypto identity) throws IOException {
        byte[] key=identity.getPrivateKeyEncoded();
        try { synchronized(sslLock){if(closed || tls!=0)throw new IOException("Invalid TLS upgrade");tls=NativeCrypto.tlsCreate(key);} }
        finally { Arrays.fill(key,(byte)0); }
        while(true){
            int result;
            synchronized(sslLock){ensureOpen();result=NativeCrypto.tlsHandshake(tls);drain();}
            if(result==1){secure=true;return;}
            if(result<0)throw new IOException("ADB TLS handshake rejected; pair this device first");
            receive();
        }
    }
    byte[] exportedKey() throws IOException { synchronized(sslLock){ensureOpen();return NativeCrypto.tlsExport(tls);} }
    private void receive() throws IOException {
        byte[] incoming=new byte[32768];int n=rawInput.read(incoming);
        if(n<0)throw new EOFException("ADB TLS connection closed");
        synchronized(sslLock){ensureOpen();NativeCrypto.tlsFeed(tls,incoming,n);}
    }
    // Called inside sslLock so wire records retain the order of SSL operations.
    private void drain() throws IOException {
        byte[] record;while((record=NativeCrypto.tlsDrain(tls)).length>0)rawOutput.write(record);
        rawOutput.flush();
    }
    private void ensureOpen() throws IOException { if(closed || tls==0)throw new IOException("ADB TLS transport closed"); }
    private static void bounds(byte[] b,int off,int len){if(off<0 || len<0 || off>b.length-len)throw new IndexOutOfBoundsException();}
    @Override public void close() throws IOException {
        closed=true;
        try { socket.close(); }
        finally { synchronized(sslLock){if(tls!=0){NativeCrypto.tlsFree(tls);tls=0;}} }
    }
}
