package dev.whoop4.hr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** HR-over-time chart: elapsed time on x, bpm on y, dashed average line. */
final class ChartView extends View {
    private Samples data;
    private long start, end;
    private String empty = "No session yet";
    private final float dp;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), avg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    ChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        grid.setColor(Ui.LINE); grid.setStrokeWidth(dp);
        label.setColor(Ui.MUTED); label.setTextSize(11 * dp);
        line.setColor(Ui.ACCENT); line.setStrokeWidth(2 * dp); line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        avg.setColor(Ui.MUTED); avg.setStrokeWidth(dp); avg.setStyle(Paint.Style.STROKE);
        avg.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
    }

    /** end = 0 means "live": the x axis runs up to now. */
    void set(Samples s, long start, long end, String emptyText) {
        data = s; this.start = start; this.end = end; empty = emptyText;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pl = 34 * dp, pr = 8 * dp, pt = 8 * dp, pb = 20 * dp, pw = w - pl - pr, ph = h - pt - pb;
        if (data == null || data.n == 0) {
            label.setTextAlign(Paint.Align.CENTER);
            c.drawText(empty, w / 2, h / 2, label);
            return;
        }
        long t0 = start, t1 = Math.max(end > 0 ? end : System.currentTimeMillis(), t0 + 10000);
        int lo = (int) Math.floor((data.min - 5) / 10.0) * 10, hi = (int) Math.ceil((data.max + 5) / 10.0) * 10;
        int step = hi - lo > 60 ? 20 : 10;
        label.setTextAlign(Paint.Align.RIGHT);
        for (int v = lo; v <= hi; v += step) {
            float y = pt + ph - (v - lo) / (float) (hi - lo) * ph;
            c.drawLine(pl, y, w - pr, y, grid);
            c.drawText(String.valueOf(v), pl - 6 * dp, y + 4 * dp, label);
        }
        label.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 4; i++) {
            long t = t0 + (t1 - t0) * i / 4;
            float x = Math.min(Math.max(pl + pw * i / 4f, pl + 14 * dp), w - pr - 16 * dp);
            c.drawText(MainActivity.fmtDur(t - t0), x, h - 4 * dp, label);
        }
        double a = data.avg();
        float ay = pt + ph - (float) ((a - lo) / (hi - lo)) * ph;
        c.drawLine(pl, ay, w - pr, ay, avg);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("avg " + Math.round(a), pl + 4 * dp, ay - 4 * dp, label);

        // Samples falling into the same ~2 px column are averaged, so hour-long sessions draw a clean line.
        path.reset();
        float bucketW = 2 * dp;
        int bucket = Integer.MIN_VALUE, cnt = 0;
        long sum = 0, prevT = 0;
        boolean penDown = false;
        for (int i = 0; i <= data.n; i++) {
            int b = i < data.n ? (int) ((data.t[i] - t0) / (float) (t1 - t0) * pw / bucketW) : Integer.MAX_VALUE;
            boolean gap = i < data.n && i > 0 && data.t[i] - prevT > 10000;   // don't bridge disconnects
            if ((b != bucket || gap) && cnt > 0) {
                float x = pl + (bucket + 0.5f) * bucketW, y = pt + ph - ((float) sum / cnt - lo) / (hi - lo) * ph;
                if (penDown) path.lineTo(x, y); else { path.moveTo(x, y); penDown = true; }
                sum = 0; cnt = 0;
            }
            if (i == data.n) break;
            if (gap) penDown = false;
            bucket = b; sum += data.v[i]; cnt++; prevT = data.t[i];
        }
        c.drawPath(path, line);
    }
}
