package com.roohu.clipforge;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.Crop;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one clip with the phone's hardware video encoder: trim, smart 9:16 crop, colour enhance,
 * zoom punch / beat pulse, fades, animated captions, 3D name credit, progress bar and music.
 * Output is a constant 30 fps H.264/AAC MP4 with a proper index, so it plays smoothly in the Gallery.
 */
@UnstableApi
final class ClipExporter {
    interface Listener {
        void progress(int pct);
        void done(File file);
        void error(String message);
    }

    private Transformer transformer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean finished;

    /** Must be called on the main thread. musicUri may be null. */
    void start(final Context ctx, Uri src, JSONObject spec, Uri musicUri, final Listener l) {
        Fonts.init(ctx);
        try {
            final double s = spec.getDouble("s"), e = spec.getDouble("e"), dur = e - s;
            final int sw = spec.optInt("w", 0), sh = spec.optInt("h", 0);
            final int W, H;
            if (sw >= 64 && sh >= 64 && sw <= 4320 && sh <= 4320) { W = sw & ~1; H = sh & ~1; }   // any aspect ratio, validated again here
            else { W = spec.optInt("res", 720) >= 1080 ? 1080 : 720; H = Math.round(W * 16f / 9f / 2f) * 2; }
            final int ow = Math.min(W, H);   // overlay text is sized from the short side so it fits every aspect ratio
            int fpsReq = spec.optInt("fps", 30);
            final int fps = fpsReq == 24 || fpsReq == 60 ? fpsReq : 30;
            final String mood = spec.optString("mood", "upbeat");
            final boolean react = "react".equals(spec.optString("look", "normal")) && H > W;   // the framed look is designed for portrait
            final double[] pk = react ? readPunches(spec.optJSONArray("punches")) : new double[0];

            int vw = 0, vh = 0, rot = 0;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, src);
                vw = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                vh = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String ro = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                rot = ro == null ? 0 : Integer.parseInt(ro);
            } finally { r.release(); }
            if (rot == 90 || rot == 270) { int t = vw; vw = vh; vh = t; }

            final boolean fill = react || !"fit".equals(spec.optString("mode", "fill"));
            final double cropX = spec.optDouble("x", 0);
            List<Effect> fx = new ArrayList<>();

            // 1) smart 9:16 reframe (follows the motion centre found during analysis)
            if (fill) {
                float aspect = (float) vw / (float) vh;
                if (aspect > 9f / 16f) {
                    float f = (9f / 16f) / aspect;
                    float center = (float) Math.max(-1, Math.min(1, cropX)) * (1f - f);
                    fx.add(new Crop(center - f, center + f, -1f, 1f));
                }
            }
            fx.add(Presentation.createForWidthAndHeight(W, H,
                    fill ? Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP : Presentation.LAYOUT_SCALE_TO_FIT));

            // 2) colour enhance
            if (spec.optBoolean("enh", true)) {
                fx.add(new Contrast(0.12f));
                fx.add(new Brightness(0.02f));
                fx.add(new HslAdjustment.Builder().adjustSaturation(18f).build());
            }

            // 3) zoom punch-in and beat pulse
            final boolean punch = "punch".equals(spec.optString("trans", "fade"));
            final boolean beat = spec.optBoolean("beat", false);
            final double bpm = "intense".equals(mood) ? 128 : "upbeat".equals(mood) ? 108 : 78;
            if (punch || beat || pk.length > 0) {
                fx.add((MatrixTransformation) pts -> {
                    double t = pts / 1e6, z = 1, dx = 0, dy = 0;
                    if (punch && t < 0.6) z += 0.14 * (1 - t / 0.6);
                    if (beat) z += 0.035 * Math.pow(Math.max(0, Math.cos(2 * Math.PI * t * bpm / 60.0)), 8);
                    double d = sinceLast(pk, t);
                    if (d >= 0) {
                        double k = Math.exp(-d / 0.16);
                        z += 0.17 * k;
                        dx = 0.012 * k * Math.sin(d * 90);
                        dy = 0.010 * k * Math.cos(d * 77);
                    }
                    Matrix m = new Matrix();
                    m.setScale((float) z, (float) z);
                    m.postTranslate((float) dx, (float) dy);
                    return m;
                });
            }
            // light flash on every punch
            if (pk.length > 0) {
                fx.add((RgbMatrix) (pts, hdr) -> {
                    double d = sinceLast(pk, pts / 1e6);
                    float k = (float) (d >= 0 ? 1 + 0.40 * Math.exp(-d / 0.09) : 1);
                    return new float[]{k, 0, 0, 0, 0, k, 0, 0, 0, 0, k, 0, 0, 0, 0, 1};
                });
            }

