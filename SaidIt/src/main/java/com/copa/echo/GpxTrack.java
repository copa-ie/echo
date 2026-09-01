package com.copa.echo;

import android.location.Location;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The in-memory buffer of location fixes, written out as a GPX track next to the audio it was
 * recorded with.
 *
 * Fixes arrive on the main thread and are written from the audio thread, so every method is
 * synchronized. Like audio memory, the buffer is emptied by a successful write, so consecutive
 * tracks continue one another without overlapping.
 */
public class GpxTrack {

    /**
     * Fixes further apart than this start a new {@code <trkseg>}. Joining them would draw a
     * straight line across a gap where there simply was no signal, which is not where we were.
     */
    private static final long SEGMENT_GAP_MILLIS = 60000;

    /**
     * Upper bound on buffered fixes, so a save that never happens cannot grow without end.
     * At one fix per second this is over thirteen hours; the oldest go first.
     */
    private static final int MAX_POINTS = 50000;

    /** One location fix, kept as primitives: there may be tens of thousands of them. */
    private static final class Point {
        double latitude;
        double longitude;
        /** Metres above the ellipsoid, or NaN when the fix carried no altitude. */
        double elevation;
        long timeMillis;
    }

    private final List<Point> points = new ArrayList<Point>();
    /** Fixes dropped because the buffer was full, reported once the track is written. */
    private int dropped = 0;

    public void add(Location location) {
        add(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
    }

    synchronized void add(double latitude, double longitude, double elevation, long timeMillis) {
        final Point point = new Point();
        point.latitude = latitude;
        point.longitude = longitude;
        point.elevation = elevation;
        point.timeMillis = timeMillis;
        points.add(point);
        while (points.size() > MAX_POINTS) {
            points.remove(0);
            ++dropped;
        }
    }

    public synchronized int size() {
        return points.size();
    }

    public synchronized int droppedCount() {
        return dropped;
    }

    /** Wall clock time of the oldest buffered fix, or 0 when there is none. */
    public synchronized long firstFixMillis() {
        return points.isEmpty() ? 0 : points.get(0).timeMillis;
    }

    public synchronized void reset() {
        points.clear();
        dropped = 0;
    }

    /**
     * Writes every buffered fix into a GPX 1.1 file and empties the buffer, returning how many
     * were written. The buffer is only emptied once the file is closed, so a failed write leaves
     * the fixes to be retried rather than losing them.
     */
    public synchronized int writeTo(File file, String trackName) throws IOException {
        if (points.isEmpty()) return 0;

        final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        time.setTimeZone(TimeZone.getTimeZone("UTC"));
        final Date date = new Date();

        final Writer out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
        try {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write("<gpx version=\"1.1\" creator=\"Echo\"\n");
            out.write("     xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            out.write("  <metadata>\n");
            date.setTime(points.get(0).timeMillis);
            out.write("    <time>" + time.format(date) + "</time>\n");
            out.write("  </metadata>\n");
            out.write("  <trk>\n");
            out.write("    <name>" + escape(trackName) + "</name>\n");

            long previousMillis = 0;
            boolean segmentOpen = false;
            for (Point point : points) {
                if (segmentOpen && point.timeMillis - previousMillis > SEGMENT_GAP_MILLIS) {
                    out.write("    </trkseg>\n");
                    segmentOpen = false;
                }
                if (!segmentOpen) {
                    out.write("    <trkseg>\n");
                    segmentOpen = true;
                }
                previousMillis = point.timeMillis;

                out.write(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n",
                        point.latitude, point.longitude));
                if (!Double.isNaN(point.elevation)) {
                    out.write(String.format(Locale.US, "        <ele>%.2f</ele>\n", point.elevation));
                }
                date.setTime(point.timeMillis);
                out.write("        <time>" + time.format(date) + "</time>\n");
                out.write("      </trkpt>\n");
            }

            if (segmentOpen) out.write("    </trkseg>\n");
            out.write("  </trk>\n");
            out.write("</gpx>\n");
        } finally {
            out.close();
        }

        final int written = points.size();
        points.clear();
        dropped = 0;
        return written;
    }

    /** Track names come from a timestamp, but escaping them costs nothing and cannot rot. */
    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
