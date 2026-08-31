package com.copa.echo;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads back the recordings sitting on disk so the app can show what has actually been kept,
 * and which stretches of time they cover.
 *
 * Every file the service writes is named after the wall clock time of its *first* sample
 * (yyyyMMdd_HHmmss, plus a _2, _3... suffix when that second already had a file), so the
 * covered range of a file is [name, name + length of its audio].
 */
public class Recordings {

    private static final String TAG = Recordings.class.getSimpleName();
    private static final int WAV_HEADER_SIZE = 44;

    private static final Pattern NAME_PATTERN = Pattern.compile("^(\\d{8}_\\d{6})(?:_\\d+)?$");

    /** Consecutive files closer than this are reported as a single uninterrupted range. */
    public static final long DEFAULT_MAX_GAP_MILLIS = 5000;

    public static class Entry {
        public File file;
        public long startMillis;
        public long endMillis;
        public float durationSeconds;
        public long sizeBytes;
        public int sampleRate;
        /** True when the start time comes from the file name, false when guessed from its mtime. */
        public boolean exactStart;
    }

    /** An uninterrupted stretch of time covered by one or more consecutive files. */
    public static class Range {
        public long startMillis;
        public long endMillis;
        public int fileCount;
        public long sizeBytes;

        public long durationMillis() {
            return endMillis - startMillis;
        }
    }

    /** Recordings found on disk, oldest first. */
    public static List<Entry> scan(File dir) {
        final List<Entry> entries = new ArrayList<Entry>();
        final File[] files = dir.listFiles();
        if (files == null) return entries;

        for (File file : files) {
            if (!file.isFile()) continue;
            if (!file.getName().toLowerCase(Locale.US).endsWith(".wav")) continue;
            final Entry entry = readEntry(file);
            if (entry != null) entries.add(entry);
        }

        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(a.startMillis, b.startMillis);
            }
        });
        return entries;
    }

    private static Entry readEntry(File file) {
        final Entry entry = new Entry();
        entry.file = file;
        entry.sizeBytes = file.length();

        int byteRate = 0;
        long dataBytes = 0;
        try {
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                final byte[] header = new byte[WAV_HEADER_SIZE];
                raf.readFully(header);
                entry.sampleRate = intLE(header, 24);
                byteRate = intLE(header, 28);
                dataBytes = intLE(header, 40) & 0xffffffffL;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't read the wav header of " + file.getName() + ": " + e.getMessage());
            return null;
        }

        if (byteRate <= 0) return null; // not a wav file we wrote

        final long audioBytes = Math.max(0, entry.sizeBytes - WAV_HEADER_SIZE);
        // A file whose writer never got to close keeps a zero length in its header; trust the disk instead.
        if (dataBytes <= 0 || dataBytes > audioBytes) dataBytes = audioBytes;
        entry.durationSeconds = dataBytes / (float) byteRate;

        final Long namedStart = parseStartFromName(file.getName());
        if (namedStart != null) {
            entry.startMillis = namedStart;
            entry.exactStart = true;
        } else {
            entry.startMillis = file.lastModified() - (long) (entry.durationSeconds * 1000);
            entry.exactStart = false;
        }
        entry.endMillis = entry.startMillis + (long) (entry.durationSeconds * 1000);
        return entry;
    }

    static Long parseStartFromName(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        final String base = (dot < 0) ? fileName : fileName.substring(0, dot);
        final Matcher matcher = NAME_PATTERN.matcher(base);
        if (!matcher.matches()) return null;
        try {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(matcher.group(1)).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** Collapses the entries into the stretches of time they cover, oldest first. */
    public static List<Range> ranges(List<Entry> sortedEntries, long maxGapMillis) {
        final List<Range> ranges = new ArrayList<Range>();
        for (Entry entry : sortedEntries) {
            final Range last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
            if (last != null && entry.startMillis - last.endMillis <= maxGapMillis) {
                last.endMillis = Math.max(last.endMillis, entry.endMillis);
                last.fileCount++;
                last.sizeBytes += entry.sizeBytes;
            } else {
                final Range range = new Range();
                range.startMillis = entry.startMillis;
                range.endMillis = entry.endMillis;
                range.fileCount = 1;
                range.sizeBytes = entry.sizeBytes;
                ranges.add(range);
            }
        }
        return ranges;
    }

    public static long totalSizeBytes(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) total += entry.sizeBytes;
        return total;
    }

    public static float totalDurationSeconds(List<Entry> entries) {
        float total = 0;
        for (Entry entry : entries) total += entry.durationSeconds;
        return total;
    }

    private static int intLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }
}