            // 4) overlays
            List<TextureOverlay> ov = new ArrayList<>();
            if (spec.optBoolean("enh", true)) {
                ov.add(BitmapOverlay.createStaticBitmapOverlay(Painter.vignette(W, H)));
            }
            if (react) {
                final float tf = 0.15f, bf = 0.20f;
                ov.add(BitmapOverlay.createStaticBitmapOverlay(Painter.reactFrame(W, H, tf, bf)));
                ov.add(new PulseOverlay(Painter.reactGlow(W, H, tf, bf), bpm, pk));
                String hook = spec.optString("hook", spec.optString("title", ""));
                if (!hook.trim().isEmpty()) ov.add(new HookOverlay(ow, H * tf * 0.92f, tf, hook));
            }
            JSONArray cj = spec.optJSONArray("cues");
            if (spec.optBoolean("capOn", true) && cj != null && cj.length() > 0) {
                ov.add(new CaptionOverlay(ow, s, parseCues(cj), spec.optString("capStyle", "pop"),
                        Painter.face(spec.optString("font", "")), spec.optBoolean("rtl", false)));
            }
            ov.add(new NameOverlay(ow, (float) spec.optDouble("wmSize", 100), (float) spec.optDouble("wmOp", 90) / 100f,
                    spec.optString("wmPos", "br"), react ? 1f - 0.20f : 0f));
            if (spec.optBoolean("prog", true)) ov.add(new ProgressOverlay(W, H, dur));
            if (spec.optBoolean("fxq", true)) fx.add(new FxEffect(pk));
            fx.add(new OverlayEffect(ImmutableList.copyOf(ov)));

            // 5) fade in / fade out
            if ("fade".equals(spec.optString("trans", "fade"))) {
                final double D = dur;
                fx.add((RgbMatrix) (pts, hdr) -> {
                    double t = pts / 1e6;
                    double a = Math.max(0, Math.max(1 - t / 0.4, 1 - (D - t) / 0.4));
                    float k = (float) (1 - Math.min(1, a));
                    return new float[]{k, 0, 0, 0, 0, k, 0, 0, 0, 0, k, 0, 0, 0, 0, 1};
                });
            }

