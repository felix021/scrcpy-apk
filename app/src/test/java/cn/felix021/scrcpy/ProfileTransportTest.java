package cn.felix021.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileTransportTest {
    @Test public void networkRemainsDefaultAndUsbDoesNotRequireAddressOrPort(){
        Profile p=new Profile();assertFalse(p.isUsb());assertEquals("tcp",p.transport);
        p.transport="usb";p.port=0;p.validate();assertEquals("USB",p.endpoint());
        p.usbLabel="Phone";assertEquals("USB · Phone",p.endpoint());
    }
    @Test public void endpointsKeepNetworkAndUsbDeviceProfilesSeparate(){
        Profile a=new Profile(),b=new Profile();a.host=b.host="phone.example";assertTrue(a.sameEndpoint(b));
        b.port++;assertFalse(a.sameEndpoint(b));b.transport="usb";assertFalse(a.sameEndpoint(b));
        a.transport="usb";a.usbVendor=b.usbVendor=12;a.usbProduct=b.usbProduct=34;assertTrue(a.sameEndpoint(b));
        a.usbSerial="one";b.usbSerial="two";assertFalse(a.sameEndpoint(b));
        b.usbSerial="one";assertTrue(a.sameEndpoint(b));b.usbVendor=13;assertFalse(a.sameEndpoint(b));
    }
}
