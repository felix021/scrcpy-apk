package cn.felix021.scrcpy;

import android.content.Context;
import android.hardware.usb.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class UsbAdb {
    static UsbInterface adbInterface(UsbDevice device){
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface face=device.getInterface(i);
            if(face.getInterfaceClass()==255 && face.getInterfaceSubclass()==66 && face.getInterfaceProtocol()==1
                    && endpoint(face,UsbConstants.USB_DIR_IN)!=null && endpoint(face,UsbConstants.USB_DIR_OUT)!=null)return face;
        }
        return null;
    }
    private static UsbEndpoint endpoint(UsbInterface face,int direction){
        for(int i=0;i<face.getEndpointCount();i++){
            UsbEndpoint ep=face.getEndpoint(i);
            if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection()==direction)return ep;
        }
        return null;
    }
    static List<UsbDevice> devices(UsbManager manager){
        List<UsbDevice> result=new ArrayList<>();
        if(manager==null)return result;
        for(UsbDevice device:manager.getDeviceList().values())if(adbInterface(device)!=null)result.add(device);
        result.sort(Comparator.comparing(UsbDevice::getDeviceName));return result;
    }
    static String label(UsbDevice device){
        String name=device.getProductName();
        return name==null || name.trim().isEmpty()?String.format(java.util.Locale.ROOT,"USB %04x:%04x",device.getVendorId(),device.getProductId()):name;
    }
    static String serial(UsbManager manager,UsbDevice device){
        if(manager==null)return "";
        try{String value=manager.hasPermission(device)?device.getSerialNumber():null;return value==null?"":value;}
        catch(SecurityException e){return "";}
    }
    static UsbAdbStreams open(Context context,UsbDevice device)throws IOException{
        UsbManager manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
        if(manager==null)throw new IOException(UiText.text(R.string.usb_host_unavailable));
        UsbInterface face=device==null?null:adbInterface(device);
        if(face==null || !manager.getDeviceList().containsKey(device.getDeviceName()))throw new IOException(UiText.text(R.string.usb_not_connected));
        if(!manager.hasPermission(device))throw new IOException(UiText.text(R.string.usb_permission_denied));
        UsbDeviceConnection connection=manager.openDevice(device);
        if(connection==null)throw new IOException(UiText.text(R.string.usb_open_failed));
        try{
            if(!connection.claimInterface(face,true) || face.getAlternateSetting()!=0 && !connection.setInterface(face))throw new IOException(UiText.text(R.string.usb_open_failed));
            NativeIo io=new NativeIo(connection,endpoint(face,UsbConstants.USB_DIR_IN),endpoint(face,UsbConstants.USB_DIR_OUT));
            try{return new UsbAdbStreams(io);}catch(RuntimeException e){io.cancel();io.close();throw e;}
        }catch(IOException|RuntimeException e){connection.close();throw e;}
    }
    private static final class NativeIo implements UsbAdbStreams.BulkIo {
        final UsbDeviceConnection connection;
        final UsbEndpoint out;
        final UsbRequest request=new UsbRequest();
        final ByteBuffer input=ByteBuffer.allocateDirect(UsbAdbStreams.CHUNK);
        final AtomicBoolean cancelled=new AtomicBoolean();
        boolean disposed;
        NativeIo(UsbDeviceConnection connection,UsbEndpoint in,UsbEndpoint out)throws IOException{
            this.connection=connection;this.out=out;
            if(!request.initialize(connection,in)){request.close();throw new IOException(UiText.text(R.string.usb_open_failed));}
        }
        public int packetSize(){return out.getMaxPacketSize();}
        public int read(byte[] bytes,int length)throws IOException{
            synchronized(request){
                if(cancelled.get())throw new IOException(UiText.text(R.string.usb_disconnected));
                input.clear();input.limit(length);
                if(!request.queue(input))throw new IOException(UiText.text(R.string.usb_disconnected));
            }
            while(true){
                try{
                    UsbRequest completed=connection.requestWait(1000);
                    if(completed!=request)throw new IOException(UiText.text(R.string.usb_disconnected));
                    if(cancelled.get())throw new IOException(UiText.text(R.string.usb_disconnected));
                    int count=input.position();input.flip();input.get(bytes,0,count);return count;
                }catch(TimeoutException ignored){
                    // Keep the same URB queued across idle timeouts; requeuing can lose partial data.
                    if(cancelled.get())throw new IOException(UiText.text(R.string.usb_disconnected));
                }catch(RuntimeException e){throw new IOException(UiText.text(R.string.usb_disconnected),e);}
            }
        }
        public synchronized void write(byte[] bytes,int offset,int length)throws IOException{
            try{
                if(cancelled.get() || connection.bulkTransfer(out,bytes,offset,length,5000)!=length)
                    throw new IOException(UiText.text(R.string.usb_transfer_failed));
            }catch(RuntimeException e){throw new IOException(UiText.text(R.string.usb_transfer_failed),e);}
        }
        public void cancel(){
            cancelled.set(true);synchronized(request){if(!disposed)request.cancel();}
        }
        public synchronized void close(){
            cancel();synchronized(request){if(!disposed){disposed=true;request.close();}}
            connection.close(); // Reader disposes after pending I/O; fd close releases the interface.
        }
    }
}
