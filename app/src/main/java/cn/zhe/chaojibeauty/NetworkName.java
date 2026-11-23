package cn.zhe.chaojibeauty;

import java.util.Locale;

final class NetworkName {
    private NetworkName() {}

    static String forSlot(String model, String nativeText) {
        String label = model == null || model.trim().isEmpty() ? nativeText : model;
        String key = label.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (key.startsWith("5G") || key.startsWith("5.5G") || key.startsWith("TIGO5G")
                || key.startsWith("NR5G") || key.equals("NR") || key.equals("NRSA")
                || key.equals("NRNSA") || key.equals("NRADVANCED")) return "5G";
        if (key.startsWith("4G") || key.startsWith("4.5G") || key.startsWith("LTE")) return "4G";
        if (key.startsWith("3G") || key.equals("UMTS") || key.equals("WCDMA")) return "3G";
        if (key.startsWith("2G") || key.equals("GSM")) return "2G";
        if (key.equals("E") || key.equals("EDGE")) return "E";
        if (key.equals("H") || key.equals("H+")) return key;
        if (key.equals("G") || key.equals("GPRS")) return "G";
        if (key.equals("1X") || key.equals("1XRTT")) return "1x";
        return "";
    }
}
