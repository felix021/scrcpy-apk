package cn.felix021.scrcpy.adb;

import com.tananaev.adblib.AdbCrypto;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** AOSP pairing protocol v1; the same persistent RSA key is used by ADB. */
public final class AdbPairing implements Closeable {
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"adb-pair-timeout");t.setDaemon(true);return t;});
    private final Socket socket=new Socket();
    private volatile AdbTlsTransport transport;
    private volatile boolean closed;
    public static final class Challenge {
        public final String service,password;
        public Challenge(){byte[] b=new byte[24];new SecureRandom().nextBytes(b);service="studio-"+hex(Arrays.copyOfRange(b,0,8));password=hex(Arrays.copyOfRange(b,8,24));}
        public String payload(){return "WIFI:T:ADB;S:"+service+";P:"+password+";;";}
        private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    }
    public String pair(String host,int port,String password,AdbCrypto identity) throws IOException {
        if(host.trim().isEmpty() || port<1 || port>65535 || password.isEmpty() || password.length()>128)throw new IOException("Invalid pairing address or code");
        ScheduledFuture<?> timeout=TIMER.schedule(() -> {try{close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);
        long pairing=0;byte[] secret=null;
        try {
            if(closed)throw new IOException("Pairing cancelled");
            socket.connect(new InetSocketAddress(host,port),10000);socket.setSoTimeout(15000);
            AdbTlsTransport tls=new AdbTlsTransport(socket);transport=tls;
            if(closed)throw new IOException("Pairing cancelled");
            tls.upgrade(identity);
            byte[] passwordBytes=password.getBytes(StandardCharsets.UTF_8),exporter=tls.exportedKey();
            secret=Arrays.copyOf(passwordBytes,passwordBytes.length+exporter.length);System.arraycopy(exporter,0,secret,passwordBytes.length,exporter.length);
            Arrays.fill(passwordBytes,(byte)0);Arrays.fill(exporter,(byte)0);
            pairing=NativeCrypto.pairCreate(secret);
            DataInputStream in=new DataInputStream(tls.input);DataOutputStream out=new DataOutputStream(tls.output);
            write(out,0,NativeCrypto.pairMessage(pairing));NativeCrypto.pairProcess(pairing,read(in,0,128));
            byte[] key=identity.getAdbPublicKeyPayload();if(key.length>8191)throw new IOException("Pairing identity too long");
            byte[] info=new byte[8192];System.arraycopy(key,0,info,1,key.length);
            write(out,1,NativeCrypto.pairCrypt(pairing,info,true));
            byte[] peer=NativeCrypto.pairCrypt(pairing,read(in,1,8208),false);
            if(peer.length!=8192 || peer[0]!=1)throw new IOException("Invalid paired-device identity");
            int end=1;while(end<peer.length && peer[end]!=0)end++;
            if(end==1 || end>129)throw new IOException("Invalid paired-device GUID");
            return new String(peer,1,end-1,StandardCharsets.UTF_8);
        }finally{
            timeout.cancel(false);if(secret!=null)Arrays.fill(secret,(byte)0);if(pairing!=0)NativeCrypto.pairFree(pairing);close();
        }
    }
    private static void write(DataOutputStream out,int type,byte[] data) throws IOException {out.writeByte(1);out.writeByte(type);out.writeInt(data.length);out.write(data);out.flush();}
    private static byte[] read(DataInputStream in,int type,int limit) throws IOException {
        int version=in.readUnsignedByte(),kind=in.readUnsignedByte(),size=in.readInt();
        if(version!=1 || kind!=type || size<1 || size>limit)throw new IOException("Invalid ADB pairing packet");
        byte[] bytes=new byte[size];in.readFully(bytes);return bytes;
    }
    @Override public void close() throws IOException {closed=true;socket.close();AdbTlsTransport t=transport;if(t!=null)t.close();}
}
