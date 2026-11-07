package cn.felix021.scrcpy;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScrcpySession {
    interface Listener {
        void status(String text);
        void size(int width, int height);
        void ready(String device);
        void error(String message);
        void warning(String message);
        void settingFailed(String key);
    }
    private final Context context;
    private final Profile profile;
    private final Surface surface;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean gracefulClosing = new AtomicBoolean();
    private static final byte[] END_CONTROLS = new byte[0];
    private final AtomicBoolean rendered = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> controls = new ArrayBlockingQueue<>(256);
    private volatile Thread controlWriter;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile java.io.Closeable transport;
    private final android.hardware.usb.UsbDevice usbDevice;
    private volatile AdbConnection adb;
    private volatile AdbStream control;
    private volatile Thread worker;
    private volatile Decoder decoder;
    private volatile RemoteDeviceSettings deviceSettings;
    private volatile AudioForwarder audioForwarder;
    private volatile MicForwarder microphone;
    private volatile String serverLog = "";
    private ScheduledFuture<?> startupTimeout;
    private final java.util.concurrent.ExecutorService runtime=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger audioEpoch=new java.util.concurrent.atomic.AtomicInteger(),micEpoch=new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean connectionReady,lastLandscape;
    private boolean micUploaded;
    private String serverPath,micPath,micName;

    ScrcpySession(Context context, Profile profile, android.hardware.usb.UsbDevice usbDevice, Surface surface, Listener listener) {
        this.context = context.getApplicationContext(); this.profile = profile.copy();
        this.surface = surface; this.listener = listener;
        this.usbDevice=usbDevice;
    }

    void start() {
        startupTimeout = timer.schedule(() -> fail(UiText.text(profile.isUsb()?R.string.usb_connection_timeout:R.string.connection_timed_out_check_the_network_and_allow_usb_debugging_o)), 60, TimeUnit.SECONDS);
        worker = new Thread(this::run, "scrcpy-video"); worker.start();
    }

    private static synchronized AdbCrypto identity(Context c) throws Exception {
        AdbBase64 b64 = b -> Base64.encodeToString(b, Base64.NO_WRAP);
        try {
            return AdbCrypto.loadAdbKeyPair(b64, c.getFileStreamPath("adb-private"), c.getFileStreamPath("adb-public"));
        } catch (Exception e) {
            AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(b64);
            crypto.saveAdbKeyPair(c.getFileStreamPath("adb-private"), c.getFileStreamPath("adb-public"));
            return crypto;
        }
    }

    private void run() {
        try {
            listener.status(UiText.text(R.string.connecting_to_adb_allow_debugging_on_the_remote_device));
            AdbCrypto crypto = identity(context);
            checkOpen();
            AdbConnection connection;
            if(profile.isUsb()){
                UsbAdbStreams usb=UsbAdb.open(context,usbDevice);installTransport(usb);
                connection=AdbConnection.create(usb.input,usb.output,usb,crypto);
            }else{
                Socket s = new Socket();installTransport(s);
                s.connect(new InetSocketAddress(profile.host, profile.port), 10000);
                connection=AdbConnection.create(s,crypto);
            }
            adb = connection;
            checkOpen();
            if (!connection.connect(45, TimeUnit.SECONDS, false)) throw new IOException(UiText.text(R.string.adb_authorization_timed_out_allow_debugging_on_the_remote_device));
            checkOpen();
            listener.status(UiText.text(R.string.preparing_remote_service));
            String id = String.format(Locale.ROOT, "%08x", new java.security.SecureRandom().nextInt(0x7fffffff));
            serverPath="/data/local/tmp/scrcpy-apk-"+id+".jar";
            micPath="/data/local/tmp/scrcpy-mic-"+id+".apk";micName="scrcpy_mic_"+id;
            deviceSettings=new RemoteDeviceSettings(connection,serverPath,micPath,this::fail,listener::warning);
            deviceSettings.awake(profile.stayAwake);
            checkOpen();
            try(InputStream server=context.getAssets().open("scrcpy-server.jar")){push(connection,serverPath,server);}
            checkOpen();
            String command = "CLASSPATH=" + serverPath + " app_process / com.genymobile.scrcpy.Server 5.0.1"
                    + " scid=" + id + " tunnel_forward=true audio=false video=true"
                    + " cleanup=false clipboard_autosync=false power_on=true" + profile.serverOptions();
            AdbStream shell = connection.open("shell:" + RemoteServerProcess.owned(command));
            new Thread(() -> {
                try {
                    while (!closed.get()) {
                        String line = new String(shell.read(), StandardCharsets.UTF_8);
                        serverLog = (serverLog + line);
                        if (serverLog.length() > 3000) serverLog = serverLog.substring(serverLog.length() - 3000);
                        Log.i("ScrcpyServer", line);
                    }
                } catch (Exception ignored) { }
            }, "scrcpy-server-log").start();
            String destination = "localabstract:scrcpy_" + id;
            AdbStream video = null;
            for (int i = 0; i < 80 && !closed.get(); i++) {
                try { video = connection.open(destination); break; }
                catch (IOException e) { Thread.sleep(100); }
            }
            if (video == null) throw new IOException(UiText.text(R.string.remote_display_service_did_not_start) + serverLog);
            DataInputStream input = input(video);
            if (input.readUnsignedByte() != 0) throw new IOException(UiText.text(R.string.invalid_service_handshake));
            if (!profile.viewOnly) {
                control = connection.open(destination);
                controlWriter = new Thread(() -> {
                    try {
                        while (!closed.get()) {
                            java.io.ByteArrayOutputStream batch = new java.io.ByteArrayOutputStream();
                            byte[] first=controls.take();
                            if(first==END_CONTROLS){control.awaitWriteComplete(500);close();return;}
                            batch.write(first);
                            // ADB requires an ACK per WRTE. Batch all currently queued
                            // events so latency does not limit controls to 1 event / RTT.
                            byte[] next;
                            for (int i=1;i<128;i++) {
                                next=controls.peek();
                                if(next==null || next==END_CONTROLS || batch.size()+next.length>65536)break;
                                next=controls.poll();if(next==null)break;batch.write(next);
                            }
                            byte[] bytes = batch.toByteArray();
                            // Clipboard packets may be larger than an ADB WRTE. The
                            // control stream is byte-oriented; preserve order across chunks.
                            for (int offset = 0; offset < bytes.length; offset += 4096) {
                                control.write(java.util.Arrays.copyOfRange(bytes, offset, Math.min(offset + 4096, bytes.length)));
                            }
                        }
                    } catch (Exception e) { if (!closed.get()) fail(UiText.text(R.string.could_not_send_control_events)); }
                }, "scrcpy-control-writer");
                controlWriter.start();
                new Thread(() -> {
                    try { while (!closed.get()) control.read(); }
                    catch (Exception e) { if (!closed.get()) fail(UiText.text(R.string.control_connection_closed)); }
                }, "scrcpy-control-reader").start();
            }
            connectionReady=true;queueAudio();queueMicrophone();
            byte[] name = new byte[64]; input.readFully(name);
            String device = new String(name, StandardCharsets.UTF_8).replace("\0", "").trim();
            int codecId = input.readInt();
            String mime;
            if (codecId == 0x68323634) mime = "video/avc";
            else if (codecId == 0x68323635) mime = "video/hevc";
            else throw new IOException(UiText.text(R.string.remote_codec_unavailable_try_another_codec_or_lower_settings));
            listener.status(UiText.text(R.string.receiving) + device + UiText.text(R.string.video));
            while (!closed.get()) {
                int first = input.readInt(); int second = input.readInt(); int third = input.readInt();
                if ((first & 0x80000000) != 0) {
                    int width = second, height = third;
                    if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IOException(UiText.text(R.string.invalid_video_dimensions));
                    if (decoder != null) decoder.close();
                    listener.size(width, height);
                    decoder = new Decoder(mime, width, height, device);
                    continue;
                }
                long ptsFlags = ((long) first << 32) | (second & 0xffffffffL);
                int length = third;
                if (length < 1 || length > 16 * 1024 * 1024) throw new IOException(UiText.text(R.string.invalid_video_frame_length));
                byte[] packet = new byte[length]; input.readFully(packet);
                if (decoder == null) throw new IOException(UiText.text(R.string.missing_video_dimensions));
                boolean config = (ptsFlags & (1L << 62)) != 0;
                decoder.feed(packet, config ? 0 : ptsFlags & ((1L << 61) - 1), config);
            }
        } catch (Exception e) {
            if (!closed.get() && !gracefulClosing.get()) { Log.e("ScrcpySession", "Session failed: " + serverLog, e); fail(e.getMessage() == null ? e.toString() : e.getMessage()); }
        } finally {
            if(!gracefulClosing.get())close();
            Decoder d = decoder;
            if (d != null) d.close();
        }
    }

    private void push(AdbConnection connection, String path, InputStream asset) throws Exception {
        try (AdbStream stream = connection.open("sync:")) {
            sync(stream, "SEND", (path + ",33216").getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[Math.min(65528, connection.getMaxData() - 8)]; int n;
            while ((n = asset.read(buffer)) != -1) {
                checkOpen();
                sync(stream, "DATA", java.util.Arrays.copyOf(buffer, n));
            }
            stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put("DONE".getBytes(StandardCharsets.US_ASCII)).putInt((int) (System.currentTimeMillis() / 1000)).array());
            DataInputStream in = input(stream);
            byte[] response = new byte[8]; in.readFully(response);
            if (!new String(response, 0, 4, StandardCharsets.US_ASCII).equals("OKAY")) throw new IOException(UiText.text(R.string.could_not_upload_remote_service));
        }
    }

    private static void sync(AdbStream stream, String type, byte[] bytes) throws Exception {
        stream.write(ByteBuffer.allocate(bytes.length + 8).order(ByteOrder.LITTLE_ENDIAN)
                .put(type.getBytes(StandardCharsets.US_ASCII)).putInt(bytes.length).put(bytes).array());
    }

    private static DataInputStream input(AdbStream stream) {
        return new DataInputStream(new InputStream() {
            byte[] packet = new byte[0]; int offset;
            @Override public int read() throws IOException {
                byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                while (offset == packet.length) {
                    try { packet = stream.read(); offset = 0; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(UiText.text(R.string.connection_cancelled), e); }
                }
                int n = Math.min(len, packet.length - offset);
                System.arraycopy(packet, offset, b, off, n); offset += n; return n;
            }
        });
    }

    void key(int keycode) { send(Protocol.chord(keycode, 0)); }
    void key(int keycode, int metaState) { send(Protocol.chord(keycode, metaState)); }
    void paste(String text) { send(Protocol.paste(text)); }
    void orientation(boolean landscape) {
        lastLandscape=landscape;
        RemoteDeviceSettings settings=deviceSettings;
        if(!closed.get() && !gracefulClosing.get() && settings!=null && profile.syncRotation && !profile.viewOnly)
            settings.rotation(landscape);
    }
    void syncRotation(boolean enabled) {
        profile.syncRotation=enabled;
        if(enabled)orientation(lastLandscape);
        else {RemoteDeviceSettings settings=deviceSettings;if(!closed.get() && !gracefulClosing.get() && settings!=null)settings.rotation(null);}
    }
    void rotate(boolean right){
        RemoteDeviceSettings settings=deviceSettings;
        if(!closed.get() && !gracefulClosing.get() && settings!=null && !profile.viewOnly)settings.rotate(right);
    }
    void stayAwake(boolean enabled) {
        profile.stayAwake=enabled;RemoteDeviceSettings settings=deviceSettings;
        if(!closed.get() && !gracefulClosing.get() && settings!=null)settings.awake(enabled);
    }
    void playback(boolean enabled) {
        profile.forwardAudio=enabled;audioEpoch.incrementAndGet();
        if(!enabled){AudioForwarder audio=audioForwarder;if(audio!=null)audio.close();}
        queueAudio();
    }
    void microphone(boolean enabled) {
        profile.forwardMic=enabled && !profile.viewOnly;micEpoch.incrementAndGet();
        if(!profile.forwardMic){MicForwarder mic=microphone;if(mic!=null)mic.close();}
        queueMicrophone();
    }
    private void queueAudio() {
        if(!connectionReady || closed.get() || gracefulClosing.get())return;
        try{runtime.execute(() -> {
            try {
                AudioForwarder old=audioForwarder;
                if(old!=null && (!profile.forwardAudio || old.isClosed())){old.close();old.awaitClosed();audioForwarder=null;}
                if(profile.forwardAudio && audioForwarder==null && !closed.get() && !gracefulClosing.get()) {
                    int epoch=audioEpoch.get();
                    AudioForwarder next=new AudioForwarder(context,adb,serverPath,message -> {
                        if(epoch==audioEpoch.get() && !closed.get() && !gracefulClosing.get()) {
                            profile.forwardAudio=false;listener.settingFailed("forwardAudio");listener.warning(message);
                        }
                    });
                    audioForwarder=next;if(closed.get() || gracefulClosing.get() || !profile.forwardAudio)next.close();
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    private void queueMicrophone() {
        if(!connectionReady || closed.get() || gracefulClosing.get())return;
        try{runtime.execute(() -> {
            try {
                MicForwarder old=microphone;
                if(old!=null && (!profile.forwardMic || old.isClosed())){old.close();old.awaitClosed();microphone=null;}
                if(profile.forwardMic && !profile.viewOnly && microphone==null && !closed.get() && !gracefulClosing.get()) {
                    int epoch=micEpoch.get();
                    if(!micUploaded){
                        try(InputStream apk=new java.io.FileInputStream(context.getApplicationInfo().sourceDir)){push(adb,micPath,apk);}
                        micUploaded=true;
                    }
                    if(!profile.forwardMic || closed.get() || gracefulClosing.get())return;
                    MicForwarder next=new MicForwarder(context,adb,micPath,micName,message -> {
                        if(epoch==micEpoch.get() && !closed.get() && !gracefulClosing.get()) {
                            profile.forwardMic=false;listener.settingFailed("forwardMic");listener.warning(message);
                        }
                    });
                    microphone=next;if(closed.get() || gracefulClosing.get() || !profile.forwardMic)next.close();
                }
            }catch(Exception e){
                if(!closed.get() && !gracefulClosing.get()){profile.forwardMic=false;listener.settingFailed("forwardMic");listener.warning(UiText.text(R.string.could_not_start_microphone_forwarding));}
            }
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    void expandSettings() { send(new byte[]{6}); }
    void touch(int action, long pointer, int x, int y, int w, int h, float pressure) {
        send(Protocol.touch(action, pointer, x, y, w, h, pressure));
    }
    private void send(byte[] bytes) {
        if (profile.viewOnly || closed.get() || gracefulClosing.get() || control == null) return;
        if (!controls.offer(bytes) && !closed.get()) fail(UiText.text(R.string.control_connection_congested_please_reconnect));
    }

    private void checkOpen() throws IOException { if (closed.get() || gracefulClosing.get()) throw new IOException(UiText.text(R.string.connection_cancelled)); }
    private void installTransport(java.io.Closeable value)throws IOException{
        transport=value;
        if(closed.get() || gracefulClosing.get()){value.close();checkOpen();}
    }
    private void fail(String message) { if (close()) listener.error(message); }

    void closeGracefully() {
        if(closed.get() || !gracefulClosing.compareAndSet(false,true))return;
        closeAudio();
        runtime.shutdownNow();
        RemoteDeviceSettings settings=deviceSettings;
        if(settings==null)finishControls();else settings.finish(this::finishControls);
        try {timer.schedule(this::close,4000,TimeUnit.MILLISECONDS);}
        catch(java.util.concurrent.RejectedExecutionException e){close();}
    }
    private void finishControls() {
        if(control==null || !controls.offer(END_CONTROLS)){close();return;}
    }

    private void closeAudio() {
        MicForwarder mic=microphone;if(mic!=null)mic.close();
        AudioForwarder audio=audioForwarder;if(audio!=null)audio.close();
    }

    boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        closeAudio();
        runtime.shutdownNow();
        RemoteDeviceSettings settings=deviceSettings;if(settings!=null)settings.close();
        timer.shutdownNow(); controls.clear();
        Thread writer = controlWriter; if (writer != null) writer.interrupt();
        // Closing the transport unblocks all ADB readers/writers; codec teardown
        // belongs to the video worker, after its output thread has stopped.
        java.io.Closeable s = transport;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        Thread t = worker; if (t != null && t != Thread.currentThread()) t.interrupt();
        AdbConnection a = adb;
        if (a != null) new Thread(() -> { try { a.close(); } catch (IOException ignored) { } }, "scrcpy-close").start();
        return true;
    }

    private final class Decoder {
        final MediaCodec codec;
        final Thread output;
        volatile boolean stopped;
        Decoder(String mime, int w, int h, String device) throws IOException {
            codec = MediaCodec.createDecoderByType(mime);
            try {
                MediaFormat format = MediaFormat.createVideoFormat(mime, w, h);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(1024 * 1024, w * h));
                if (android.os.Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                codec.configure(format, surface, null, 0); codec.start();
            } catch (RuntimeException e) { codec.release(); throw e; }
            output = new Thread(() -> {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                try {
                    while (!stopped && !closed.get() && !gracefulClosing.get()) {
                        int index = codec.dequeueOutputBuffer(info, 10000);
                        if (index >= 0) {
                            codec.releaseOutputBuffer(index, true);
                            if (rendered.compareAndSet(false, true)) {
                                startupTimeout.cancel(false); listener.ready(device);
                            }
                        }
                    }
                } catch (RuntimeException e) { if (!stopped && !closed.get() && !gracefulClosing.get()) fail(UiText.text(R.string.video_decoding_failed_lower_the_settings_or_change_codec)); }
            }, "scrcpy-render"); output.start();
        }
        void feed(byte[] bytes, long pts, boolean config) throws IOException {
            int index;
            do { checkOpen(); index = codec.dequeueInputBuffer(10000); } while (index < 0);
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < bytes.length) throw new IOException(UiText.text(R.string.video_frame_too_large_lower_the_resolution));
            buffer.clear(); buffer.put(bytes);
            codec.queueInputBuffer(index, 0, bytes.length, pts, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
        }
        void close() {
            // A failed replacement decoder leaves the previous instance in the
            // session field; final cleanup must not release it a second time.
            if (stopped) return;
            stopped = true;
            boolean interrupted = Thread.interrupted();
            while (output.isAlive()) {
                try { output.join(); } catch (InterruptedException e) { interrupted = true; }
            }
            try { codec.stop(); } catch (RuntimeException ignored) { }
            codec.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
