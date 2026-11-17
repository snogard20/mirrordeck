package com.mirrordeck.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * MirrorDeck home: one question, two big buttons.
 * "Share my screen" = this device is the sender.
 * "Show a screen here" = this device is the receiver.
 */
public class MainActivity extends Activity {
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
                "Standalone MirrorDeck v1.3 — its own app, installs next to any " +
                "other casting app without touching it. Screen video and supported " +
                "playback audio, same Wi-Fi network, one sender to one receiver. " +
                "Install this same app on both devices."));

        setContentView(root);
    }
}
