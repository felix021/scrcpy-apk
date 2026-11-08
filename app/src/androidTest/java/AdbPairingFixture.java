// Android system-service fixture for owned AVDs only. Never bundled into the app.
// Starts the same pairing service used by Settings after scanning an ADB QR.
import java.lang.reflect.Method;
import java.util.Map;


public final class AdbPairingFixture {
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { error.printStackTrace(System.err); System.exit(1); });
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"adb");
        Class<?> manager=Class.forName("android.debug.IAdbManager");
        Object service=Class.forName("android.debug.IAdbManager$Stub").getMethod("asInterface",Class.forName("android.os.IBinder")).invoke(null,binder);
        if(args[0].equals("qr") || args[0].equals("code")) {
            // The native Android pairing server is a singleton. Cancel and join
            // its old session through the handler before starting another.
            manager.getMethod("disablePairing").invoke(service);
            Thread.sleep(300);
        }
        switch(args[0]) {
            case "qr": manager.getMethod("enablePairingByQrCode",String.class,String.class).invoke(service,args[1],args[2]); System.out.println("QR_SERVER_STARTED"); break;
            case "code": manager.getMethod("enablePairingByPairingCode").invoke(service); System.out.println("CODE_SERVER_STARTED"); break;
            case "allow": manager.getMethod("allowWirelessDebugging",boolean.class,String.class).invoke(service,true,args[1]); break;
            case "stop": manager.getMethod("disablePairing").invoke(service); break;
            case "port": System.out.println("CONNECT_PORT="+manager.getMethod("getAdbWirelessPort").invoke(service)); break;
            case "devices":
                Object records=manager.getMethod("getPairedDevices").invoke(service);
                Iterable<?> devices=records instanceof Map ? ((Map<?,?>)records).values() : java.util.Arrays.asList((Object[])records);
                for(Object record:devices) {
                    Object device=record;
                    try { device=record.getClass().getField("device").get(record); } catch(NoSuchFieldException ignored) {}
                    System.out.println("PAIRED_NAME="+device.getClass().getField("name").get(device));
                }
                break;
            default: throw new IllegalArgumentException("qr|code|stop|port|devices");
        }
    }
}
