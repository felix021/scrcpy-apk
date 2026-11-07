package cn.felix021.scrcpy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static byte[] key(int action, int keycode) { return key(action, keycode, 0); }
    static byte[] key(int action, int keycode, int metaState) {
        return ByteBuffer.allocate(14).put((byte) 0).put((byte) action)
                .putInt(keycode).putInt(0).putInt(metaState).array();
    }

    /** An indivisible control-queue item, including modifier release in reverse order. */
    static byte[] chord(int keycode, int metaState) {
        int[] codes = {113, 57, 59}, masks = {0x3000, 0x12, 0x41};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int active = metaState & ~0x3053;
        for (int i = 0; i < codes.length; i++) {
            if ((metaState & masks[i]) != 0) {
                active |= masks[i];
                byte[] bytes = key(0, codes[i], active); out.write(bytes, 0, bytes.length);
            }
        }
        byte[] down = key(0, keycode, metaState), up = key(1, keycode, metaState);
        out.write(down, 0, down.length); out.write(up, 0, up.length);
        for (int i = codes.length - 1; i >= 0; i--) {
            if ((metaState & masks[i]) != 0) {
                active &= ~masks[i];
                byte[] bytes = key(1, codes[i], active); out.write(bytes, 0, bytes.length);
            }
        }
        return out.toByteArray();
    }

    static byte[] paste(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 65536) throw new IllegalArgumentException("TEXT_TOO_LARGE");
        return ByteBuffer.allocate(14 + utf8.length).put((byte) 9).putLong(0)
                .put((byte) 1).putInt(utf8.length).put(utf8).array();
    }

    static byte[] touch(int action, long pointer, int x, int y, int width, int height, float pressure) {
        return ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(pointer)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) Math.round(Math.max(0, Math.min(1, pressure)) * 65535))
                .putInt(0).putInt(0).array();
    }
}
