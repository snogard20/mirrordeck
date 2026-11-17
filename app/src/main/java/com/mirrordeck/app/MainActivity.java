package com.mirrordeck.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * MirrorDeck home: one question, two big buttons.
 * "Share my screen" = this device is the sender.
 * "Show a screen here" = this device is the receiver.
 */
public class MainActivity extends Activity {
    private static final int REQ_EXPORT_DIAGNOSTICS = 1101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "MirrorDeck"));
        root.addView(Ui.body(this,
                "Cast this phone's screen to another Android device on the same Wi-Fi. " +
                "No accounts, no ads, no setup wizard. Devices find each other " +
                "automatically — there are no addresses or settings to enter."));

        Button share = Ui.bigButton(this, "📤  Share my screen", Ui.ACCENT);
        share.setOnClickListener(v ->
                startActivity(new Intent(this, SenderActivity.class)));
        root.addView(share);

        Button show = Ui.bigButton(this, "📥  Show a screen here", Ui.ACCENT2);
        show.setOnClickListener(v ->
                startActivity(new Intent(this, ReceiverActivity.class)));
        root.addView(show);

        root.addView(Ui.body(this,
                "After a crash, reopen MirrorDeck and save a diagnostic log to help "
                        + "troubleshoot. It contains app errors and device/app version, "
                        + "not screen or audio content."));
        Button logs = Ui.bigButton(this, "Save diagnostic log", Ui.CARD);
        logs.setOnClickListener(v -> saveDiagnosticLog());
        root.addView(logs);

        root.addView(Ui.body(this,
                "Standalone MirrorDeck v1.4 — its own app, installs next to any " +
                "other casting app without touching it. Screen video and supported " +
                "playback audio, same Wi-Fi network, one sender to one receiver. " +
                "Install this same app on both devices."));

        setContentView(root);
    }

    private void saveDiagnosticLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "mirrordeck-diagnostics.txt");
        try {
            startActivityForResult(intent, REQ_EXPORT_DIAGNOSTICS);
        } catch (ActivityNotFoundException e) {
            CrashLog.record(this, "Could not open a file picker to export diagnostics", e);
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_DIAGNOSTICS || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri destination = data.getData();
        try {
            CrashLog.export(this, destination);
            Toast.makeText(this, "Diagnostic log saved", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            CrashLog.record(this, "Could not export diagnostic log", e);
            Toast.makeText(this, "Could not save diagnostic log: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
