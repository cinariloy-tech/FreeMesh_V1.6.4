package com.freemesh.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import org.json.JSONObject;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Yerel ses: mikrofon (AudioRecord) -> ADPCM -> Bluetooth; Bluetooth -> ADPCM -> hoparlör (AudioTrack).
 * Gelen çağrı zili de burada (sistem zil sesi, zil seviyesinde çalar, arka planda da çalışır).
 */
public class Voice {
    static final int RATE = 8000;
    static final int FRAME = 512;          // 64 ms
    static final float GAIN = 2.0f;        // hoparlör kazancı (ses daha gür)

    private final Context ctx;
    private final FreeMeshBle ble;

    // mikrofon
    private AudioRecord rec;
    private Thread micThread;
    private volatile boolean micOn = false;
    private String micTo = null;

    // hoparlör
    private AudioTrack track;
    private final LinkedBlockingQueue<short[]> playQ = new LinkedBlockingQueue<>(200);

    // zil
    private MediaPlayer ringer;
    private Vibrator vib;

    Voice(Context c, FreeMeshBle b) {
        ctx = c;
        ble = b;
    }

    // ---------- Mikrofon ----------
    @SuppressLint("MissingPermission")
    synchronized boolean micStart(String to) {
        if (micOn) return true;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return false;
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, FRAME * 4));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; return false; }
        micTo = to;
        micOn = true;
        rec.startRecording();
        micThread = new Thread(this::micLoop, "fm-mic");
        micThread.start();
        return true;
    }

    private void micLoop() {
        short[] buf = new short[FRAME];
        Adpcm.Encoder enc = new Adpcm.Encoder();
        while (micOn) {
            int n = 0;
            while (n < FRAME && micOn) {
                int r = rec.read(buf, n, FRAME - n);
                if (r <= 0) break;
                n += r;
            }
            if (n < FRAME) break;
            byte[] frame = enc.encode(buf);
            String to = micTo;
            String b64 = Base64.encodeToString(frame, Base64.NO_WRAP);
            // Web tarafındaki ayrıştırma {"t":"tx","k":...} biçimini bekler, bu yüzden elle kuruyoruz
            ble.send("{\"t\":\"tx\",\"k\":\"au\",\"to\":\"" + to + "\",\"text\":\"" + b64 + "\"}");
        }
    }

    synchronized void micStop() {
        micOn = false;
        if (micThread != null) {
            try { micThread.join(300); } catch (InterruptedException ignored) { }
            micThread = null;
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
    }

    // ---------- Hoparlör ----------
    private synchronized void ensureTrack() {
        if (track != null) return;
        int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                Math.max(minBuf, RATE / 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.play();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    short[] s = playQ.take();
                    track.write(s, 0, s.length);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fm-spk");
        t.setDaemon(true);
        t.start();
    }

    /** Gelen satır bir ses karesiyse çalar ve true döner (web'e iletilmez). Sadece bana gelen kareler çalınır. */
    boolean playLine(String line) {
        if (!line.contains("\"k\":\"au\"")) return false;
        try {
            JSONObject o = new JSONObject(line);
            if (!"au".equals(o.optString("k"))) return false;
            if (!ble.myId().equals(o.optString("to"))) return true; // başkasına ait, sessizce yok say
            byte[] b = Base64.decode(o.getString("text"), Base64.NO_WRAP);
            if (b.length < 4) return true;
            short[] pcm = Adpcm.decode(b);
            for (int i = 0; i < pcm.length; i++) {
                int v = Math.round(pcm[i] * GAIN);
                pcm[i] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
            }
            ensureTrack();
            playQ.offer(pcm); // dolarsa en eski karelerden vazgeç
        } catch (Exception ignored) { }
        return true;
    }

    // ---------- Zil ----------
    synchronized void ringStart() {
        ringStop();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringer = new MediaPlayer();
            ringer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringer.setDataSource(ctx, uri);
            ringer.setLooping(true);
            ringer.prepare();
            ringer.start();
        } catch (Exception e) {
            ringer = null;
        }
        vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null) {
            long[] pattern = {0, 700, 500};
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else vib.vibrate(pattern, 0);
        }
    }

    synchronized void ringStop() {
        if (ringer != null) {
            try { ringer.stop(); } catch (Exception ignored) { }
            ringer.release();
            ringer = null;
        }
        if (vib != null) vib.cancel();
    }

    void close() {
        micStop();
        ringStop();
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) { } track = null; }
    }
}
