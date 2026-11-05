package cn.felix021.scrcpy;

import java.nio.ByteBuffer;

final class Protocol {
    static byte[] key(int action, int keycode) {
        return ByteBuffer.allocate(14).put((byte) 0).put((byte) action)
                .putInt(keycode).putInt(0).putInt(0).array();
    }

    static byte[] touch(int action, long pointer, int x, int y, int width, int height, float pressure) {
        return ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(pointer)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) Math.round(Math.max(0, Math.min(1, pressure)) * 65535))
                .putInt(0).putInt(0).array();
    }
}
