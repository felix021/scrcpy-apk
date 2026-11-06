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
    boolean stayAwake = true;

    JSONObject json() throws JSONException {
        return new JSONObject().put("name", name).put("host", host).put("port", port)
                .put("maxSize", maxSize).put("bitrate", bitrate).put("fps", fps)
                .put("codec", codec).put("showNavigation", showNavigation).put("viewOnly", viewOnly).put("stayAwake", stayAwake);
    }

    static Profile from(JSONObject j) {
        Profile p = new Profile();
        p.name = j.optString("name", ""); p.host = j.optString("host", "");
        p.port = j.optInt("port", 5555); p.maxSize = j.optInt("maxSize", 1600);
        p.bitrate = j.optInt("bitrate", 6); p.fps = j.optInt("fps", 60);
        p.codec = j.optString("codec", "h264"); p.viewOnly = j.optBoolean("viewOnly");
        p.showNavigation = j.optBoolean("showNavigation", true);
        p.stayAwake = j.optBoolean("stayAwake", true);
        return p;
    }

    void validate() {
        if (host.isEmpty() || host.contains("://") || host.matches(".*[\\s/].*"))
            throw new IllegalArgumentException("请输入设备 IP 或主机名，端口请填在单独的输入框");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口范围：1–65535");
        if (maxSize != 0 && (maxSize < 256 || maxSize > 8192)) throw new IllegalArgumentException("最长边范围：256–8192；0 为原始尺寸");
        if (bitrate < 1 || bitrate > 100) throw new IllegalArgumentException("码率范围：1–100 Mbps");
        if (fps < 1 || fps > 120) throw new IllegalArgumentException("帧率范围：1–120 FPS");
        if (!codec.equals("h264") && !codec.equals("h265")) throw new IllegalArgumentException("不支持的编码");
    }

    String serverOptions() {
        return " max_size=" + maxSize + " video_bit_rate=" + (bitrate * 1000000)
                + " max_fps=" + fps + " video_codec=" + codec
                + " control=" + !viewOnly + " stay_awake=" + stayAwake;
    }
}
