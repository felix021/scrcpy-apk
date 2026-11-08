package cn.felix021.scrcpy;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbCrypto;

final class AdbIdentity {
    static String localName(Context context){
        String name=null;
        try{name=Settings.Global.getString(context.getContentResolver(),"device_name");}catch(SecurityException ignored){}
        return context.getSharedPreferences("connections",Context.MODE_PRIVATE).getString("localName",name==null || name.trim().isEmpty()?Build.MODEL:name);
    }
    static synchronized AdbCrypto load(Context context) throws Exception {
        AdbBase64 b64=b -> Base64.encodeToString(b,Base64.NO_WRAP);
        AdbCrypto crypto;
        if(context.getFileStreamPath("adb-private").exists() || context.getFileStreamPath("adb-public").exists()){
            // A corrupt existing identity must not silently unpair every saved device.
            crypto=AdbCrypto.loadAdbKeyPair(b64,context.getFileStreamPath("adb-private"),context.getFileStreamPath("adb-public"));
        }else{
            crypto=AdbCrypto.generateAdbKeyPair(b64);crypto.saveAdbKeyPair(context.getFileStreamPath("adb-private"),context.getFileStreamPath("adb-public"));
        }
        crypto.setDeviceName(localName(context));return crypto;
    }
}
