package dev.mi14.audiofxauto;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class RunLog {
    private RunLog() {}
    static synchronized void add(Context context, String text) {
        try {
            File file = new File(context.getFilesDir(), "audiofx.log");
            if (file.length() > 512000) {
                String old = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String tail = old.substring(Math.max(0, old.length() - 180000));
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(tail.getBytes(StandardCharsets.UTF_8)); }
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + text + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) { android.util.Log.e("AudioFxAuto", "Log write failed", e); }
    }
    static synchronized String read(Context context) {
        try { return new String(Files.readAllBytes(new File(context.getFilesDir(), "audiofx.log").toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { return "暂无运行日志。"; }
    }
}
