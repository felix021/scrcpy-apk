package cn.felix021.scrcpy;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class MicForwarder {
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> frames=new ArrayBlockingQueue<>(4);
    private final Consumer<String> warning;
    private volatile AdbStream shell,stream;
    private volatile AudioRecord recorder;
    private volatile AcousticEchoCanceler echo;
    private volatile Thread sender,capture;
    private volatile String serverLog="";
    MicForwarder(android.content.Context context,AdbConnection connection,String path,String name,Consumer<String> warning) {
        this.warning=warning;
        sender=new Thread(() -> run(context,connection,path,name),"scrcpy-mic-send");sender.start();
    }
    private void run(android.content.Context context,AdbConnection connection,String path,String name) {
        CountDownLatch ready=new CountDownLatch(1);AtomicBoolean started=new AtomicBoolean();
        try {
            shell=connection.open("shell:CLASSPATH="+path+" app_process / cn.felix021.scrcpy.MicServer "+name+" "+path);
            new Thread(() -> {
                try {
                    StringBuilder pending=new StringBuilder();
                    while(!closed.get()) {
                        pending.append(new String(shell.read(),StandardCharsets.UTF_8));
                        if(pending.indexOf("MIC_READY")>=0){started.set(true);ready.countDown();}
                        serverLog=pending.substring(Math.max(0,pending.length()-1500));
                        if(pending.length()>3000)pending.delete(0,pending.length()-1500);
                    }
                }catch(Exception ignored){}
                finally {ready.countDown();if(!closed.get())fail("远端麦克风服务已结束");}
            },"scrcpy-mic-log").start();
            if(!ready.await(15,TimeUnit.SECONDS) || !started.get())throw new IllegalStateException("远端不支持麦克风转发 "+serverLog);
            if(closed.get())return;
            stream=connection.open("localabstract:"+name);
            int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("录音权限未授予");
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,6400));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("本机麦克风不可用");
            if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(recorder.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}
            recorder.startRecording();
            if(closed.get())return;
            capture=new Thread(this::capture,"scrcpy-mic-record");capture.start();
            android.util.Log.i("ScrcpyAudio","MIC_STARTED");
            while(!closed.get()) {
                byte[] first=frames.poll(100,TimeUnit.MILLISECONDS);if(first==null)continue;
                ByteArrayOutputStream batch=new ByteArrayOutputStream();batch.write(first);
                while(frames.peek()!=null && batch.size()+frames.peek().length<=4096) {
                    byte[] next=frames.poll();if(next!=null)batch.write(next);
                }
                stream.write(batch.toByteArray());
            }
        }catch(Exception e){if(!closed.get())fail(e.getMessage());}
        finally {close();releaseRecorder();}
    }
    private void capture() {
        try {
            short[] samples=new short[480];
            while(!closed.get()) {
                int n=recorder.read(samples,0,samples.length);if(n<0)throw new IllegalStateException("录音中断："+n);
                if(n==0)continue;
                ByteBuffer packet=ByteBuffer.allocate(n*2+2);packet.putShort((short)(n*2));packet.order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<n;i++)packet.putShort(samples[i]);
                if(!frames.offer(packet.array())){frames.poll();frames.offer(packet.array());}
            }
        }catch(Exception e){if(!closed.get())fail(e.getMessage());}
    }
    private void fail(String message) {
        android.util.Log.e("ScrcpyAudio","MIC_FAILED "+message+" "+serverLog);
        if(close())warning.accept("麦克风转发不可用，请检查录音权限及远端音频路由支持");
    }
    boolean close() {
        if(!closed.compareAndSet(false,true))return false;
        AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(Exception ignored){}
        AdbStream s=stream;if(s!=null)try{s.close();}catch(Exception ignored){}
        AdbStream command=shell;if(command!=null)try{command.close();}catch(Exception ignored){}
        Thread t=sender;if(t!=null && t!=Thread.currentThread())t.interrupt();
        return true;
    }
    private void releaseRecorder() {
        boolean interrupted=Thread.interrupted();
        Thread t=capture;
        if(t!=null && t!=Thread.currentThread())while(t.isAlive())try{t.join();}catch(InterruptedException e){interrupted=true;}
        AcousticEchoCanceler e=echo;if(e!=null)e.release();
        AudioRecord r=recorder;if(r!=null)r.release();
        if(interrupted)Thread.currentThread().interrupt();
    }
}
