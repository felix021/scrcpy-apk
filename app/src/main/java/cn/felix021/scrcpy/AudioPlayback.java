package cn.felix021.scrcpy;

import android.content.Context;
import android.media.*;
import com.tananaev.adblib.AdbStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** AAC from scrcpy's separate audio socket, played with a bounded PCM buffer. */
final class AudioPlayback {
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final AdbStream stream;
    private final Consumer<String> warning;
    private final AudioManager manager;
    private volatile boolean focused=true;
    private final AudioManager.OnAudioFocusChangeListener focus=change -> focused=change!=AudioManager.AUDIOFOCUS_LOSS && change!=AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
    private volatile MediaCodec codec;
    private volatile AudioTrack track;
    private volatile Thread output;
    private final Thread input;
    private final boolean standalone;
    private final java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);

    AudioPlayback(Context context,AdbStream stream,boolean standalone,Consumer<String> warning) {
        this.stream=stream;this.standalone=standalone;this.warning=warning;manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        input=new Thread(this::run,"scrcpy-audio-input");input.start();
    }
    private void run() {
        try {
            DataInputStream data=new DataInputStream(new InputStream() {
                private byte[] packet=new byte[0];private int offset;
                @Override public int read() throws IOException {byte[] b=new byte[1];return read(b,0,1)==-1 ? -1 : b[0]&255;}
                @Override public int read(byte[] b,int off,int len)throws IOException {
                    if(len==0)return 0;
                    while(offset==packet.length) {
                        if(stopped.get())return -1;
                        try{packet=stream.read();offset=0;}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    }
                    int n=Math.min(len,packet.length-offset);System.arraycopy(packet,offset,b,off,n);offset+=n;return n;
                }
            });
            if(standalone && data.readUnsignedByte()!=0)throw new IOException(UiText.text(R.string.invalid_audio_service_handshake));
            int id=data.readInt();
            if(id==0 || id==1)throw new IOException(UiText.text(R.string.remote_system_audio_capture_is_unavailable_requires_android_11_o));
            if(id!=0x00616163)throw new IOException(UiText.text(R.string.unsupported_remote_audio_codec));
            while(!stopped.get()) {
                long pts=data.readLong();int length=data.readInt();
                if(length<1 || length>1024*1024)throw new IOException(UiText.text(R.string.invalid_audio_data));
                byte[] packet=new byte[length];data.readFully(packet);
                boolean config=(pts&(1L<<62))!=0;
                if(codec==null) {
                    if(!config)throw new IOException(UiText.text(R.string.missing_audio_codec_configuration));
                    MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48000,2);
                    format.setByteBuffer("csd-0",ByteBuffer.wrap(packet));
                    codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    codec.configure(format,null,null,0);codec.start();
                    manager.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    output=new Thread(this::play,"scrcpy-audio-output");output.start();continue;
                }
                int index;
                do {if(stopped.get())return;index=codec.dequeueInputBuffer(10000);}while(index<0);
                ByteBuffer buffer=codec.getInputBuffer(index);
                if(buffer==null || buffer.capacity()<length)throw new IOException(UiText.text(R.string.audio_frame_too_large));
                buffer.clear();buffer.put(packet);
                codec.queueInputBuffer(index,0,length,pts&((1L<<61)-1),config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
            }
        }catch(Exception e){if(!stopped.get())fail(e.getMessage());}
        finally {stop();release();}
    }
    private void play() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean logged=false;
        try {
            while(!stopped.get()) {
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format=codec.getOutputFormat();int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int mask=channels==1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                    if(channels<1 || channels>2)throw new IOException(UiText.text(R.string.unsupported_audio_channels));
                    AudioTrack old=track;if(old!=null)old.release();
                    track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT),rate*channels/5))
                            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                    track.play();
                } else if(index>=0) {
                    ByteBuffer pcm=codec.getOutputBuffer(index);
                    if(pcm!=null && info.size>0 && track!=null && focused) {
                        pcm.position(info.offset);pcm.limit(info.offset+info.size);
                        while(pcm.hasRemaining() && !stopped.get() && focused) {
                            int n=track.write(pcm,pcm.remaining(),AudioTrack.WRITE_NON_BLOCKING);
                            if(n<0)throw new IOException(UiText.text(R.string.local_audio_playback_failed));
                            if(n==0)Thread.sleep(2);
                        }
                        if(!logged){android.util.Log.i("ScrcpyAudio","PLAYBACK_PCM");logged=true;}
                    }
                    codec.releaseOutputBuffer(index,false);
                }
            }
        }catch(Exception e){if(!stopped.get())fail(e.getMessage());}
    }
    private void fail(String message) {
        android.util.Log.e("ScrcpyAudio","PLAYBACK_FAILED "+message);
        if(stop())warning.accept(UiText.text(R.string.audio_forwarding_unavailable)+message);
    }
    private boolean stop() {
        if(!stopped.compareAndSet(false,true))return false;
        try{stream.close();}catch(Exception ignored){}
        if(input!=Thread.currentThread())input.interrupt();
        return true;
    }
    void close() {stop();}
    void awaitClosed() throws InterruptedException {released.await(2,java.util.concurrent.TimeUnit.SECONDS);}
    private void release() {
        boolean interrupted=Thread.interrupted();Thread t=output;
        if(t!=null && t!=Thread.currentThread())while(t.isAlive())try{t.join();}catch(InterruptedException e){interrupted=true;}
        AudioTrack a=track;if(a!=null)a.release();
        MediaCodec c=codec;if(c!=null){try{c.stop();}catch(Exception ignored){}c.release();}
        manager.abandonAudioFocus(focus);
        released.countDown();
        if(interrupted)Thread.currentThread().interrupt();
    }
}
