package cn.felix021.scrcpy;

import cn.felix021.scrcpy.adb.AdbPairing;
import com.tananaev.adblib.AdbCrypto;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AdbPairingTest {
    @Test public void freshQrChallengesAreSafeForWifiSyntax(){
        AdbPairing.Challenge first=new AdbPairing.Challenge(),second=new AdbPairing.Challenge();
        assertTrue(first.payload().matches("WIFI:T:ADB;S:studio-[0-9a-f]{16};P:[0-9a-f]{32};;"));
        assertNotEquals(first.service,second.service);assertNotEquals(first.password,second.password);
    }
    @Test public void namePreservesUnicodeWithoutAndroidWhitespaceSeparators() throws Exception {
        assertEquals("My_Phone_中文",AdbCrypto.deviceLabel("  My Phone\u00a0中文\0  "));
        assertEquals("A_B",AdbCrypto.deviceLabel("A\nB"));
    }
    @Test public void nameLimitCountsUtf8Bytes() throws Exception {
        assertEquals(120,AdbCrypto.deviceLabel(new String(new char[40]).replace("\0","中")).getBytes(StandardCharsets.UTF_8).length);
        try{AdbCrypto.deviceLabel(new String(new char[41]).replace("\0","中"));fail();}catch(IOException expected){}
        try{AdbCrypto.deviceLabel(" \0 ");fail();}catch(IOException expected){}
    }
    @Test public void namingKeepsTheSameRsaIdentity() throws Exception {
        AdbCrypto identity=AdbCrypto.generateAdbKeyPair(b -> java.util.Base64.getEncoder().encodeToString(b));
        String original=new String(identity.getAdbPublicKeyPayload(),StandardCharsets.UTF_8).split(" ")[0];
        identity.setDeviceName("My Android");
        assertEquals(original+" My_Android\0",new String(identity.getAdbPublicKeyPayload(),StandardCharsets.UTF_8));
        assertNotNull(identity.getPrivateKeyEncoded());
    }
}
