package cn.felix021.scrcpy.probe;

import android.app.UiAutomation;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

/** Shell-only test helper; sets a local dialog field without restarting the app. */
public final class SetText {
    public static void main(String[] args) throws Exception {
        HandlerThread thread=new HandlerThread("validation-accessibility");thread.start();
        Class<?> connectionType=Class.forName("android.app.IUiAutomationConnection");
        Object connection=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,connectionType).newInstance(thread.getLooper(),connection);
        UiAutomation.class.getMethod("connect").invoke(automation);
        try {
            AccessibilityNodeInfo target=null;
            long deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(target==null && android.os.SystemClock.uptimeMillis()<deadline) {
                target=find(automation.getRootInActiveWindow(),args[0]);
                if(target==null)Thread.sleep(100);
            }
            if(target==null)throw new IllegalStateException("Text field missing");
            String value=args.length>2 && "--file".equals(args[1])
                    ? new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[2])),java.nio.charset.StandardCharsets.UTF_8) : args[1];
            Bundle bundle=new Bundle();bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
            if(!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,bundle))throw new IllegalStateException("Text action rejected");
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(automation);thread.quitSafely();thread.join(2000);
        }
        // app_process must exit before RuntimeInit tears down Binder under queued accessibility callbacks.
        System.exit(0);
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String description) {
        if(node==null)return null;
        if(description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=find(node.getChild(i),description);if(child!=null)return child;}
        return null;
    }
}
