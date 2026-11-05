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
    }
    private final Context context;
    private final Profile profile;
    private final Surface surface;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean rendered = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> controls = new ArrayBlockingQueue<>(256);
    private volatile Thread controlWriter;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile Socket socket;
    private volatile AdbConnection adb;
    private volatile AdbStream control;
    private volatile Thread worker;
    private volatile Decoder decoder;
    private volatile String serverLog = "";
    private ScheduledFuture<?> startupTimeout;

    ScrcpySession(Context context, Profile profile, Surface surface, Listener listener) {
        this.context = context.getApplicationContext(); this.profile = profile;
        this.surface = surface; this.listener = listener;
    }

    void start() {
        startupTimeout = timer.schedule(() -> fail("连接超时。请检查网络，并在远端设备上允许 USB 调试授权。"), 60, TimeUnit.SECONDS);
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
            listener.status("正在连接 ADB · 首次连接请在远端允许调试");
            AdbCrypto crypto = identity(context);
            checkOpen();
            Socket s = new Socket(); socket = s;
            checkOpen();
            s.connect(new InetSocketAddress(profile.host, profile.port), 10000);
            AdbConnection connection = AdbConnection.create(s, crypto); adb = connection;
            checkOpen();
            if (!connection.connect(45, TimeUnit.SECONDS, false)) throw new IOException("ADB 授权超时，请在远端允许调试");
            checkOpen();
            listener.status("正在准备远端服务");
            String id = String.format(Locale.ROOT, "%08x", new java.security.SecureRandom().nextInt(0x7fffffff));
            String path = "/data/local/tmp/scrcpy-apk-" + id + ".jar";
            push(connection, path);
            checkOpen();
            String command = "CLASSPATH=" + path + " app_process / com.genymobile.scrcpy.Server 5.0.1"
                    + " scid=" + id + " tunnel_forward=true audio=false video=true"
                    + " cleanup=true clipboard_autosync=false power_on=true" + profile.serverOptions();
            AdbStream shell = connection.open("shell:" + command);
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
            if (video == null) throw new IOException("远端投屏服务未启动。" + serverLog);
            DataInputStream input = input(video);
            if (input.readUnsignedByte() != 0) throw new IOException("无效的服务握手");
            if (!profile.viewOnly) {
                control = connection.open(destination);
                controlWriter = new Thread(() -> {
                    try {
                        while (!closed.get()) {
                            java.io.ByteArrayOutputStream batch = new java.io.ByteArrayOutputStream();
                            batch.write(controls.take());
                            // ADB requires an ACK per WRTE. Batch all currently queued
                            // events so latency does not limit controls to 1 event / RTT.
                            byte[] next;
                            for (int i = 1; i < 128 && (next = controls.poll()) != null; i++) batch.write(next);
                            control.write(batch.toByteArray());
                        }
                    } catch (Exception e) { if (!closed.get()) fail("发送控制操作失败"); }
                }, "scrcpy-control-writer");
                controlWriter.start();
                new Thread(() -> {
                    try { while (!closed.get()) control.read(); }
                    catch (Exception e) { if (!closed.get()) fail("控制连接已断开"); }
                }, "scrcpy-control-reader").start();
            }
            byte[] name = new byte[64]; input.readFully(name);
            String device = new String(name, StandardCharsets.UTF_8).replace("\0", "").trim();
            int codecId = input.readInt();
            String mime;
            if (codecId == 0x68323634) mime = "video/avc";
            else if (codecId == 0x68323635) mime = "video/hevc";
            else throw new IOException("远端编码不可用，请更换编码或降低参数");
            listener.status("正在接收 " + device + " 的画面");
            while (!closed.get()) {
                int first = input.readInt(); int second = input.readInt(); int third = input.readInt();
                if ((first & 0x80000000) != 0) {
                    int width = second, height = third;
                    if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IOException("无效的视频尺寸");
                    if (decoder != null) decoder.close();
                    listener.size(width, height);
                    decoder = new Decoder(mime, width, height, device);
                    continue;
                }
                long ptsFlags = ((long) first << 32) | (second & 0xffffffffL);
                int length = third;
                if (length < 1 || length > 16 * 1024 * 1024) throw new IOException("无效的视频帧长度");
                byte[] packet = new byte[length]; input.readFully(packet);
                if (decoder == null) throw new IOException("缺少视频尺寸信息");
                boolean config = (ptsFlags & (1L << 62)) != 0;
                decoder.feed(packet, config ? 0 : ptsFlags & ((1L << 61) - 1), config);
            }
        } catch (Exception e) {
            if (!closed.get()) { Log.e("ScrcpySession", "Session failed: " + serverLog, e); fail(e.getMessage() == null ? e.toString() : e.getMessage()); }
        } finally {
            close();
            Decoder d = decoder;
            if (d != null) d.close();
        }
    }

    private void push(AdbConnection connection, String path) throws Exception {
        try (AdbStream stream = connection.open("sync:"); InputStream asset = context.getAssets().open("scrcpy-server.jar")) {
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
            if (!new String(response, 0, 4, StandardCharsets.US_ASCII).equals("OKAY")) throw new IOException("上传服务失败");
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
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("连接已取消", e); }
                }
                int n = Math.min(len, packet.length - offset);
                System.arraycopy(packet, offset, b, off, n); offset += n; return n;
            }
        });
    }

    void key(int keycode) { send(Protocol.key(0, keycode)); send(Protocol.key(1, keycode)); }
    void touch(int action, long pointer, int x, int y, int w, int h, float pressure) {
        send(Protocol.touch(action, pointer, x, y, w, h, pressure));
    }
    private void send(byte[] bytes) {
        if (profile.viewOnly || closed.get() || control == null) return;
        if (!controls.offer(bytes) && !closed.get()) fail("控制连接拥塞，请重新连接");
    }

    private void checkOpen() throws IOException { if (closed.get()) throw new IOException("连接已取消"); }
    private void fail(String message) { if (close()) listener.error(message); }

    boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        timer.shutdownNow(); controls.clear();
        Thread writer = controlWriter; if (writer != null) writer.interrupt();
        // Closing the transport unblocks all ADB readers/writers; codec teardown
        // belongs to the video worker, after its output thread has stopped.
        Socket s = socket;
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
                    while (!stopped && !closed.get()) {
                        int index = codec.dequeueOutputBuffer(info, 10000);
                        if (index >= 0) {
                            codec.releaseOutputBuffer(index, true);
                            if (rendered.compareAndSet(false, true)) {
                                startupTimeout.cancel(false); listener.ready(device);
                            }
                        }
                    }
                } catch (RuntimeException e) { if (!stopped && !closed.get()) fail("视频解码失败，请降低参数或更换编码"); }
            }, "scrcpy-render"); output.start();
        }
        void feed(byte[] bytes, long pts, boolean config) throws IOException {
            int index;
            do { checkOpen(); index = codec.dequeueInputBuffer(10000); } while (index < 0);
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < bytes.length) throw new IOException("视频帧过大，请降低分辨率");
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
