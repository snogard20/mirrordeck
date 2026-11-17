package com.mirrordeck.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sender screen — built to be click-and-it-works:
 *
 * Devices are found three independent ways, automatically, no typing:
 *   1. NSD / mDNS advertisement from the receiver.
 *   2. UDP broadcast shout — the receiver answers with its name and port.
 *      (Works on routers that filter multicast, where NSD alone fails.)
 *   3. A direct scan of the phone's own /24 for the MirrorDeck port,
 *      if the first two have found nothing after a few seconds.
 *
 * Tapping a device in the list starts casting to it immediately.
 * The manual-address box exists only as a last-resort fallback, hidden
 * behind a small link — a normal user never needs it.
 */
public class SenderActivity extends Activity {
    private static final int REQ_PROJECTION = 1001;

    private static final class Entry {
        String name;
        final String host;
        final int port;
        Entry(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView searchStatus;
    private TextView status;
    private EditText ipInput;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private boolean resolving;

    private volatile boolean discovering;
    private DatagramSocket udpSocket;
    private volatile boolean scanRunning;

    private String pendingHost;
    private int pendingPort = Proto.PORT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "Share my screen"));
        root.addView(Ui.body(this,
                "Devices on your Wi-Fi appear below by themselves. " +
                "Tap one and casting starts — nothing to type, no settings."));

