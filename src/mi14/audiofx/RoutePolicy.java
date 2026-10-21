package mi14.audiofx;

/** Pure media-route policy: device type constants from Android AudioDeviceInfo. */
public final class RoutePolicy {
    private RoutePolicy() {}
    public static String key(int[] types,String[] addresses,int audioMode) {
        String route=target(types,audioMode);
        if(route==null)return null;
        if("speaker".equals(route))return Config.SPEAKER;
        if(addresses==null||addresses.length!=types.length)return null;
        String key=null;
        for(String address:addresses){String candidate=Config.bluetoothKey(address);if(Config.DEFAULT_BT.equals(candidate)&&addresses.length>1)return null;if(key!=null&&!key.equals(candidate))return null;key=candidate;}
        return key;
    }
    public static String target(int[] types, int audioMode) {
        if (audioMode != 0 || types == null || types.length == 0) return null;
        String target = null;
        for (int type : types) {
            String one = type == 2 ? "speaker" : (type == 8 || type == 26) ? "bt-none" : null;
            if (one == null || (target != null && !target.equals(one))) return null;
            target = one;
        }
        return target;
    }
}
