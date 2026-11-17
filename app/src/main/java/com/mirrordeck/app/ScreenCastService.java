package com.mirrordeck.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Sender engine: MediaProjection -> VirtualDisplay -> H.264 encoder (surface input)
 * -> TCP socket to the receiver, using the Proto framing.
 * Runs as a mediaProjection foreground service (required on Android 14+).
 */
public class ScreenCastService extends Service {
    private static final String TAG = "MirrorDeckSender";
    public static final String ACTION_START = "com.mirrordeck.app.CAST_START";
    public static final String ACTION_STOP = "com.mirrordeck.app.CAST_STOP";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DENSITY = "density";

    /** Set by the service so the sender screen can show live status. */
    public static volatile String liveStatus = "Not casting";
    public static volatile boolean running = false;

    private Thread worker;
    private volatile boolean stopRequested;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Socket socket;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRequested = true;
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY;
        }

        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, Proto.PORT);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int width = intent.getIntExtra(EXTRA_WIDTH, 720);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 1280);
        int density = intent.getIntExtra(EXTRA_DENSITY, 320);

        if (host == null || resultData == null) {
            liveStatus = "Missing receiver address or screen permission";
            stopSelf();
            return START_NOT_STICKY;
        }

        // Initialization order matters on Android 14/15 (Pixel): obtain the
        // MediaProjection and register its callback BEFORE claiming the
        // mediaProjection foreground-service type. Claiming the type first is
        // a known way for screen capture to fail to initialize.
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopRequested = true;
                }
            }, null);
        } catch (Exception e) {
            liveStatus = "Screen capture failed to initialize: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundWithNotification();

        running = true;
        stopRequested = false;
        liveStatus = "Connecting to " + host + "…";
        worker = new Thread(() -> runSession(host, port, width, height, density),
                "mirrordeck-sender");
        worker.start();
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        String channelId = "mirrordeck_casting";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    channelId, "Screen casting", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, channelId)
                .setContentTitle("MirrorDeck is sharing your screen")
                .setContentText("Tap MirrorDeck to stop")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void runSession(String host, int port, int width, int height, int density) {
        try {
            // 1) Connect first, so a bad address fails before the screen is captured.
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            liveStatus = "Connected — starting capture…";

            // 2) Encoder.
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            // 3) Screen capture into the encoder surface. The projection was
            // obtained and its callback registered in onStartCommand.
            virtualDisplay = projection.createVirtualDisplay(
                    "MirrorDeck", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);

            // 4) Stream: wait for the output format (carries SPS/PPS), send the
            //    header, then send length-prefixed access units.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean headerSent = false;
            while (!stopRequested) {
                int idx = encoder.dequeueOutputBuffer(info, 100_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = encoder.getOutputFormat();
                    ByteBuffer csd0 = outFormat.getByteBuffer("csd-0");
                    ByteBuffer csd1 = outFormat.getByteBuffer("csd-1");
                    if (csd0 == null || csd1 == null) {
                        throw new IllegalStateException("Encoder gave no SPS/PPS");
                    }
                    Proto.writeHeader(out, width, height, toBytes(csd0), toBytes(csd1));
                    headerSent = true;
                    liveStatus = "Casting to " + host;
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buffer = encoder.getOutputBuffer(idx);
                if (buffer != null && info.size > 0 && headerSent) {
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config) {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data);
                        Proto.writeFrame(out, data, 0, data.length, key);
                    }
                }
                encoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Session failed", e);
            if (!stopRequested) {
                liveStatus = "Casting stopped: " + e.getMessage();
            }
        } finally {
            cleanup();
            running = false;
            if (!stopRequested) {
                // keep the last error/status visible; service is done either way
            } else {
                liveStatus = "Not casting";
            }
            stopSelf();
        }
    }

    private static byte[] toBytes(ByteBuffer bb) {
        ByteBuffer dup = bb.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private void cleanup() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        encoder = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        cleanup();
        running = false;
        super.onDestroy();
    }
}
