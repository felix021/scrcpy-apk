package cn.ohmyterm.scrcpy.adb;

import java.io.IOException;

final class NativeCrypto {
    static { System.loadLibrary("adbcrypto"); }
    private NativeCrypto() { }
    static native long tlsCreate(byte[] pkcs8) throws IOException;
    static native void tlsFree(long handle);
    static native int tlsHandshake(long handle);
    static native int tlsIO(long handle,byte[] bytes,int offset,int length,boolean write);
    static native void tlsFeed(long handle,byte[] bytes,int count) throws IOException;
    static native byte[] tlsDrain(long handle);
    static native byte[] tlsExport(long handle) throws IOException;
    static native long pairCreate(byte[] secret) throws IOException;
    static native void pairFree(long handle);
    static native byte[] pairMessage(long handle) throws IOException;
    static native void pairProcess(long handle,byte[] message) throws IOException;
    static native byte[] pairCrypt(long handle,byte[] message,boolean encrypt) throws IOException;
}