            MediaItem mi = new MediaItem.Builder().setUri(src)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((long) (s * 1000)).setEndPositionMs((long) (e * 1000)).build())
                    .build();
            EditedMediaItem video = new EditedMediaItem.Builder(mi)
                    .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(fx)))
                    .setFrameRate(fps)
                    .build();

            List<EditedMediaItemSequence> seqs = new ArrayList<>();
            seqs.add(new EditedMediaItemSequence(ImmutableList.of(video)));
            if (musicUri != null) {
                MediaItem mm = new MediaItem.Builder().setUri(musicUri)
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0).setEndPositionMs((long) (dur * 1000)).build())
                        .build();
                float vol = (float) spec.optDouble("vol", 35) / 100f * 0.8f;
                EditedMediaItem music = new EditedMediaItem.Builder(mm)
                        .setRemoveVideo(true)
                        .setEffects(new Effects(ImmutableList.<AudioProcessor>of(new GainProcessor(vol)), ImmutableList.<Effect>of()))
                        .build();
                seqs.add(new EditedMediaItemSequence(ImmutableList.of(music)));
            }
            Composition composition = new Composition.Builder(seqs).build();

            int bitrate = Math.max(500_000, Math.min(50_000_000, spec.optInt("bps", spec.optInt("br", 6) * 1_000_000)));
            transformer = new Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) { finish(null, l); }
                        @Override public void onError(Composition c, ExportResult r, ExportException ex) {
                            finish(ex.getMessage() == null ? ex.toString() : ex.getMessage(), l);
                        }
                    })
                    .build();
            final File out = new File(ctx.getCacheDir(), "clip_" + System.currentTimeMillis() + ".mp4");
            outFile = out;
            transformer.start(composition, out.getAbsolutePath());
            poll(l);
        } catch (Throwable t) {
            finish(t.getMessage() == null ? t.toString() : t.getMessage(), l);
        }
    }

    private File outFile;

    /** Seconds since the latest punch at or before t, or -1 when none happened in the last second. */
    static double sinceLast(double[] pk, double t) {
        double best = -1;
        for (double p : pk) { if (p <= t) best = p; else break; }
        return best < 0 || t - best > 1.0 ? -1 : t - best;
    }

    private static double[] readPunches(JSONArray a) {
        if (a == null) return new double[0];
        double[] r = new double[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.optDouble(i, 0);
        java.util.Arrays.sort(r);
        return r;
    }

    private void finish(String err, Listener l) {
        if (finished) return;
        finished = true;
        h.removeCallbacksAndMessages(null);
        if (err == null && outFile != null && outFile.length() > 0) l.done(outFile);
        else l.error(err == null ? "The encoder produced an empty file." : err);
    }

    private void poll(final Listener l) {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (finished || transformer == null) return;
                ProgressHolder ph = new ProgressHolder();
                if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) l.progress(ph.progress);
                h.postDelayed(this, 350);
            }
        }, 350);
    }

    void cancel() {
        finished = true;
        h.removeCallbacksAndMessages(null);
        if (transformer != null) { try { transformer.cancel(); } catch (Exception ignored) { } }
        if (outFile != null) outFile.delete();
    }

    private static List<Painter.Cue> parseCues(JSONArray a) throws Exception {
        List<Painter.Cue> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Painter.Cue c = new Painter.Cue(o.getDouble("a"), o.getDouble("b"));
            JSONArray w = o.getJSONArray("w");
            for (int k = 0; k < w.length(); k++) {
                JSONArray x = w.getJSONArray(k);
                c.words.add(new Painter.W(x.getString(0), x.getDouble(1), x.getDouble(2)));
            }
            if (!c.words.isEmpty()) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ overlays

    /** Wide divider bloom whose brightness kicks on every punch (or on the beat when there are none). */
    private static final class PulseOverlay extends BitmapOverlay {
        private final Bitmap bmp; private final double bpm; private final double[] pk;
        PulseOverlay(Bitmap bmp, double bpm, double[] pk) { this.bmp = bmp; this.bpm = bpm; this.pk = pk; }

        @Override public Bitmap getBitmap(long presentationTimeUs) { return bmp; }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6, d = sinceLast(pk, t);
            double env = d >= 0 ? Math.exp(-d / 0.25) : Math.pow(Math.max(0, Math.cos(2 * Math.PI * t * bpm / 60.0)), 8);
            return new OverlaySettings.Builder().setAlphaScale((float) (0.35 + 0.65 * env)).build();
        }
    }

    /** The 3D hook title in the top bar: pops in with an overshoot, holds, then fades. */
    private static final class HookOverlay extends BitmapOverlay {
        private final Bitmap bmp, empty = Painter.transparent(); private final float cy;
        HookOverlay(int W, float maxH, float topFrac, String text) {
            bmp = Painter.hookTitle(W, maxH, text); cy = 1f - topFrac;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) { return presentationTimeUs / 1e6 < 4.2 ? bmp : empty; }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            float sc = 1f;
            if (t < 0.45) {
                double p = t / 0.45, c1 = 1.70158, c3 = c1 + 1, q = p - 1;
                sc = (float) (0.55 + 0.45 * (1 + c3 * q * q * q + c1 * q * q));
            }
            float a = (float) (t < 0.2 ? t / 0.2 : t > 3.6 ? Math.max(0, 1 - (t - 3.6) / 0.5) : 1);
            return new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, cy).setOverlayFrameAnchor(0f, 0f)
                    .setScale(sc, sc).setAlphaScale(a).build();
        }
    }

    /** 3D word-by-word captions: each page springs in, the spoken word pops, tilts and glows. */
    private static final class CaptionOverlay extends BitmapOverlay {
        private final int W; private final double clipStart; private final List<Painter.Cue> cues;
        private final String style; private final Typeface tf; private final boolean rtl;
        private final LruCache<Long, Bitmap> cache = new LruCache<>(24);
        private final Bitmap empty = Painter.transparent();

        CaptionOverlay(int W, double clipStart, List<Painter.Cue> cues, String style, Typeface tf, boolean rtl) {
            this.W = W; this.clipStart = clipStart; this.cues = cues; this.style = style; this.tf = tf; this.rtl = rtl;
        }

        private int cueAt(double t) {
            for (int i = 0; i < cues.size(); i++) {
                Painter.Cue c = cues.get(i);
                if (t >= c.a - 0.02 && t <= c.b + 0.25) return i;
            }
            return -1;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6 + clipStart;
            int ci = cueAt(t);
            if (ci < 0) return empty;
            Painter.Cue c = cues.get(ci);
            int act = 0;
            for (int k = 0; k < c.words.size(); k++) if (t >= c.words.get(k).a) act = k;
            double pop = (t - c.words.get(act).a) / 0.16;
            int step = (int) Math.max(0, Math.min(5, Math.round(pop * 5)));
            long key = ((long) ci << 16) | ((long) act << 4) | step;
            Bitmap b = cache.get(key);
            if (b == null) {
                List<String> ws = new ArrayList<>();
                for (Painter.W w : c.words) ws.add(w.w);
                b = Painter.caption(W, ws, act, step / 5f, style, tf, rtl);
                cache.put(key, b);
            }
            return b;
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6 + clipStart;
            int ci = cueAt(t);
            float sc = 1f, a = 1f;
            if (ci >= 0) {
                double age = t - cues.get(ci).a;
                if (age < 0.2) {
                    double p = Math.max(0, age) / 0.2, c1 = 1.70158, c3 = c1 + 1, q = p - 1;
                    sc = (float) (0.62 + 0.38 * (1 + c3 * q * q * q + c1 * q * q));
                    a = (float) Math.min(1, age / 0.08);
                }
            }
            return new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, -0.26f).setOverlayFrameAnchor(0f, 1f)
                    .setScale(sc, sc).setAlphaScale(a).build();
        }
    }

    /** The editor credit ROOHU X KHAN in 3D chrome: floats gently, with a shine sweeping across every few seconds. */
    private static final class NameOverlay extends BitmapOverlay {
        private static final int STEPS = 14;
        private final Bitmap[] frames = new Bitmap[STEPS + 1];
        private final float sizePct, opacity; private final int W;
        private final float ax, ay, barTop;   // barTop > 0 => centred inside the reaction look's bottom bar

        NameOverlay(int W, float sizePct, float opacity, String pos, float barTop) {
            this.W = W; this.barTop = barTop; this.opacity = Math.max(0.5f, opacity);
            this.sizePct = barTop > 0 ? sizePct * 1.15f : sizePct * 0.8f;
            ax = pos.length() > 1 && pos.charAt(1) == 'l' ? -1f : 1f;
            ay = pos.length() > 0 && pos.charAt(0) == 't' ? 1f : -1f;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            double ph = (t % 3.4) / 1.0;           // shine sweep during the first second of each 3.4 s cycle
            int idx = ph < 1 ? (int) (ph * STEPS) : STEPS;   // STEPS = no shine
            if (frames[idx] == null)
                frames[idx] = Painter.nameCredit(W, sizePct, idx == STEPS ? -1f : idx / (float) STEPS);
            return frames[idx];
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            float in = (float) Math.min(1, t / 0.6);
            float bob = (float) (Math.sin(t * 2.2) * 0.008);
            OverlaySettings.Builder b = new OverlaySettings.Builder().setAlphaScale(opacity * in);
            if (barTop > 0) {
                float s = 0.7f + 0.3f * in;     // grows in
                b.setBackgroundFrameAnchor(0f, -barTop + bob).setOverlayFrameAnchor(0f, 0f).setScale(s, s);
            } else {
                b.setBackgroundFrameAnchor(ax * 0.95f, ay * (ay > 0 ? 0.92f : 0.90f) + bob).setOverlayFrameAnchor(ax, ay);
            }
            return b.build();
        }
    }

    private static final class ProgressOverlay extends BitmapOverlay {
        private final int W, H; private final double dur;
        private final Bitmap[] cache = new Bitmap[51];
        private final OverlaySettings settings = new OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, 0.985f).setOverlayFrameAnchor(0f, 1f).build();

        ProgressOverlay(int W, int H, double dur) { this.W = W; this.H = H; this.dur = dur; }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            int step = (int) Math.max(0, Math.min(50, Math.round(presentationTimeUs / 1e6 / dur * 50)));
            if (cache[step] == null) cache[step] = Painter.progress(W, H, step / 50f);
            return cache[step];
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) { return settings; }
    }

    /** Simple PCM16 volume control for the music bed. */
    private static final class GainProcessor extends BaseAudioProcessor {
        private final float gain;
        GainProcessor(float gain) { this.gain = gain; }

        @Override protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
            return in;
        }

        @Override public void queueInput(ByteBuffer input) {
            int n = input.remaining();
            ByteBuffer out = replaceOutputBuffer(n);
            out.order(ByteOrder.nativeOrder());
            ByteOrder keep = input.order();
            input.order(ByteOrder.nativeOrder());
            while (input.remaining() >= 2) {
                int v = Math.round(input.getShort() * gain);
                out.putShort((short) Math.max(-32768, Math.min(32767, v)));
            }
            input.order(keep);
            out.flip();
        }
    }

    // ------------------------------------------------------------------ saving to the Gallery

    /** Copies the rendered file into Movies/RoohuClipForge so it shows up in the Gallery. Returns its content/file URI. */
    static Uri publish(Context ctx, File file, String displayName) throws IOException {
        String name = displayName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) name = "clip";
        if (!name.toLowerCase().endsWith(".mp4")) name += ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RoohuClipForge");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create the video in the Gallery.");
            try (InputStream in = new FileInputStream(file); OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open the Gallery file.");
                copy(in, os);
            } catch (IOException ex) {
                ctx.getContentResolver().delete(uri, null, null);
                throw ex;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            file.delete();
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "RoohuClipForge");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create the Movies/RoohuClipForge folder (storage permission needed).");
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(file); OutputStream os = new FileOutputStream(dst)) { copy(in, os); }
            file.delete();
            MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            return Uri.fromFile(dst);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
