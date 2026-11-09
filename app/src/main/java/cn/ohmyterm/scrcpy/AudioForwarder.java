package cn.ohmyterm.scrcpy;

import android.content.Context;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Separate audio server lets capture stop and resume without replacing video. */
final class AudioForwarder {
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CountDownLatch stop=new CountDownLatch(1),finished=new CountDownLatch(1);
    private final Consumer<String> warning;
    private volatile AdbStream shell,stream;
    private volatile AudioPlayback playback;
    private volatile String log="";
    private final Thread worker;
    AudioForwarder(Context context,AdbConnection connection,String path,Consumer<String> warning) {
        this.warning=warning;worker=new Thread(() -> run(context,connection,path),"scrcpy-audio-server");worker.start();
    }
    private void run(Context context,AdbConnection connection,String path) {
        try {
            String id=String.format(Locale.ROOT,"%08x",new java.security.SecureRandom().nextInt(0x7fffffff));
            shell=connection.open("shell:"+RemoteServerProcess.owned("CLASSPATH="+path+" app_process / com.genymobile.scrcpy.Server 5.0.1 scid="+id
                    +" tunnel_forward=true video=false audio=true audio_codec=aac control=false cleanup=false send_device_meta=false"));
            new Thread(() -> {
                try {while(true){
                    log+=new String(shell.read(),java.nio.charset.StandardCharsets.UTF_8);
                    if(log.length()>2000)log=log.substring(log.length()-2000);
                }}catch(Exception ignored){}finally{if(!closed.get())fail(UiText.text(R.string.remote_audio_service_stopped));}
            },"scrcpy-audio-server-log").start();
            for(int i=0;i<100 && !closed.get();i++) {
                try{stream=connection.open("localabstract:scrcpy_"+id);break;}
                catch(java.io.IOException e){Thread.sleep(100);}
            }
            if(closed.get())return;
            if(stream==null)throw new java.io.IOException(UiText.text(R.string.remote_audio_service_did_not_start));
            playback=new AudioPlayback(context,stream,true,this::fail);
            if(closed.get())playback.close();
            stop.await();
        }catch(Exception e){if(!closed.get())fail(e.getMessage());}
        finally {
            close();boolean interrupted=Thread.interrupted();
            AudioPlayback p=playback;if(p!=null)try{p.awaitClosed();}catch(InterruptedException e){interrupted=true;}
            AdbStream s=stream;if(s!=null)try{s.close();}catch(Exception ignored){}
            AdbStream command=shell;if(command!=null)try{command.close();}catch(Exception ignored){}
            finished.countDown();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private void fail(String message) {
        android.util.Log.e("ScrcpyAudio","PLAYBACK_FAILED "+message+" "+log);
        if(close())warning.accept(UiText.text(R.string.audio_forwarding_unavailable)+message);
    }
    boolean isClosed(){return closed.get();}
    boolean close() {
        if(!closed.compareAndSet(false,true))return false;
        AudioPlayback p=playback;if(p!=null)p.close();stop.countDown();
        if(worker!=Thread.currentThread())worker.interrupt();return true;
    }
    void awaitClosed()throws InterruptedException{finished.await(2500,TimeUnit.MILLISECONDS);}
}
