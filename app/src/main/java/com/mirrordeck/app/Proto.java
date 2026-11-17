package com.mirrordeck.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * MirrorDeck wire protocol v1 (LAN, TCP, video only).
 *
 * Header, sent once by the sender right after connecting:
 *   4 bytes  magic "CDK1"
 *   int      width
 *   int      height
 *   int      spsLength, then sps bytes   (H.264 csd-0)
 *   int      ppsLength, then pps bytes   (H.264 csd-1)
 *
 * Then a stream of frames until disconnect:
 *   int      frameLength
 *   byte     flags (bit 0 = keyframe)
 *   byte[frameLength]  one H.264 access unit from the encoder
 *
 * All ints are big-endian (DataOutputStream.writeInt).
 */
public final class Proto {
    public static final int PORT = 47475;
    public static final String SERVICE_TYPE = "_mirrordeck._tcp.";
    public static final String SERVICE_NAME = "MirrorDeck";

    // UDP broadcast discovery (works even where mDNS/multicast is filtered):
    // sender broadcasts DISCOVER_QUERY to DISCOVERY_PORT; every receiver
    // answers unicast: DISCOVER_REPLY_PREFIX + name + "|" + tcpPort.
    public static final int DISCOVERY_PORT = 47476;
    public static final String DISCOVER_QUERY = "MDCK_DISCOVER_V1";
    public static final String DISCOVER_REPLY_PREFIX = "MDCK_HERE_V1|";
    public static final byte[] MAGIC = {'C', 'D', 'K', '1'};
    public static final int FLAG_KEYFRAME = 1;
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private Proto() {}

    public static void writeHeader(DataOutputStream out, int width, int height,
                                   byte[] sps, byte[] pps) throws IOException {
        out.write(MAGIC);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(sps.length);
        out.write(sps);
        out.writeInt(pps.length);
        out.write(pps);
        out.flush();
    }

    public static final class Header {
        public int width;
        public int height;
        public byte[] sps;
        public byte[] pps;
    }

    public static Header readHeader(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) throw new IOException("Bad magic");
        }
        Header h = new Header();
        h.width = in.readInt();
        h.height = in.readInt();
        int spsLen = in.readInt();
        if (spsLen <= 0 || spsLen > 4096) throw new IOException("Bad SPS length " + spsLen);
        h.sps = new byte[spsLen];
        in.readFully(h.sps);
        int ppsLen = in.readInt();
        if (ppsLen <= 0 || ppsLen > 4096) throw new IOException("Bad PPS length " + ppsLen);
        h.pps = new byte[ppsLen];
        in.readFully(h.pps);
        return h;
    }

    public static void writeFrame(DataOutputStream out, byte[] data, int offset, int length,
                                  boolean keyframe) throws IOException {
        out.writeInt(length);
        out.writeByte(keyframe ? FLAG_KEYFRAME : 0);
        out.write(data, offset, length);
        out.flush();
    }
}
