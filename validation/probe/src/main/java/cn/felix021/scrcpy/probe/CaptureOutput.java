package cn.felix021.scrcpy.probe;

import android.content.Context;
import android.media.*;
import android.os.Looper;

/** Shell-only measurement of the client's actual output, independent of app logs. */
public final class CaptureOutput {
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");
        Object thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        Context shell=system.createPackageContext("com.android.shell",0);
        AudioRecord.Builder builder=new AudioRecord.Builder().setAudioSource(8)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(38400);
        builder.getClass().getMethod("setContext",Context.class).invoke(builder,shell);
        AudioRecord record=builder.build();
        AudioManager manager=(AudioManager)shell.getSystemService(Context.AUDIO_SERVICE);
        int volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            record.startRecording();manager.setStreamVolume(AudioManager.STREAM_MUSIC,manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0);System.out.println("CAPTURE_READY");System.out.flush();
            short[] samples=new short[9600];double maximum=0;boolean normalized=false;
            long end=android.os.SystemClock.uptimeMillis()+4000;
            while(android.os.SystemClock.uptimeMillis()<end) {
                int n=record.read(samples,0,samples.length);if(n<=0)throw new IllegalStateException("Audio read "+n);
                if(!normalized){
                    // REMOTE_SUBMIX changes the active output route asynchronously.
                    // Normalize its gain after the first captured buffer, not before routing.
                    int max=manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC,max-1,0);manager.setStreamVolume(AudioManager.STREAM_MUSIC,max,0);
                    normalized=true;continue;
                }
                double re=0,im=0;int frames=n/2;
                for(int i=0;i<frames;i++) {
                    double value=(samples[i*2]+samples[i*2+1])/2.0;
                    re+=value*Math.cos(2*Math.PI*1499*i/48000);im+=value*Math.sin(2*Math.PI*1499*i/48000);
                }
                maximum=Math.max(maximum,2*Math.hypot(re,im)/frames);
            }
            System.out.println("PLAYBACK_TONE1499="+(int)maximum);
        }finally{manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);record.stop();record.release();manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);}
        System.exit(0);
    }
}
