package cn.felix021.scrcpy;

import org.json.JSONException;
import org.json.JSONObject;

final class Profile {
    String name = "";
    String host = "";
    int port = 5555;
    int maxSize = 1600;
    int bitrate = 6;
    int fps = 60;
    String codec = "h264";
    boolean showNavigation = true;
    boolean viewOnly;
    volatile boolean syncRotation = true;
    volatile boolean forwardAudio = true;
    volatile boolean forwardMic;
    volatile boolean stayAwake = true;

    JSONObject json() throws JSONException {
        return new JSONObject().put("name", name).put("host", host).put("port", port)
                .put("maxSize", maxSize).put("bitrate", bitrate).put("fps", fps)
                .put("syncRotation",syncRotation).put("codec", codec).put("forwardAudio",forwardAudio).put("forwardMic",forwardMic).put("showNavigation", showNavigation).put("viewOnly", viewOnly).put("stayAwake", stayAwake);
    }

    static Profile from(JSONObject j) {
        Profile p = new Profile();
        p.name = j.optString("name", ""); p.host = j.optString("host", "");
        p.port = j.optInt("port", 5555); p.maxSize = j.optInt("maxSize", 1600);
        p.bitrate = j.optInt("bitrate", 6); p.fps = j.optInt("fps", 60);
        p.codec = j.optString("codec", "h264"); p.viewOnly = j.optBoolean("viewOnly");
        p.showNavigation = j.optBoolean("showNavigation", true);
        p.forwardAudio=j.optBoolean("forwardAudio",true);p.forwardMic=j.optBoolean("forwardMic",false);
        p.syncRotation=j.optBoolean("syncRotation",true);
        p.stayAwake = j.optBoolean("stayAwake", true);
        return p;
    }

    Profile copy() {try{return from(json());}catch(JSONException e){throw new IllegalStateException(e);}}

    void validate() {
        if (host.isEmpty() || host.contains("://") || host.matches(".*[\\s/].*"))
            throw new IllegalArgumentException(UiText.text(R.string.enter_a_device_ip_or_hostname_enter_the_port_in_its_separate_fie));
        if (port < 1 || port > 65535) throw new IllegalArgumentException(UiText.text(R.string.port_range_1_65535));
        if (maxSize != 0 && (maxSize < 256 || maxSize > 8192)) throw new IllegalArgumentException(UiText.text(R.string.max_edge_range_256_8192_0_original_size));
        if (bitrate < 1 || bitrate > 100) throw new IllegalArgumentException(UiText.text(R.string.bit_rate_range_1_100_mbps));
        if (fps < 1 || fps > 120) throw new IllegalArgumentException(UiText.text(R.string.frame_rate_range_1_120_fps));
        if (!codec.equals("h264") && !codec.equals("h265")) throw new IllegalArgumentException(UiText.text(R.string.unsupported_codec));
    }

    String serverOptions() {
        return " max_size=" + maxSize + " video_bit_rate=" + (bitrate * 1000000)
                + " max_fps=" + fps + " video_codec=" + codec
                + " control=" + !viewOnly + " stay_awake=false";
    }
}
