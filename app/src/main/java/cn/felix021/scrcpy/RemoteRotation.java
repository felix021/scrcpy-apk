package cn.felix021.scrcpy;

import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** An ADB shell owns the temporary rotation lock. Its EXIT trap also restores
 * settings when the transport disappears, rather than depending on client cleanup. */
final class RemoteRotation {
    private static final String COMMAND =
            "a=$(settings get system accelerometer_rotation </dev/null); r=$(settings get system user_rotation </dev/null); "
            + "restore() { "
            + "if [ \"$r\" = null ]; then settings delete system user_rotation </dev/null; else settings put system user_rotation \"$r\" </dev/null; fi; "
            + "if [ \"$a\" = null ]; then settings delete system accelerometer_rotation </dev/null; else settings put system accelerometer_rotation \"$a\" </dev/null; fi; }; "
            + "s=$(wm size </dev/null | tail -n 1); s=${s##*: }; w=${s%x*}; h=${s#*x}; sdk=$(getprop ro.build.version.sdk </dev/null); "
            // Install EXIT only after command substitutions: mksh inherits EXIT
            // in those subshells, which could otherwise restore settings mid-session.
            + "trap 'echo RESTORE; restore >/dev/null 2>&1' EXIT; trap 'echo GOT_HUP; exit' HUP INT TERM; echo READY; "
            + "while IFS= read -r d; do "
            + "case \"$d\" in q) break;; 0|1) "
            + "if [ \"$w\" -gt \"$h\" ]; then d=$((1-d)); fi; "
            + "if [ \"$sdk\" -ge 29 ]; then wm user-rotation lock \"$d\" </dev/null; else settings put system accelerometer_rotation 0 </dev/null; settings put system user_rotation \"$d\" </dev/null; fi; echo APPLIED:$d;; esac; done";
    private final AdbStream stream;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final CountDownLatch ready=new CountDownLatch(1);
    private final CountDownLatch exited=new CountDownLatch(1);
    private final Consumer<String> error;
    private volatile boolean stopping;
    private Boolean requested;

    RemoteRotation(AdbConnection connection, Consumer<String> error) throws Exception {
        this.error=error;stream=connection.open("shell:"+COMMAND);
        new Thread(() -> {
            StringBuilder pending=new StringBuilder();
            try {while(true) {
                pending.append(new String(stream.read(),StandardCharsets.UTF_8));
                int end;
                while((end=pending.indexOf("\n"))>=0) {
                    String line=pending.substring(0,end).trim();pending.delete(0,end+1);
                    if(line.equals("READY"))ready.countDown();
                    else android.util.Log.d("RemoteRotation",line);
                }
            }}
            catch(Exception ignored) { }
            finally {exited.countDown();if(!stopping)error.accept("远端旋转服务已结束");}
        },"scrcpy-rotation-reader").start();
    }
    synchronized void set(boolean landscape) {
        if(stopping || (requested!=null && requested==landscape))return;
        requested=landscape;
        try {writer.execute(() -> {
            android.util.Log.d("RemoteRotation","SET_ORIENTATION "+(landscape?"landscape":"portrait"));
            try {
                if(!ready.await(10,TimeUnit.SECONDS))throw new java.io.IOException("Rotation shell startup timed out");
                if(stopping)return;
                stream.write((landscape?"1\n":"0\n").getBytes(StandardCharsets.US_ASCII));}
            catch(Exception e){if(!stopping)error.accept("同步远端方向失败");}
        });}catch(java.util.concurrent.RejectedExecutionException ignored) { }
    }
    void finish(Runnable done) {
        stopping=true;
        try {writer.execute(() -> {
            try {stream.write("q\n".getBytes(StandardCharsets.US_ASCII));exited.await(1500,TimeUnit.MILLISECONDS);}
            catch(Exception ignored) { }
            finally {done.run();}
        });}catch(java.util.concurrent.RejectedExecutionException e){done.run();}
    }
    void close() {stopping=true;writer.shutdownNow();}
}
