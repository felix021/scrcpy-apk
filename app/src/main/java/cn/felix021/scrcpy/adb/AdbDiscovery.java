package cn.felix021.scrcpy.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovery is optional for codes, required for Android Studio QR pairing. */
public final class AdbDiscovery implements Closeable {
    public static final String PAIR="_adb-tls-pairing._tcp.",CONNECT="_adb-tls-connect._tcp.";
    public interface Listener { void changed(List<Service> services); void unavailable(); }
    public static final class Service {
        public final String name,host,type;public final int port;
        Service(NsdServiceInfo info){name=info.getServiceName();host=info.getHost().getHostAddress();port=info.getPort();type=info.getServiceType();}
        public boolean pairing(){return type.contains("_adb-tls-pairing.");}
    }
    private final NsdManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final List<NsdManager.DiscoveryListener> browsers=new ArrayList<>();
    private final Map<String,Service> resolved=new LinkedHashMap<>();
    private final Map<String,NsdServiceInfo> present=new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private boolean closed,resolving;
    public AdbDiscovery(Context context,Listener listener){manager=(NsdManager)context.getSystemService(Context.NSD_SERVICE);this.listener=listener;}
    public void start(){for(String type:new String[]{PAIR,CONNECT}){
        NsdManager.DiscoveryListener browser=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){}
            public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int error){main.post(() -> {if(!closed)listener.unavailable();});}
            public void onStopDiscoveryFailed(String t,int error){}
            public void onServiceFound(NsdServiceInfo service){main.post(() -> {if(!closed){present.put(key(service),service);queue.add(service);resolveNext();}});}
            public void onServiceLost(NsdServiceInfo service){main.post(() -> {if(!closed){present.remove(key(service));resolved.remove(key(service));emit();}});}
        };
        browsers.add(browser);
        try{manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,browser);}catch(RuntimeException e){listener.unavailable();}
    }}
    private static String key(NsdServiceInfo info){return info.getServiceType().replaceAll("\\.$","")+"/"+info.getServiceName();}
    @SuppressWarnings("deprecation")
    private void resolveNext(){
        if(closed || resolving || queue.isEmpty())return;
        NsdServiceInfo next=queue.remove();if(!present.containsKey(key(next))){resolveNext();return;}
        resolving=true;
        try{manager.resolveService(next,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo service,int code){main.post(() -> {resolving=false;resolveNext();});}
            public void onServiceResolved(NsdServiceInfo service){main.post(() -> {
                resolving=false;
                if(!closed && present.containsKey(key(next)) && service.getHost()!=null && service.getPort()>0){resolved.put(key(next),new Service(service));emit();}
                resolveNext();
            });}
        });}catch(RuntimeException e){resolving=false;listener.unavailable();resolveNext();}
    }
    private void emit(){listener.changed(new ArrayList<>(resolved.values()));}
    @Override public void close(){closed=true;queue.clear();present.clear();resolved.clear();for(NsdManager.DiscoveryListener b:browsers){try{manager.stopServiceDiscovery(b);}catch(RuntimeException ignored){}}browsers.clear();}
}
