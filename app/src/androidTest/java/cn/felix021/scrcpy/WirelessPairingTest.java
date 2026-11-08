package cn.felix021.scrcpy;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Explicit opt-in against an owned AVD; no test configuration ships in the app. */
public final class WirelessPairingTest extends Instrumentation {
    private Bundle args;
    private final List<Relay> relays=new ArrayList<>();
    private final List<NsdManager.RegistrationListener> registrations=new ArrayList<>();
    private Activity activity;
    @Override public void onCreate(Bundle arguments){args=arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int exit=Activity.RESULT_OK;
        try{runChecks(result);}catch(Throwable error){exit=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+error+"\n");}
        finish(exit,result);
    }
    private void runChecks(Bundle result) throws Exception {
        try{
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
            getTargetContext().getSharedPreferences("connections",0).edit().putString("language",args.getString("language","en")).putString("localName","Before pairing").commit();
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click("app_settings");set("local_device_name","My Android Client");waitText(UiText.format(R.string.pair_name_preview,"My_Android_Client"),5000);click("app_settings_done");
            String mode=args.getString("mode","code");
            if(mode.equals("classic")){
                setDescription(UiText.text(R.string.device_ip_hostname),args.getString("host"));setDescription(UiText.text(R.string.adb_port),args.getString("connectPort"));
                List<AccessibilityNodeInfo> buttons=getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(UiText.text(R.string.connect));
                require(!buttons.isEmpty() && buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Connect classic ADB");
                waitText("● Connected",60000);click("runtime_settings");click("runtime_disconnect");click("disconnect_confirm");
                result.putString("stream","PASS: classic TCP video first frame and disconnect\n");return;
            }
            click("pair_open");
            if(mode.equals("layout")){
                for(int orientation:new int[]{android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE}){
                    runOnMainSync(()->activity.setRequestedOrientation(orientation));waitForIdleSync();Thread.sleep(700);
                    AccessibilityNodeInfo image=waitID("pair_qr_image",10000);image.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());Thread.sleep(400);
                    image=waitID("pair_qr_image",5000);Rect rect=new Rect();image.getBoundsInScreen(rect);
                    Bitmap screenshot=getUiAutomation().takeScreenshot();
                    try(java.io.FileOutputStream out=getTargetContext().openFileOutput("wireless-layout-"+args.getString("language","en")+"-"+orientation+".png",0)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
                    Bitmap crop=Bitmap.createBitmap(screenshot,rect.left,rect.top,rect.width(),rect.height());require(crop.getPixel(crop.getWidth()/2,crop.getHeight()-1)==android.graphics.Color.WHITE,"QR quiet zone must not be covered by a gradient");int[] pixels=new int[crop.getWidth()*crop.getHeight()];crop.getPixels(pixels,0,crop.getWidth(),0,0,crop.getWidth(),crop.getHeight());
                    require(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(crop.getWidth(),crop.getHeight(),pixels)))).getText().startsWith("WIFI:T:ADB;"),"Readable QR in each orientation");
                    Rect footer=new Rect();waitID("pair_action",5000).getBoundsInScreen(footer);require(footer.height()>=40*getTargetContext().getResources().getDisplayMetrics().density && footer.bottom<=screenshot.getHeight(),"Footer remains visible");
                    click("pair_code_tab");require(waitID("pair_action",5000)!=null,"Code action remains available");click("pair_qr_tab");
                }
                runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
                result.putString("stream","PASS: editable device name; portrait and both landscape QR/footer layouts\n");return;
            }
            if(mode.equals("qr")){
                AccessibilityNodeInfo image=waitID("pair_qr_image",10000);image.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());waitForIdleSync();Thread.sleep(600);image=waitID("pair_qr_image",5000);Rect bounds=new Rect();image.getBoundsInScreen(bounds);
                Bitmap screenshot=getUiAutomation().takeScreenshot();try(java.io.FileOutputStream debug=getTargetContext().openFileOutput("wireless-qr-test.png",0)){screenshot.compress(Bitmap.CompressFormat.PNG,100,debug);}
                Bitmap qr=Bitmap.createBitmap(screenshot,bounds.left,bounds.top,bounds.width(),bounds.height());
                int[] pixels=new int[qr.getWidth()*qr.getHeight()];qr.getPixels(pixels,0,qr.getWidth(),0,0,qr.getWidth(),qr.getHeight());
                String payload=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(qr.getWidth(),qr.getHeight(),pixels)))).getText();
                require(payload.matches("WIFI:T:ADB;S:studio-[0-9a-f]{16};P:[0-9a-f]{32};;"),"Displayed QR format");
                String service=payload.split(";S:")[1].split(";")[0];
                Relay pairing=new Relay(args.getString("host"),Integer.parseInt(args.getString("pairPort")));relays.add(pairing);
                Relay connection=new Relay(args.getString("host"),Integer.parseInt(args.getString("connectPort")));relays.add(connection);
                connection.ready.countDown();
                register(service,"_adb-tls-pairing._tcp.",pairing.server.getLocalPort());
                register("adb-test-connect","_adb-tls-connect._tcp.",connection.server.getLocalPort());
                waitStatus("Pairing…",10000);
                JSONObject challenge=new JSONObject();challenge.put("payload",payload);
                java.net.URI bridge=java.net.URI.create(args.getString("bridge"));
                try(Socket http=new Socket()){
                    http.connect(new InetSocketAddress(bridge.getHost(),bridge.getPort()),10000);http.setSoTimeout(20000);
                    byte[] request=challenge.toString().getBytes(StandardCharsets.UTF_8);
                    String header="POST /qr HTTP/1.0\r\nHost: test-relay\r\nContent-Length: "+request.length+"\r\n\r\n";
                    http.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));http.getOutputStream().write(request);http.getOutputStream().flush();
                    String status=new java.io.BufferedReader(new java.io.InputStreamReader(http.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                    require(status!=null && status.contains(" 200 "),"Android QR server setup");
                }
                pairing.ready.countDown();
                waitStatus("Paired successfully",45000);
                waitValue("pair_connect_port",String.valueOf(connection.server.getLocalPort()),10000);
            }else{
                click("pair_code_tab");set("pair_host",args.getString("host"));set("pair_port",args.getString("pairPort"));set("pair_code_input",args.getString("code"));click("pair_action");
                if(mode.equals("wrong")){
                    waitStatus("Pairing failed.",45000);require(!hasText("Paired successfully"),"Wrong code must not pair");
                    click("pair_qr_tab");require(waitID("pair_qr_image",5000)!=null,"Can change mode after rejection");
                    result.putString("stream","PASS: wrong code rejected; mode reset works\n");return;
                }
                waitStatus("Paired successfully",45000);set("pair_connect_port",args.getString("connectPort"));
            }
            click("pair_action");waitText("● Connected",60000);click("runtime_settings");click("runtime_disconnect");click("disconnect_confirm");waitID("pair_open",15000);
            result.putString("stream","PASS: "+mode+" pairing, TLS video first frame, runtime controls and disconnect\n");
        }finally{
            NsdManager manager=(NsdManager)getTargetContext().getSystemService(android.content.Context.NSD_SERVICE);
            for(NsdManager.RegistrationListener listener:registrations)try{manager.unregisterService(listener);}catch(RuntimeException ignored){}
            for(Relay relay:relays)relay.close();
        }
    }
    private AccessibilityNodeInfo id(String id){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId("cn.felix021.scrcpy:id/"+id);return nodes.isEmpty()?null:nodes.get(0);}
    private AccessibilityNodeInfo waitID(String id,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;do{AccessibilityNodeInfo n=id(id);if(n!=null)return n;Thread.sleep(150);}while(System.currentTimeMillis()<end);throw new AssertionError("Missing UI: "+id);}
    private void click(String id) throws Exception {require(waitID(id,10000).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Click "+id);Thread.sleep(300);}
    private void set(String id,String text) throws Exception {Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);require(waitID(id,5000).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b),"Set "+id);}
    private void setDescription(String description,String text) throws Exception {
        AccessibilityNodeInfo node=description(getUiAutomation().getRootInActiveWindow(),description);require(node!=null,"Input "+description);
        Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b),"Edit "+description);
    }
    private AccessibilityNodeInfo description(AccessibilityNodeInfo node,String description){if(node==null)return null;if(node.getContentDescription()!=null && description.contentEquals(node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=description(node.getChild(i),description);if(child!=null)return child;}return null;}
    private void waitValue(String id,String value,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;do{AccessibilityNodeInfo n=id(id);if(n!=null && value.contentEquals(n.getText()))return;Thread.sleep(150);}while(System.currentTimeMillis()<end);throw new AssertionError("Discovered connection port missing; expected "+value+", actual "+(id(id)==null?"no field":id(id).getText()));}
    private boolean hasText(String text){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null && !root.findAccessibilityNodeInfosByText(text).isEmpty();}
    private void waitText(String text,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;do{if(hasText(text))return;Thread.sleep(200);}while(System.currentTimeMillis()<end);throw new AssertionError("UI text missing: "+text);}
    private void waitStatus(String text,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;do{AccessibilityNodeInfo n=id("pair_status");if(n!=null && n.getText()!=null && n.getText().toString().startsWith(text))return;Thread.sleep(200);}while(System.currentTimeMillis()<end);throw new AssertionError("Pairing status missing: "+text);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void register(String name,String type,int port) throws Exception {
        CountDownLatch latch=new CountDownLatch(1);boolean[] ok={false};NsdServiceInfo service=new NsdServiceInfo();service.setServiceName(name);service.setServiceType(type);service.setPort(port);
        NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo s){ok[0]=s.getServiceName().equals(name);latch.countDown();}public void onRegistrationFailed(NsdServiceInfo s,int code){latch.countDown();}public void onServiceUnregistered(NsdServiceInfo s){}public void onUnregistrationFailed(NsdServiceInfo s,int code){}};
        registrations.add(listener);((NsdManager)getTargetContext().getSystemService(android.content.Context.NSD_SERVICE)).registerService(service,NsdManager.PROTOCOL_DNS_SD,listener);
        require(latch.await(10,TimeUnit.SECONDS) && ok[0],"Register test Bonjour relay");
    }
    private static final class Relay implements AutoCloseable {
        final ServerSocket server;final CountDownLatch ready=new CountDownLatch(1);final List<Socket> sockets=java.util.Collections.synchronizedList(new ArrayList<>());
        Relay(String host,int port) throws Exception {server=new ServerSocket(0);Thread accept=new Thread(()->{while(!server.isClosed())try{Socket a=server.accept(),b=new Socket();sockets.add(a);sockets.add(b);if(!ready.await(20,TimeUnit.SECONDS))throw new java.io.IOException("Relay activation timed out");b.connect(new InetSocketAddress(host,port),10000);pipe(a,b);pipe(b,a);}catch(Exception ignored){}});accept.setDaemon(true);accept.start();}
        private void pipe(Socket from,Socket to){Thread thread=new Thread(()->{try{byte[] bytes=new byte[32768];int n;while((n=from.getInputStream().read(bytes))>=0)to.getOutputStream().write(bytes,0,n);}catch(Exception ignored){}finally{try{from.close();to.close();}catch(Exception ignored){}}});thread.setDaemon(true);thread.start();}
        @Override public void close(){try{server.close();}catch(Exception ignored){}synchronized(sockets){for(Socket s:sockets)try{s.close();}catch(Exception ignored){}}}
    }
}
