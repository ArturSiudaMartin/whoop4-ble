package dev.whoop4.hr;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sessions on disk (app-private storage):
 *   sessions/<startMs>.csv   one "timeMs,bpm" line per sample, appended live while tracking
 *   sessions/index.csv       one "startMs,endMs,samples,avg,min,max" line per finished session
 */
final class Store {
    private Store() {}

    static final class Session {
        long start, end; int n, min, max; double avg;
    }

    static File dir(Context c) { File d = new File(c.getFilesDir(), "sessions"); d.mkdirs(); return d; }
    static File samplesFile(Context c, long start) { return new File(dir(c), start + ".csv"); }

    static void appendIndex(Context c, long start, long end, Samples s) {
        try (FileWriter w = new FileWriter(new File(dir(c), "index.csv"), true)) {
            w.write(String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d\n", start, end, s.n, s.avg(), s.min, s.max));
        } catch (IOException ignored) {}
    }

    static List<Session> sessions(Context c) {
        List<Session> out = new ArrayList<>();
        File f = new File(dir(c), "index.csv");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split(",");
                if (p.length < 6) continue;
                Session s = new Session();
                s.start = Long.parseLong(p[0]); s.end = Long.parseLong(p[1]); s.n = Integer.parseInt(p[2]);
                s.avg = Double.parseDouble(p[3]); s.min = Integer.parseInt(p[4]); s.max = Integer.parseInt(p[5]);
                out.add(s);
            }
        } catch (IOException | NumberFormatException ignored) {}
        Collections.reverse(out);   // newest first
        return out;
    }

    static Samples loadSamples(Context c, long start) {
        Samples s = new Samples();
        File f = samplesFile(c, start);
        if (!f.exists()) return s;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                int k = line.indexOf(',');
                if (k <= 0) continue;
                try { s.add(Long.parseLong(line.substring(0, k)), Integer.parseInt(line.substring(k + 1).trim())); }
                catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return s;
    }

    static void delete(Context c, long start) {
        samplesFile(c, start).delete();
        List<Session> all = sessions(c);
        Collections.reverse(all);
        File f = new File(dir(c), "index.csv");
        try (FileWriter w = new FileWriter(f, false)) {
            for (Session s : all) if (s.start != start)
                w.write(String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d\n", s.start, s.end, s.n, s.avg, s.min, s.max));
        } catch (IOException ignored) {}
    }
}
