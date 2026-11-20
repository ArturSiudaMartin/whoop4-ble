package dev.whoop4.hr;

/** Growable list of (time ms, bpm) samples with running stats. */
final class Samples {
    long[] t = new long[1024];
    int[] v = new int[1024];
    int n;
    private long sum;
    int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;

    void add(long time, int bpm) {
        if (n == t.length) {
            t = java.util.Arrays.copyOf(t, n * 2);
            v = java.util.Arrays.copyOf(v, n * 2);
        }
        t[n] = time; v[n] = bpm; n++;
        sum += bpm;
        if (bpm < min) min = bpm;
        if (bpm > max) max = bpm;
    }

    void clear() { n = 0; sum = 0; min = Integer.MAX_VALUE; max = Integer.MIN_VALUE; }

    double avg() { return n == 0 ? 0 : (double) sum / n; }
}
