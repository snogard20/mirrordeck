package com.mirrordeck.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CrashLog {
    private static final String TAG = "MirrorDeckDiagnostics";
    private static final String FILE_NAME = "diagnostics.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final int MAX_ENTRY_CHARS = 64 * 1024;
    private static final Object LOCK = new Object();
    private static volatile File logFile;

    private CrashLog() {}

    static void initialize(Context context) {
        logFile = new File(context.getFilesDir(), FILE_NAME);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record(context, "Uncaught exception on " + thread.getName(), error);
            } catch (Throwable loggingError) {
                Log.e(TAG, "Could not record uncaught exception", loggingError);
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    static void record(Context context, String message, Throwable error) {
        File file = logFile;
        if (file == null) {
            file = new File(context.getFilesDir(), FILE_NAME);
            logFile = file;
        }

        StringBuilder entry = new StringBuilder();
        entry.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date()))
                .append(" [").append(Thread.currentThread().getName()).append("] ")
                .append(message).append('\n');
        if (error != null) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            entry.append(stack).append('\n');
        }
        if (entry.length() > MAX_ENTRY_CHARS) {
            entry.setLength(MAX_ENTRY_CHARS);
            entry.append("\nDiagnostic entry truncated.\n");
        }
        byte[] entryBytes = entry.toString().getBytes(StandardCharsets.UTF_8);

        synchronized (LOCK) {
            try {
                if (file.length() + entryBytes.length > MAX_BYTES) {
                    try (FileOutputStream output = new FileOutputStream(file, false)) {
                        output.write(header(context).getBytes(StandardCharsets.UTF_8));
                        output.write("Older diagnostics were removed to limit log size.\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                    }
                } else if (!file.exists() || file.length() == 0) {
                    try (FileOutputStream output = new FileOutputStream(file, false)) {
                        output.write(header(context).getBytes(StandardCharsets.UTF_8));
                    }
                }
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(entryBytes);
                    output.flush();
                    output.getFD().sync();
                }
            } catch (IOException writeError) {
                Log.e(TAG, "Could not write diagnostic log", writeError);
            }
        }
    }

    static void export(Context context, Uri destination) throws IOException {
        File file = logFile;
        if (file == null) {
            file = new File(context.getFilesDir(), FILE_NAME);
        }
        if (!file.exists()) {
            record(context, "Diagnostic log exported before any app events", null);
        }
        synchronized (LOCK) {
            try (OutputStream output = context.getContentResolver().openOutputStream(destination)) {
                if (output == null) {
                    throw new IOException("Could not open the selected destination");
                }
                try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static String header(Context context) {
        String version = "unknown";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.w(TAG, "Could not read app version for diagnostic log", e);
        }
        return "MirrorDeck diagnostics\n"
                + "App version: " + version + "\n"
                + "Android SDK: " + Build.VERSION.SDK_INT + "\n"
                + "Device model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";
    }
}
