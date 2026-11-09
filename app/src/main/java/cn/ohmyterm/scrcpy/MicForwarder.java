package cn.ohmyterm.scrcpy;

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
    private final CountDownLatch finished=new CountDownLatch(1),remoteStopped=new CountDownLatch(1);
    MicForwarder(android.content.Context context,AdbConnection connection,String path,String name,Consumer<String> warning) {
        this.warning=warning;
        sender=new Thread(() -> run(context,connection,path,name),"scrcpy-mic-send");sender.start();
    }
    private void run(android.content.Context context,AdbConnection connection,String path,String name) {
        CountDownLatch ready=new CountDownLatch(1);AtomicBoolean started=new AtomicBoolean();
        try {
            shell=connection.open("shell:CLASSPATH="+path+" app_process / cn.ohmyterm.scrcpy.MicServer "+name+" "+path+" keep");
            new Thread(() -> {
                try {
                    StringBuilder pending=new StringBuilder();
                    while(true) {
                        pending.append(new String(shell.read(),StandardCharsets.UTF_8));
                        if(pending.indexOf("MIC_CLOSED")>=0)remoteStopped.countDown();
                        if(pending.indexOf("MIC_READY")>=0){started.set(true);ready.countDown();}
                        serverLog=pending.substring(Math.max(0,pending.length()-1500));
                        if(pending.length()>3000)pending.delete(0,pending.length()-1500);
                    }
                }catch(Exception ignored){}
                finally {ready.countDown();if(!closed.get())fail(UiText.text(R.string.remote_microphone_service_stopped));}
            },"scrcpy-mic-log").start();
            if(!ready.await(15,TimeUnit.SECONDS) || !started.get())throw new IllegalStateException(UiText.text(R.string.remote_microphone_forwarding_is_unavailable)+serverLog);
            if(closed.get())return;
            stream=connection.open("localabstract:"+name);
            int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException(UiText.text(R.string.microphone_permission_not_granted));
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,6400));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException(UiText.text(R.string.local_microphone_is_unavailable));
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
        finally {
            close();boolean interrupted=Thread.interrupted();releaseRecorder();
            AdbStream s=stream;
            if(s!=null)try{s.write(new byte[]{0,0});remoteStopped.await(1200,TimeUnit.MILLISECONDS);}catch(Exception ignored){}
            if(s!=null)try{s.close();}catch(Exception ignored){}
            AdbStream command=shell;if(command!=null)try{command.close();}catch(Exception ignored){}
            finished.countDown();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private void capture() {
        try {
            short[] samples=new short[480];
            while(!closed.get()) {
                int n=recorder.read(samples,0,samples.length);if(n<0)throw new IllegalStateException(UiText.text(R.string.recording_interrupted)+n);
                if(n==0)continue;
                ByteBuffer packet=ByteBuffer.allocate(n*2+2);packet.putShort((short)(n*2));packet.order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<n;i++)packet.putShort(samples[i]);
                if(!frames.offer(packet.array())){frames.poll();frames.offer(packet.array());}
            }
        }catch(Exception e){if(!closed.get())fail(e.getMessage());}
    }
    private void fail(String message) {
        android.util.Log.e("ScrcpyAudio","MIC_FAILED "+message+" "+serverLog);
        if(close())warning.accept(UiText.text(R.string.microphone_forwarding_is_unavailable_check_recording_permission));
    }
    boolean close() {
        if(!closed.compareAndSet(false,true))return false;
        AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(Exception ignored){}
        Thread t=sender;if(t!=null && t!=Thread.currentThread())t.interrupt();
        return true;
    }
    boolean isClosed(){return closed.get();}
    void awaitClosed()throws InterruptedException{finished.await(2500,TimeUnit.MILLISECONDS);}
    private void releaseRecorder() {
        boolean interrupted=Thread.interrupted();
        Thread t=capture;
        if(t!=null && t!=Thread.currentThread())while(t.isAlive())try{t.join();}catch(InterruptedException e){interrupted=true;}
        AcousticEchoCanceler e=echo;if(e!=null)e.release();
        AudioRecord r=recorder;if(r!=null)r.release();
        if(interrupted)Thread.currentThread().interrupt();
    }
}
