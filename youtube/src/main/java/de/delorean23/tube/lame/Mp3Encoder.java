package de.delorean23.tube.lame;

import de.delorean23.tube.lame.mp3.BRHist;
import de.delorean23.tube.lame.mp3.BitStream;
import de.delorean23.tube.lame.mp3.GainAnalysis;
import de.delorean23.tube.lame.mp3.GetAudio;
import de.delorean23.tube.lame.mp3.ID3Tag;
import de.delorean23.tube.lame.mp3.Lame;
import de.delorean23.tube.lame.mp3.LameGlobalFlags;
import de.delorean23.tube.lame.mp3.Parse;
import de.delorean23.tube.lame.mp3.Presets;
import de.delorean23.tube.lame.mp3.Quantize;
import de.delorean23.tube.lame.mp3.QuantizePVT;
import de.delorean23.tube.lame.mp3.Reservoir;
import de.delorean23.tube.lame.mp3.Takehiro;
import de.delorean23.tube.lame.mp3.VBRTag;
import de.delorean23.tube.lame.mp3.Version;
import de.delorean23.tube.lame.mpg.Common;
import de.delorean23.tube.lame.mpg.Interface;
import de.delorean23.tube.lame.mpg.MPGLib;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Kleiner Wrapper um den Java-LAME-Encoder (jump3r, LGPL).
 * Nimmt 16-bit-PCM (little endian, interleaved) entgegen und schreibt MP3 (CBR).
 * Created by DeLorean23
 */
public final class Mp3Encoder {

    private final Lame lame;
    private final LameGlobalFlags gfp;
    private final OutputStream out;
    private final int channels;
    private byte[] mp3buf = new byte[0];

    public Mp3Encoder(int sampleRate, int channels, int bitrateKbps,
                      String title, String artist, OutputStream out) throws IOException {
        this.out = out;
        this.channels = channels <= 1 ? 1 : 2;

        lame = new Lame();
        GetAudio gaud = new GetAudio();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro();
        Parse parse = new Parse();
        new BRHist();
        MPGLib mpg = new MPGLib();
        Interface intf = new Interface();
        Common common = new Common();

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        gaud.setModules(parse, mpg);
        parse.setModules(ver, id3, p);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        gfp = lame.lame_init();
        gfp.num_channels = this.channels;
        gfp.in_samplerate = sampleRate;
        gfp.brate = bitrateKbps;
        gfp.quality = 7; // schneller auf alten 32-bit-Handys
        gfp.bWriteVbrTag = false;
        gfp.findReplayGain = false;

        id3.id3tag_init(gfp);
        // Titel/Interpret selbst als ID3v2 (UTF-16) schreiben: volle Länge, Umlaute richtig
        writeId3v2(out, title, artist);

        int rc = lame.lame_init_params(gfp);
        if (rc < 0) throw new IOException("LAME init fehlgeschlagen (" + rc + ")");
    }

    private static void writeId3v2(OutputStream out, String title, String artist) throws IOException {
        java.io.ByteArrayOutputStream frames = new java.io.ByteArrayOutputStream();
        textFrame(frames, "TIT2", title);
        textFrame(frames, "TPE1", artist);
        textFrame(frames, "TENC", "DeLorean Tube");
        byte[] body = frames.toByteArray();
        int size = body.length;
        out.write(new byte[]{'I', 'D', '3', 3, 0, 0,
                (byte) ((size >> 21) & 0x7f), (byte) ((size >> 14) & 0x7f),
                (byte) ((size >> 7) & 0x7f), (byte) (size & 0x7f)});
        out.write(body);
    }

    private static void textFrame(java.io.ByteArrayOutputStream f, String id, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int len = 1 + 2 + t.length;
        f.write(id.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        f.write(new byte[]{(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len, 0, 0});
        f.write(1);                       // Kodierung: UTF-16 mit BOM
        f.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
        f.write(t);
    }

    /** pcm: 16-bit little endian, interleaved; length in bytes. */
    public void encode(byte[] pcm, int offset, int length) throws IOException {
        int frames = length / (2 * channels);
        if (frames <= 0) return;
        int[] left = new int[frames];
        int[] right = new int[frames];
        int pos = offset;
        for (int i = 0; i < frames; i++) {
            int l = (short) ((pcm[pos] & 0xff) | (pcm[pos + 1] << 8));
            pos += 2;
            int r = l;
            if (channels == 2) {
                r = (short) ((pcm[pos] & 0xff) | (pcm[pos + 1] << 8));
                pos += 2;
            }
            // diese Java-Version von LAME erwartet normale 16-bit-Werte (+/- 32768), nicht hochskaliert
            left[i] = l;
            right[i] = r;
        }
        int need = (int) (1.25 * frames) + 7200;
        if (mp3buf.length < need) mp3buf = new byte[need];
        int n = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3buf, 0, mp3buf.length);
        if (n < 0) throw new IOException("LAME Fehler " + n);
        if (n > 0) out.write(mp3buf, 0, n);
    }

    public void finish() throws IOException {
        if (mp3buf.length < 7200) mp3buf = new byte[7200];
        int n = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.length);
        if (n > 0) out.write(mp3buf, 0, n);
        lame.lame_close(gfp);
        out.flush();
    }
}
