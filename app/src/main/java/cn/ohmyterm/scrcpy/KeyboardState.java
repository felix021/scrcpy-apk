package cn.ohmyterm.scrcpy;

final class KeyboardState {
    // 0 off, 1 one-shot, 2 locked. Fn is a local layer, never a remote modifier.
    int ctrl, alt, shift, win, fn;
    boolean caps, symbols;
    int meta() { return (ctrl > 0 ? 0x3000 : 0) | (alt > 0 ? 0x12 : 0) | (shift > 0 ? 0x41 : 0) | (win > 0 ? 0x30000 : 0) | (caps ? 0x100000 : 0); }
    void consume() {
        if (ctrl == 1) ctrl = 0; if (alt == 1) alt = 0;
        if (shift == 1) shift = 0; if (fn == 1) fn = 0;
        if (win == 1) win = 0;
    }
    void clear() { ctrl = alt = shift = win = fn = 0; caps = symbols = false; }
    static int toggle(int state) { return state == 0 ? 1 : 0; }
    static int lock(int state) { return state == 2 ? 0 : 2; }
    boolean upper() { return caps ^ (shift > 0); }

    static char alternate(char c) {
        String keys="qwertyuiopasdfghjklzxcvbnm.";
        String values="1234567890-/:;()~'\"@_#&?!.,";
        int index=keys.indexOf(Character.toLowerCase(c));
        return index<0?0:values.charAt(index);
    }

    static int code(char c) {
        if (c >= 'a' && c <= 'z') return 29 + c - 'a';
        if (c >= '0' && c <= '9') return 7 + c - '0';
        String plain = "`-=[]\\;',./";
        int[] codes = {68,69,70,71,72,73,74,75,55,56,76};
        int i = plain.indexOf(c); if (i >= 0) return codes[i];
        String shifted = "~_+{}|:\"<>?";
        i = shifted.indexOf(c); if (i >= 0) return codes[i];
        String numberShift = ")!@#$%^&*(";
        i = numberShift.indexOf(c); if (i >= 0) return 7 + i;
        throw new IllegalArgumentException("Unsupported key: " + c);
    }
    static char shifted(char c) {
        if(c>='a' && c<='z')return Character.toUpperCase(c);
        if(c>='0' && c<='9')return ")!@#$%^&*(".charAt(c-'0');
        String plain="`-=[]\\;',./", shifted="~_+{}|:\"<>?";
        int i=plain.indexOf(c);return i>=0?shifted.charAt(i):c;
    }
    static boolean needsShift(char c) { return "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0; }
}
