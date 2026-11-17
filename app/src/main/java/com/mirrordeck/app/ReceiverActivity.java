package com.mirrordeck.app;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Receiver screen: advertises "_mirrordeck._tcp" on the LAN, listens on
 * Proto.PORT, and decodes the incoming H.264 stream onto a full-screen
 * SurfaceView. One sender at a time; when it disconnects, it waits again.
 */
public class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "MirrorDeckReceiver";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Surface surface;
    private volatile boolean alive;
    private Thread serverThread;
    private Thread audioServerThread;
    private ServerSocket serverSocket;
    private volatile ServerSocket audioServerSocket;
    private Socket currentSocket;
    private volatile Socket currentAudioSocket;
    private volatile AudioTrack audioTrack;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private WifiManager.MulticastLock multicastLock;
    private DatagramSocket udpSocket;

    /** The friendly name this device shows up as on the sender's list. */
    static String displayName() {
        String model = Build.MODEL != null ? Build.MODEL : "Android device";
        String maker = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        return ("MirrorDeck " + maker + " " + model).replaceAll("\\s+", " ").trim();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        SurfaceView video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(17);
        status.setPadding(40, 40, 40, 40);
        root.addView(status);

        setContentView(root);
        setStatus("Starting receiver…");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        if (!alive) {
            alive = true;
            registerNsd();
            startUdpResponder();
            serverThread = new Thread(this::serverLoop, "mirrordeck-receiver");
            serverThread.start();
            audioServerThread = new Thread(this::audioServerLoop, "mirrordeck-audio-receiver");
            audioServerThread.start();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        surface = holder.getSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(Proto.PORT);
        } catch (Exception e) {
            setStatus("Could not listen on port " + Proto.PORT + ": " + e.getMessage());
            return;
        }
        String ip = localIp();
        while (alive) {
            setStatus("Ready to receive\n\nThis device shows up on the sending phone as:\n\"" +
                    displayName() + "\"\n\n" +
                    "On the sending phone: open MirrorDeck → \"Share my screen\" → " +
                    "tap that name. That's it — nothing to type, no settings." +
                    (ip != null ? "\n\n(Backup address, only if ever needed: " + ip + ")" : ""));
            Socket s;
            try {
                s = serverSocket.accept();
            } catch (Exception e) {
                if (alive) setStatus("Listener stopped: " + e.getMessage());
                break;
            }
            currentSocket = s;
            try {
                streamSession(s);
            } catch (Exception e) {
                // Sender disconnected or sent garbage; go back to waiting.
            } finally {
                try { s.close(); } catch (Exception ignored) {}
                currentSocket = null;
            }
        }
    }

    private void streamSession(Socket s) throws Exception {
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        Proto.Header header = Proto.readHeader(in);
        setStatus("Connecting…");

        // Wait briefly for the surface if the sender beat the UI to it.
        for (int i = 0; i < 50 && surface == null && alive; i++) Thread.sleep(100);
        if (surface == null) throw new IllegalStateException("No display surface");

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, header.width, header.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(header.sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(header.pps));

        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(format, surface, null, 0);
        decoder.start();
        setStatus("");
        hideStatusSoon();

        Thread outputThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (alive && !Thread.currentThread().isInterrupted()) {
                try {
                    int idx = decoder.dequeueOutputBuffer(info, 100_000);
                    if (idx >= 0) {
                        decoder.releaseOutputBuffer(idx, true);
                    }
                } catch (Exception e) {
                    return;
                }
            }

        }, "mirrordeck-decoder-out");
        outputThread.setDaemon(true);
        outputThread.start();

        try {
            while (alive) {
                int len = in.readInt();
                if (len <= 0 || len > Proto.MAX_FRAME_BYTES) {
                    throw new IllegalStateException("Bad frame length " + len);
                }
                int flags = in.readUnsignedByte();
                byte[] frame = new byte[len];
                in.readFully(frame);

                int idx;
                while ((idx = decoder.dequeueInputBuffer(100_000)) < 0) {
                    if (!alive) return;
                }
                ByteBuffer input = decoder.getInputBuffer(idx);
                if (input == null) continue;
                input.clear();
                input.put(frame);
                decoder.queueInputBuffer(idx, 0, len,
                        System.nanoTime() / 1000, 0);
            }
        } finally {
            outputThread.interrupt();
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            ui.post(() -> status.setVisibility(View.VISIBLE));
        }
    }

    private void audioServerLoop() {
        try {
            audioServerSocket = new ServerSocket(Proto.AUDIO_PORT);
            while (alive) {
                Socket s = audioServerSocket.accept();
                currentAudioSocket = s;
                try {
                    streamAudio(s);
                } catch (Exception e) {
                    if (alive) Log.w(TAG, "Audio stream ended", e);
                } finally {
                    try { s.close(); } catch (Exception ignored) {}
                    currentAudioSocket = null;
                }
            }
        } catch (Exception e) {
            if (alive) {
                Log.e(TAG, "Could not listen for audio on port " + Proto.AUDIO_PORT, e);
            }
        }
    }

    private void streamAudio(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(s.getInputStream(), 16 * 1024));
        Proto.readAudioHeader(in);
        int minBuffer = AudioTrack.getMinBufferSize(Proto.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IllegalStateException("Audio playback format is unsupported");
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuffer * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("Audio player did not initialize");
        }
        audioTrack = track;
        track.play();
        try {
            while (alive) {
                int length = in.readInt();
                if (length <= 0 || length > Proto.MAX_AUDIO_PACKET_BYTES || (length & 1) != 0) {
                    throw new IllegalStateException("Bad audio packet length " + length);
                }
                byte[] packet = new byte[length];
                in.readFully(packet);
                int offset = 0;
                while (offset < length && alive) {
                    int written = track.write(packet, offset, length - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IOException("Audio playback write failed: " + written);
                    offset += written;
                }
            }
        } finally {
            audioTrack = null;
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
        }
    }

    private void hideStatusSoon() {
        ui.post(() -> status.setVisibility(View.INVISIBLE));
    }

    private void registerNsd() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("mirrordeck-receiver");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}

        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(displayName());
            info.setServiceType(Proto.SERVICE_TYPE);
            info.setPort(Proto.PORT);
            registrationListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) {}
                @Override public void onRegistrationFailed(NsdServiceInfo i, int err) {}
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception ignored) {
            // Manual IP still works if discovery registration fails.
        }
    }

    /**
     * Answers UDP broadcast discovery: a sender shouts Proto.DISCOVER_QUERY
     * to the whole network; we reply with our name and stream port. This
     * path works on routers that filter mDNS multicast, where NSD fails.
     */
    private void startUdpResponder() {
        Thread t = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(Proto.DISCOVERY_PORT);
                udpSocket.setBroadcast(true);
                byte[] buf = new byte[256];
                while (alive) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(),
                            StandardCharsets.UTF_8).trim();
                    if (!Proto.DISCOVER_QUERY.equals(msg)) continue;
                    String reply = Proto.DISCOVER_REPLY_PREFIX + displayName()
                            + "|" + Proto.PORT;
                    byte[] rb = reply.getBytes(StandardCharsets.UTF_8);
                    udpSocket.send(new DatagramPacket(rb, rb.length,
                            p.getAddress(), p.getPort()));
                }
            } catch (Exception e) {
                // One of three finders; NSD and the sender's network scan remain.
            }
        }, "mirrordeck-udp-discovery");
        t.setDaemon(true);
        t.start();
    }

    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            WifiInfo info = wifi.getConnectionInfo();
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            return InetAddress.getByAddress(new byte[]{
                    (byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >> 24)})
                    .getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private void setStatus(String text) {
        ui.post(() -> {
            status.setVisibility(View.VISIBLE);
            status.setText(text);
        });
    }

    @Override
    protected void onDestroy() {
        alive = false;
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (currentSocket != null) currentSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (currentAudioSocket != null) currentAudioSocket.close(); } catch (Exception ignored) {}
        try { if (audioServerSocket != null) audioServerSocket.close(); } catch (Exception ignored) {}
        try { if (audioTrack != null) audioTrack.stop(); } catch (Exception ignored) {}
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager.unregisterService(registrationListener);
            }
        } catch (Exception ignored) {}
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
