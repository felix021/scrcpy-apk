package cn.felix021.scrcpy;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Looper;
import java.io.DataInputStream;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell entry point, loaded from the client APK without installing it remotely.
 * A recorder mix supplies PCM to recording apps, not to the remote loudspeaker. */
public final class MicServer {
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> frames=new ArrayBlockingQueue<>(4);
    private volatile LocalServerSocket server;
    private volatile LocalSocket socket;
    private AudioTrack track;
    private Object policy;
    private Class<?> policyType;
    private AudioManager manager;

    public static void main(String[] args) {
        MicServer server=new MicServer();
        try {server.run(args[0]);}
        catch(Throwable e){e.printStackTrace(System.err);}
        finally {server.close();if(args.length>1)new File(args[1]).delete();}
        System.exit(0);
    }
    private void run(String name) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context system=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        Context context=system.createPackageContext("com.android.shell",0);
        manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        Class<?> ruleType=Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilder=Class.forName(ruleType.getName()+"$Builder");
        Object rules=ruleBuilder.getConstructor().newInstance();
        int[] sources=Build.VERSION.SDK_INT>=29 ? new int[]{1,5,6,7,9,10} : new int[]{1,5,6,7,9};
        for(int source:sources) {
            AudioAttributes.Builder attributes=new AudioAttributes.Builder();
            attributes.getClass().getMethod("setInternalCapturePreset",int.class).invoke(attributes,source);
            ruleBuilder.getMethod("addRule",AudioAttributes.class,int.class).invoke(rules,attributes.build(),2);
        }
        Object rule=ruleBuilder.getMethod("build").invoke(rules);
        Class<?> mixType=Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilder=Class.forName(mixType.getName()+"$Builder");
        Object mixes=mixBuilder.getConstructor(ruleType).newInstance(rule);
        AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        mixBuilder.getMethod("setFormat",AudioFormat.class).invoke(mixes,format);
        mixBuilder.getMethod("setRouteFlags",int.class).invoke(mixes,2); // LOOP_BACK
        Object mix=mixBuilder.getMethod("build").invoke(mixes);
        policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilder=Class.forName(policyType.getName()+"$Builder");
        Object builder=policyBuilder.getConstructor(Context.class).newInstance(context);
        policyBuilder.getMethod("addMix",mixType).invoke(builder,mix);
        policy=policyBuilder.getMethod("build").invoke(builder);
        int status=(Integer)manager.getClass().getMethod("registerAudioPolicy",policyType).invoke(manager,policy);
        if(status!=0)throw new IllegalStateException("Audio routing permission denied: "+status);
        track=(AudioTrack)policyType.getMethod("createAudioTrackSource",mixType).invoke(policy,mix);
        if(track==null || track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Microphone input unavailable");
        track.play();server=new LocalServerSocket(name);
        new Thread(() -> {try {while(System.in.read()!=-1){}}catch(Exception ignored){}finally{closeIo();}},"mic-owner").start();
        System.out.println("MIC_READY");System.out.flush();
        socket=server.accept();
        Thread output=new Thread(this::play,"mic-inject");output.start();
        try {
            DataInputStream input=new DataInputStream(socket.getInputStream());
            while(!closed.get()) {
                int length=input.readUnsignedShort();
                if(length<2 || length>4096 || (length&1)!=0)throw new IllegalArgumentException("Invalid microphone frame");
                byte[] bytes=new byte[length];input.readFully(bytes);
                if(!frames.offer(bytes)){frames.poll();frames.offer(bytes);}
            }
        } finally {closeIo();output.interrupt();output.join(1000);}
    }
    private void play() {
        try {
            while(!closed.get()) {
                byte[] frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;
                int offset=0;long deadline=android.os.SystemClock.uptimeMillis()+100;
                while(offset<frame.length && !closed.get()) {
                    int written=track.write(frame,offset,frame.length-offset,AudioTrack.WRITE_NON_BLOCKING);
                    if(written<0)throw new IllegalStateException("Microphone track write failed: "+written);
                    offset+=written;
                    // No app may be recording yet. Bound latency instead of blocking the network.
                    if(written==0){if(android.os.SystemClock.uptimeMillis()>=deadline)break;Thread.sleep(3);}
                }
            }
        } catch(InterruptedException ignored) { }
        catch(Exception e){e.printStackTrace(System.err);closeIo();}
    }
    private void closeIo() {
        closed.set(true);
        if(socket!=null)try{socket.close();}catch(Exception ignored){}
        if(server!=null)try{server.close();}catch(Exception ignored){}
    }
    private void close() {
        closeIo();
        if(track!=null)try{track.release();}catch(Exception ignored){}
        if(policy!=null && manager!=null)try{manager.getClass().getMethod("unregisterAudioPolicy",policyType).invoke(manager,policy);}catch(Exception ignored){}
    }
}
