package cn.ohmyterm.scrcpy.probe;

import android.app.Activity;
import android.media.*;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class AudioActivity extends Activity {
    private volatile boolean running=true;
    private Thread worker;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TextView text=new TextView(this);text.setText("Audio validation");setContentView(text);
        worker=new Thread(() -> {
            if("both".equals(getIntent().getStringExtra("mode"))){new Thread(this::tone).start();record();}
            else if("record".equals(getIntent().getStringExtra("mode")))record();else tone();
        });worker.start();
    }
    private void record() {
        AudioRecord record=new AudioRecord(MediaRecorder.AudioSource.MIC,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,19200);
        try {
            record.startRecording();short[] data=new short[4800];
            while(running) {
                int n=record.read(data,0,data.length);if(n<=0)break;
                double power=0,re=0,im=0;
                for(int i=0;i<n;i++){power+=(double)data[i]*data[i];re+=data[i]*Math.cos(2*Math.PI*997*i/48000);im+=data[i]*Math.sin(2*Math.PI*997*i/48000);}
                double rms=Math.sqrt(power/n),amplitude=2*Math.hypot(re,im)/n;
                Log.i("ScrcpyAudioProbe","RECORD rms="+(int)rms+" tone997="+(int)amplitude);
            }
        }catch(Exception e){Log.e("ScrcpyAudioProbe","RECORD_FAILED",e);}
        finally {try{record.stop();}catch(Exception ignored){}record.release();}
    }
    private void tone() {
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(19200).setTransferMode(AudioTrack.MODE_STREAM).build();
        try {
            short[] data=new short[960];long count=0;track.play();
            while(running){for(int i=0;i<data.length;i++)data[i]=(short)(Math.sin((count++)*2*Math.PI*getIntent().getIntExtra("frequency",1499)/48000)*12000);if(track.write(data,0,data.length)<0)break;}
        }finally{track.release();}
    }
    @Override public void onDestroy(){running=false;super.onDestroy();}
}