        searchStatus = Ui.status(this, "Looking for devices on your Wi-Fi…");
        root.addView(searchStatus);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        list.setLayoutParams(listLp);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry e = entries.get(position);
            pendingHost = e.host;
            pendingPort = e.port;
            askProjection();
        });
        root.addView(list);

        Button stop = Ui.bigButton(this, "■  Stop casting", Ui.CARD);
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScreenCastService.class);
            i.setAction(ScreenCastService.ACTION_STOP);
            startService(i);
            status.setText("Not casting");
        });
        root.addView(stop);

        status = Ui.status(this, "Not casting");
        root.addView(status);

        // Last-resort manual entry, tucked away — not part of the normal flow.
        LinearLayout manualBox = new LinearLayout(this);
        manualBox.setOrientation(LinearLayout.VERTICAL);
        manualBox.setVisibility(View.GONE);
        ipInput = new EditText(this);
        ipInput.setHint("Device address, e.g. 192.168.1.42");
        ipInput.setTextColor(Ui.TEXT);
        ipInput.setHintTextColor(Ui.MUTED);
        ipInput.setSingleLine(true);
        manualBox.addView(ipInput);
        Button manualStart = Ui.bigButton(this, "Start with this address", Ui.CARD);
        manualStart.setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                toast("Type the address shown on the other device");
                return;
            }
            pendingHost = ip;
            pendingPort = Proto.PORT;
            askProjection();
        });
        manualBox.addView(manualStart);
        TextView manualLink = Ui.body(this, "Can't see your device? Enter its address manually");
        manualLink.setTextColor(Ui.ACCENT);
        manualLink.setOnClickListener(v ->
                manualBox.setVisibility(manualBox.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        root.addView(manualLink);
        root.addView(manualBox);

        setContentView(root);
        discovering = true;
        startNsdDiscovery();
        startUdpDiscovery();
        ui.postDelayed(this::maybeScan, 7000);
        pollStatus();
    }

    private void pollStatus() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                status.setText(ScreenCastService.liveStatus);
                if (!ScreenCastService.running) {
                    searchStatus.setText(entries.isEmpty()
                            ? "Looking for devices on your Wi-Fi…"
                            : "Tap a device to start casting:");
                } else {
                    searchStatus.setText("");
                }
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ---------- device list ----------

    private void addEntry(String name, String host, int port) {
        if (host == null || host.isEmpty()) return;
        ui.post(() -> {
            for (Entry e : entries) {
                if (e.host.equals(host)) {
                    // Upgrade a generic scan-found label to a real device name.
                    if (e.name.startsWith("MirrorDeck device") && name != null
                            && !name.startsWith("MirrorDeck device")) {
                        e.name = name;
                        labels.set(entries.indexOf(e), name);
                        adapter.notifyDataSetChanged();
                    }
                    return;
                }
            }
            String label = (name == null || name.isEmpty()) ? "MirrorDeck device" : name;
            entries.add(new Entry(label, host, port));
            labels.add(label);
            adapter.notifyDataSetChanged();
        });
    }

    // ---------- casting ----------

    private void askProjection() {
        // No other permission dialogs here: an extra dialog racing Android's
        // screen-share prompt can make the prompt fail to initialize.
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            toast("Screen sharing was not approved");
            return;
        }
        // Cap the stream at 1280 on the long edge, keep aspect, even dimensions.
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float scale = Math.min(1f, 1280f / Math.max(w, h));
        int sw = Math.max(2, (int) (w * scale) & ~1);
        int sh = Math.max(2, (int) (h * scale) & ~1);

        Intent svc = new Intent(this, ScreenCastService.class);
        svc.setAction(ScreenCastService.ACTION_START);
        svc.putExtra(ScreenCastService.EXTRA_HOST, pendingHost);
        svc.putExtra(ScreenCastService.EXTRA_PORT, pendingPort);
        svc.putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenCastService.EXTRA_RESULT_DATA, data);
        svc.putExtra(ScreenCastService.EXTRA_WIDTH, sw);
        svc.putExtra(ScreenCastService.EXTRA_HEIGHT, sh);
        svc.putExtra(ScreenCastService.EXTRA_DENSITY, dm.densityDpi);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            status.setText("Could not start casting: " + e.getMessage());
            return;
        }
        status.setText("Connecting to " + pendingHost + "…");
        // Stay on screen so the status line is visible. Once it says
        // "Casting", the user presses Home and opens what they want to show.
        toast("Starting — when status says Casting, press Home and open the app to show");
    }

    // ---------- finder 1: NSD / mDNS ----------

    private void startNsdDiscovery() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("mirrordeck-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int err) {}
            @Override public void onStopDiscoveryFailed(String type, int err) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!info.getServiceType().contains("mirrordeck")) return;
                resolveNext(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                ui.post(() -> {
                    for (int i = 0; i < entries.size(); i++) {
                        if (entries.get(i).name.equals(info.getServiceName())) {
                            entries.remove(i);
                            labels.remove(i);
                            adapter.notifyDataSetChanged();
                            break;
                        }
                    }
                });
            }
        };
        try {
            nsdManager.discoverServices(Proto.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception ignored) {
            // Two other finders still run.
        }
    }

    /** NSD allows only one resolve at a time on older Android; chain them. */
    private synchronized void resolveNext(NsdServiceInfo info) {
        if (resolving) {
            ui.postDelayed(() -> resolveNext(info), 300);
            return;
        }
        resolving = true;
        try {
            nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int err) {
                    resolving = false;
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    resolving = false;
                    if (resolved.getHost() == null) return;
                    addEntry(resolved.getServiceName(),
                            resolved.getHost().getHostAddress(),
                            resolved.getPort() > 0 ? resolved.getPort() : Proto.PORT);
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    // ---------- finder 2: UDP broadcast ----------

    private void startUdpDiscovery() {
        Thread t = new Thread(() -> {
            byte[] query = Proto.DISCOVER_QUERY.getBytes(StandardCharsets.UTF_8);
            while (discovering) {
                try {
                    if (udpSocket == null || udpSocket.isClosed()) {
                        udpSocket = new DatagramSocket();
                        udpSocket.setBroadcast(true);
                        udpSocket.setSoTimeout(500);
                    }
                    for (InetAddress target : broadcastAddresses()) {
                        udpSocket.send(new DatagramPacket(query, query.length,
                                target, Proto.DISCOVERY_PORT));
                    }
                    long deadline = System.currentTimeMillis() + 1800;
                    byte[] buf = new byte[512];
                    while (discovering && System.currentTimeMillis() < deadline) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            udpSocket.receive(p);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        String msg = new String(p.getData(), 0, p.getLength(),
                                StandardCharsets.UTF_8).trim();
                        if (!msg.startsWith(Proto.DISCOVER_REPLY_PREFIX)) continue;
                        String[] parts = msg.substring(
                                Proto.DISCOVER_REPLY_PREFIX.length()).split("\\|");
                        String name = parts.length > 0 ? parts[0] : "MirrorDeck device";
                        int port = Proto.PORT;
                        if (parts.length > 1) {
                            try { port = Integer.parseInt(parts[1].trim()); }
                            catch (NumberFormatException ignored) {}
                        }
                        addEntry(name, p.getAddress().getHostAddress(), port);
                    }
                } catch (Exception e) {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                }
            }
        }, "mirrordeck-udp-find");
        t.setDaemon(true);
        t.start();
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- finder 3: direct subnet scan (fallback) ----------

    private void maybeScan() {
        if (isFinishing()) return;
        if (!entries.isEmpty() || scanRunning || ScreenCastService.running) {
            ui.postDelayed(this::maybeScan, 15000);
            return;
        }
        scanRunning = true;
        Thread t = new Thread(() -> {
            try {
                String myIp = localIp();
                if (myIp != null) {
                    String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
                    int myLast = Integer.parseInt(
                            myIp.substring(myIp.lastIndexOf('.') + 1));
                    ExecutorService pool = Executors.newFixedThreadPool(24);
                    for (int i = 1; i < 255; i++) {
                        if (i == myLast) continue;
                        final String host = prefix + i;
                        pool.submit(() -> {
                            try {
                                Socket s = new Socket();
                                s.connect(new java.net.InetSocketAddress(host, Proto.PORT), 300);
                                s.close();
                                addEntry("MirrorDeck device", host, Proto.PORT);
                            } catch (Exception ignored) {}
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(20, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            } finally {
                scanRunning = false;
                ui.postDelayed(this::maybeScan, 15000);
            }
        }, "mirrordeck-scan");
        t.setDaemon(true);
        t.start();
    }

    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return InetAddress.getByAddress(new byte[]{
                    (byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >> 24)})
                    .getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        discovering = false;
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try {
            if (nsdManager != null && discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
        } catch (Exception ignored) {}
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
