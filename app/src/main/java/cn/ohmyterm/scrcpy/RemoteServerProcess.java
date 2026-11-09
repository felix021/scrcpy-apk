package cn.ohmyterm.scrcpy;

/** Ties a server to its ADB shell even if a static video produces no more writes.
 * Only the child started by this shell is terminated on EOF or explicit stop. */
final class RemoteServerProcess {
    static String owned(String command){
        return command+" </dev/null & child=$!; trap 'kill \"$child\" 2>/dev/null; wait \"$child\" 2>/dev/null' EXIT; "
                +"trap 'exit' HUP INT TERM; while IFS= read -r stop; do [ \"$stop\" = q ] && break; done";
    }
}
