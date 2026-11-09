package cn.ohmyterm.scrcpy;

import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns temporary device settings, including restoring them on transport loss. */
final class RemoteDeviceSettings {
    private final AdbStream stream;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final CountDownLatch ready=new CountDownLatch(1),exited=new CountDownLatch(1);
    private volatile boolean stopping;
    private Boolean rotationRequested,awakeRequested;
    private boolean manuallyRotated;
    RemoteDeviceSettings(AdbConnection connection,String serverPath,String micPath,Consumer<String> error,Consumer<String> warning)throws Exception {
        // Complete all command substitutions before installing EXIT: mksh
        // inherits EXIT in subshells and must not restore settings during init.
        String command="a=$(settings get system accelerometer_rotation </dev/null); r=$(settings get system user_rotation </dev/null); "
                +"v=$(settings get global stay_on_while_plugged_in </dev/null); "
                +"s=$(wm size </dev/null | tail -n 1); s=${s##*: }; w=${s%x*}; h=${s#*x}; sdk=$(getprop ro.build.version.sdk </dev/null); rd=0; wd=0; manual=; "
                +"restore_rotation(){ manual=; if [ \"$rd\" = 1 ]; then "
                +"if [ \"$r\" = null ]; then settings delete system user_rotation </dev/null; else settings put system user_rotation \"$r\" </dev/null; fi; "
                +"if [ \"$a\" = null ]; then settings delete system accelerometer_rotation </dev/null; else settings put system accelerometer_rotation \"$a\" </dev/null; fi; rd=0; fi; }; "
                +"restore_awake(){ if [ \"$wd\" = 1 ]; then if [ \"$v\" = null ]; then settings delete global stay_on_while_plugged_in </dev/null; else settings put global stay_on_while_plugged_in \"$v\" </dev/null; fi; wd=0; fi; }; "
                +"apply_rotation(){ rd=1; if [ \"$sdk\" -ge 29 ]; then wm user-rotation lock \"$1\" </dev/null; else settings put system accelerometer_rotation 0 </dev/null; settings put system user_rotation \"$1\" </dev/null; fi; }; "
                +"trap 'restore_rotation; restore_awake; rm -f "+serverPath+" "+micPath+"' EXIT; trap 'exit' HUP INT TERM; echo READY; "
                +"while IFS= read -r d; do case \"$d\" in q) break;; r) restore_rotation;; "
                +"w0) restore_awake;; w1) wd=1; settings put global stay_on_while_plugged_in 7 </dev/null;; "
                +"0|1) manual=; if [ \"$w\" -gt \"$h\" ]; then d=$((1-d)); fi; apply_rotation \"$d\";; "
                // Clear inherited traps in these substitutions: reading the current
                // rotation must not restore settings or delete the session files.
                +"L|R) rd=1; if [ -n \"$manual\" ]; then c=$manual; elif [ \"$sdk\" -ge 29 ]; then wm user-rotation lock </dev/null; "
                +"c=$(trap - EXIT HUP INT TERM; settings get system user_rotation </dev/null); "
                +"else c=$(trap - EXIT HUP INT TERM; dumpsys input </dev/null | sed -n 's/.*SurfaceOrientation: *\\([0-3]\\).*/\\1/p; s/.*Viewport.*displayId=0,.*orientation=\\([0-3]\\),.*/\\1/p' | head -n 1); fi; "
                +"case \"$c\" in 0|1|2|3) if [ \"$d\" = L ]; then c=$(((c+3)%4)); else c=$(((c+1)%4)); fi; apply_rotation \"$c\"; manual=$c;; "
                +"*) restore_rotation; echo ROTATION_FAILED;; esac;; esac; echo APPLIED:$d; done";
        stream=connection.open("shell:"+command);
        new Thread(() -> {
            StringBuilder pending=new StringBuilder();
            try {while(true){
                pending.append(new String(stream.read(),StandardCharsets.UTF_8));int end;
                while((end=pending.indexOf("\n"))>=0){
                    String line=pending.substring(0,end).trim();pending.delete(0,end+1);
                    if(line.equals("READY"))ready.countDown();
                    else if(line.equals("ROTATION_FAILED"))warning.accept(UiText.text(R.string.could_not_sync_remote_orientation));
                    else android.util.Log.d("RemoteSettings",line);
                }
            }}catch(Exception ignored){}
            finally{exited.countDown();if(!stopping)error.accept(UiText.text(R.string.remote_settings_service_stopped));}
        },"scrcpy-device-settings-reader").start();
    }
    synchronized void rotation(Boolean landscape) {
        if(stopping || !manuallyRotated && Objects.equals(rotationRequested,landscape))return;
        manuallyRotated=false;rotationRequested=landscape;send(landscape==null?"r":landscape?"1":"0");
    }
    synchronized void rotate(boolean right){if(!stopping){manuallyRotated=true;send(right?"R":"L");}}
    synchronized void awake(boolean enabled) {
        if(stopping || Objects.equals(awakeRequested,enabled))return;
        awakeRequested=enabled;send(enabled?"w1":"w0");
    }
    private void send(String command) {
        try{writer.execute(() -> {
            try{if(ready.await(10,TimeUnit.SECONDS) && !stopping)stream.write((command+"\n").getBytes(StandardCharsets.US_ASCII));}
            catch(Exception ignored){}
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    void finish(Runnable done) {
        stopping=true;
        try{writer.execute(() -> {
            try{stream.write("q\n".getBytes(StandardCharsets.US_ASCII));exited.await(1500,TimeUnit.MILLISECONDS);}
            catch(Exception ignored){}finally{done.run();}
        });}catch(java.util.concurrent.RejectedExecutionException e){done.run();}
    }
    void close(){stopping=true;writer.shutdownNow();}
}
