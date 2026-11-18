package com.roohu.clipforge;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/** Generates a small original beat (drums, bass, pad) so every clip can get music with zero setup and no copyright risk. */
final class MusicSynth {
    private static final int SR = 32000;

    static void generate(File out, String mood, int seconds) throws IOException {
        double bpm = "intense".equals(mood) ? 128 : "upbeat".equals(mood) ? 108 : 78;
        boolean drive = !"calm".equals(mood);
        double beat = 60.0 / bpm;
        int n = SR * Math.max(4, seconds);
        float[] mix = new float[n];
        Random rnd = new Random(42);
        int[] roots = {57, 53, 48, 55};       // A, F, C, G (minor-feel loop)
        double[] hp = new double[1];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double bt = t / beat;
            int beatIdx = (int) bt;
            double inBeat = (bt - beatIdx) * beat;     // seconds since beat start
            int bar = beatIdx / 4, bInBar = beatIdx % 4;
            int root = roots[bar % 4];
            double v = 0;

            // kick
            boolean kick = drive ? true : (bInBar == 0 || bInBar == 2);
            if (kick) {
                double f = 45 + 80 * Math.exp(-inBeat * 30);
                v += Math.sin(2 * Math.PI * f * inBeat) * Math.exp(-inBeat * 9) * 0.9;
            }
            // snare on 2 and 4
            if (drive && (bInBar == 1 || bInBar == 3)) {
                v += (rnd.nextDouble() * 2 - 1) * Math.exp(-inBeat * 16) * 0.35
                        + Math.sin(2 * Math.PI * 190 * inBeat) * Math.exp(-inBeat * 20) * 0.2;
            }
            // hats on the off-beat 8ths
            double inHalf = inBeat % (beat / 2);
            double noise = rnd.nextDouble() * 2 - 1;
            double hi = noise - hp[0];
            hp[0] = noise;
            if (drive || bInBar % 2 == 1) v += hi * Math.exp(-inHalf * 70) * (drive ? 0.12 : 0.06);

            // bass
            double bf = 440.0 * Math.pow(2, (root - 69) / 12.0);
            double benv = Math.exp(-inBeat * 3.2);
            v += (Math.sin(2 * Math.PI * bf * t) + 0.3 * Math.sin(4 * Math.PI * bf * t)) * benv * 0.32;

            // pad chord (root, minor third, fifth, one octave up)
            double pf = bf * 2, pad = 0;
            pad += Math.sin(2 * Math.PI * pf * t);
            pad += Math.sin(2 * Math.PI * pf * 1.1892 * t);
            pad += Math.sin(2 * Math.PI * pf * 1.4983 * t);
            v += pad * 0.045 * (0.75 + 0.25 * Math.sin(2 * Math.PI * 0.25 * t));

            mix[i] = (float) v;
        }
        float peak = 0.001f;
        for (float f : mix) peak = Math.max(peak, Math.abs(f));
        float g = 0.8f / peak;
        // short fade in/out
        int fade = SR / 4;
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            writeHeader(os, n);
            byte[] b = new byte[2];
            for (int i = 0; i < n; i++) {
                float s = mix[i] * g;
                if (i < fade) s *= (float) i / fade;
                if (i > n - fade) s *= (float) (n - i) / fade;
                short sv = (short) Math.max(-32768, Math.min(32767, Math.round(s * 32767)));
                b[0] = (byte) (sv & 0xFF); b[1] = (byte) ((sv >> 8) & 0xFF);
                os.write(b);
            }
        }
    }

    private static void writeHeader(BufferedOutputStream os, int samples) throws IOException {
        int dataLen = samples * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, SR); le32(h, 28, SR * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        os.write(h);
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
}
