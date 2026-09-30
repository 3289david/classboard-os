package kr.classboard.os;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Synthesised notification tones (no audio assets needed). */
public final class Chime {
    private Chime() {}

    /** @param kind "soft" two-note chime for class alerts, "urgent" repeating tone for emergencies */
    public static void play(String kind) {
        new Thread(() -> {
            try {
                int rate = 44100;
                double[][] notes = "urgent".equals(kind)
                        ? new double[][]{{988, 0.22}, {740, 0.22}, {988, 0.22}, {740, 0.22}, {988, 0.22}, {740, 0.34}}
                        : new double[][]{{1046.5, 0.28}, {784, 0.5}};
                int total = 0;
                for (double[] n : notes) total += (int) (rate * n[1]);
                short[] pcm = new short[total];
                int off = 0;
                for (double[] n : notes) {
                    int len = (int) (rate * n[1]);
                    for (int i = 0; i < len; i++) {
                        double t = (double) i / rate;
                        double env = Math.min(1.0, i / (rate * 0.01)) * Math.exp(-3.0 * t / n[1]);
                        double v = Math.sin(2 * Math.PI * n[0] * t) * 0.8 + Math.sin(4 * Math.PI * n[0] * t) * 0.15;
                        pcm[off + i] = (short) (v * env * 0.6 * Short.MAX_VALUE);
                    }
                    off += len;
                }
                AudioTrack at = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage("urgent".equals(kind) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION_EVENT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                at.write(pcm, 0, pcm.length);
                at.play();
                Thread.sleep((long) (1000.0 * total / rate) + 200);
                at.release();
            } catch (Exception ignored) {
            }
        }, "chime").start();
    }
}
