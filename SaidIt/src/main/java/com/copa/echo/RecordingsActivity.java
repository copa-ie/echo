package com.copa.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.copa.echo.android.StringFormat;
import com.copa.echo.android.Views;

/**
 * Shows what is actually on disk, one calendar day at a time. Months of continuous recording
 * add up to hundreds of files; scrolling through all of them flat is not navigation, so a day
 * picker at the top jumps straight to the day you actually want.
 */
public class RecordingsActivity extends Activity {

    private static final String TAG = RecordingsActivity.class.getSimpleName();

    /** Every recording on disk, oldest first, regardless of which day is on screen. */
    private final List<Recordings.Entry> allEntries = new ArrayList<Recordings.Entry>();
    /** Midnight (local time) of every day that has at least one recording, oldest first. */
    private final List<Long> availableDays = new ArrayList<Long>();
    /** Index into availableDays of the day on screen, or -1 when there is nothing to show. */
    private int currentDayIndex = -1;

    /** Recordings of the day on screen, newest first: what the adapter actually draws. */
    private final List<Recordings.Entry> entries = new ArrayList<Recordings.Entry>();
    private final RecordingsAdapter adapter = new RecordingsAdapter();
    private final Handler handler = new Handler();

    private Typeface bold;
    private Typeface regular;

    private TextView summary;
    private Button dayPrev;
    private Button dayNext;
    private TextView dayLabel;
    private TextView daySummary;
    private TextView rangesTitle;
    private LinearLayout rangesContainer;
    private TextView empty;
    private ListView list;

    private SimpleDateFormat timeFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final AssetManager assets = getAssets();
        bold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        regular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");
        timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_recordings, null);
        applyTypefaces(root);

        // The screen draws behind the status bar, so leave room for it.
        final int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusBarId > 0) {
            root.setPadding(root.getPaddingLeft(),
                    root.getPaddingTop() + getResources().getDimensionPixelSize(statusBarId),
                    root.getPaddingRight(), root.getPaddingBottom());
        }

        list = (ListView) root.findViewById(R.id.recordings_list);

        // Part of the scrolling list itself, see the comment in activity_recordings.xml.
        // Added before setAdapter: some ListView implementations require that order.
        final View header = getLayoutInflater().inflate(R.layout.recordings_header, list, false);
        applyTypefaces((ViewGroup) header);
        list.addHeaderView(header, null, false);

        summary = (TextView) header.findViewById(R.id.recordings_summary);
        dayPrev = (Button) header.findViewById(R.id.day_prev);
        dayNext = (Button) header.findViewById(R.id.day_next);
        dayLabel = (TextView) header.findViewById(R.id.day_label);
        daySummary = (TextView) header.findViewById(R.id.day_summary);
        rangesTitle = (TextView) header.findViewById(R.id.ranges_title);
        rangesContainer = (LinearLayout) header.findViewById(R.id.ranges_container);
        empty = (TextView) header.findViewById(R.id.recordings_empty);

        dayPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDayIndex > 0) {
                    --currentDayIndex;
                    showDay();
                }
            }
        });

        dayNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDayIndex >= 0 && currentDayIndex < availableDays.size() - 1) {
                    ++currentDayIndex;
                    showDay();
                }
            }
        });

        dayLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDatePicker();
            }
        });

        list.setAdapter(adapter);

        // position counts the header added above, so the real entries start after it.
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < entries.size()) play(entries.get(index).file);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < entries.size()) confirmDelete(entries.get(index).file);
                return true;
            }
        });

        root.findViewById(R.id.recordings_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void applyTypefaces(ViewGroup root) {
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof TextView) {
                    final Object tag = view.getTag();
                    ((TextView) view).setTypeface("bold".equals(tag) ? bold : regular);
                }
            }
        });
    }

    /** Reads the directories off the UI thread, since they can hold hundreds of files. */
    private void reload() {
        // Recordings may sit in a directory the app used before storage access changed, so look
        // in every candidate rather than only the one being written to now.
        final List<File> dirs = Storage.candidates(this);
        final File dir = Storage.resolve(this);
        // Keep showing the same day across a reload (e.g. after deleting a file), falling back
        // to the closest one if it no longer has anything in it.
        final Long keepDay = (currentDayIndex >= 0 && currentDayIndex < availableDays.size())
                ? availableDays.get(currentDayIndex) : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Recordings.Entry> scanned = Recordings.scanAll(dirs);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        show(dir, scanned, keepDay);
                    }
                });
            }
        }, "recordings-scan").start();
    }

    private void show(File dir, List<Recordings.Entry> scanned, Long keepDay) {
        allEntries.clear();
        allEntries.addAll(scanned);

        final TreeSet<Long> days = new TreeSet<Long>();
        for (Recordings.Entry entry : scanned) days.add(dayStart(entry.startMillis));
        availableDays.clear();
        availableDays.addAll(days);

        final int count = scanned.size();
        if (count == 0) {
            summary.setText(getString(R.string.recordings_summary_empty, dir.getAbsolutePath()));
        } else {
            summary.setText(getString(R.string.recordings_summary,
                    getResources().getQuantityString(R.plurals.recordings_file_count, count, count),
                    RecordingsActivity.longDuration((long) (Recordings.totalDurationSeconds(scanned) * 1000)),
                    StringFormat.shortFileSize(Recordings.totalSizeBytes(scanned)),
                    dir.getAbsolutePath()));
        }

        if (availableDays.isEmpty()) {
            currentDayIndex = -1;
        } else if (keepDay != null && availableDays.contains(keepDay)) {
            currentDayIndex = availableDays.indexOf(keepDay);
        } else if (keepDay != null) {
            currentDayIndex = closestDayIndex(keepDay);
        } else {
            currentDayIndex = availableDays.size() - 1; // most recent day by default
        }

        showDay();
    }

    /** Redraws everything below the overall summary for currentDayIndex. */
    private void showDay() {
        entries.clear();

        if (currentDayIndex < 0) {
            dayLabel.setText("");
            daySummary.setText("");
            dayPrev.setEnabled(false);
            dayNext.setEnabled(false);
            empty.setText(R.string.recordings_empty);
            empty.setVisibility(View.VISIBLE);
            rangesTitle.setVisibility(View.GONE);
            rangesContainer.removeAllViews();
            adapter.notifyDataSetChanged();
            return;
        }

        final long dayStart = availableDays.get(currentDayIndex);
        dayLabel.setText(dayLabelLong(dayStart));
        dayPrev.setEnabled(currentDayIndex > 0);
        dayNext.setEnabled(currentDayIndex < availableDays.size() - 1);

        final List<Recordings.Entry> dayEntries = new ArrayList<Recordings.Entry>();
        for (Recordings.Entry entry : allEntries) {
            if (dayStart(entry.startMillis) == dayStart) dayEntries.add(entry);
        }
        // Newest first, which is what you want to look at when you open this screen.
        for (int i = dayEntries.size() - 1; i >= 0; --i) entries.add(dayEntries.get(i));
        adapter.notifyDataSetChanged();

        if (dayEntries.isEmpty()) {
            daySummary.setText("");
            empty.setText(R.string.recordings_day_empty);
            empty.setVisibility(View.VISIBLE);
            rangesTitle.setVisibility(View.GONE);
            rangesContainer.removeAllViews();
            return;
        }

        empty.setVisibility(View.GONE);
        final int count = dayEntries.size();
        daySummary.setText(getString(R.string.recordings_day_summary,
                getResources().getQuantityString(R.plurals.recordings_file_count, count, count),
                longDuration((long) (Recordings.totalDurationSeconds(dayEntries) * 1000)),
                StringFormat.shortFileSize(Recordings.totalSizeBytes(dayEntries))));

        final List<Recordings.Range> ranges = Recordings.ranges(dayEntries, Recordings.DEFAULT_MAX_GAP_MILLIS);
        rangesTitle.setVisibility(View.VISIBLE);
        rangesContainer.removeAllViews();
        // Newest range first, same order as the list below.
        for (int i = ranges.size() - 1; i >= 0; --i) {
            final Recordings.Range range = ranges.get(i);
            final TextView view = new TextView(this);
            view.setTypeface(regular);
            view.setTextSize(16);
            view.setTextColor(getResources().getColor(R.color.gray_c));
            view.setText(getString(R.string.recordings_range_line,
                    dayLabel(range.startMillis),
                    timeFormat.format(new Date(range.startMillis)),
                    timeFormat.format(new Date(range.endMillis)),
                    longDuration(range.durationMillis()),
                    getResources().getQuantityString(R.plurals.recordings_file_count,
                            range.fileCount, range.fileCount)));
            rangesContainer.addView(view);
        }
    }

    private void openDatePicker() {
        if (availableDays.isEmpty() || currentDayIndex < 0) return;
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(availableDays.get(currentDayIndex));
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                final Calendar picked = Calendar.getInstance();
                picked.set(year, month, dayOfMonth, 0, 0, 0);
                picked.set(Calendar.MILLISECOND, 0);
                jumpToDay(picked.getTimeInMillis());
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Jumps to the given day, or the closest one that actually has recordings. */
    private void jumpToDay(long target) {
        final int index = closestDayIndex(target);
        if (index < 0) return;
        final boolean exact = availableDays.get(index) == target;
        currentDayIndex = index;
        showDay();
        if (!exact) {
            Toast.makeText(this, getString(R.string.recordings_jumped_to_day,
                    dayLabelLong(availableDays.get(index))), Toast.LENGTH_SHORT).show();
        }
    }

    private int closestDayIndex(long target) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < availableDays.size(); ++i) {
            final long diff = Math.abs(availableDays.get(i) - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /** Midnight, local time, of the day millis falls on: the key entries are grouped by. */
    private static long dayStart(long millis) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private String dayLabel(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
    }

    /** Full weekday and date, no abbreviations: what the day picker itself shows. */
    private String dayLabelLong(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_YEAR);
    }

    /** h:mm:ss, dropping the hours when there are none. */
    static String longDuration(long millis) {
        final long totalSeconds = Math.max(0, millis / 1000);
        final long hours = totalSeconds / 3600;
        final long minutes = (totalSeconds % 3600) / 60;
        final long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private void play(File file) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "audio/wav");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Can't open " + file.getName(), e);
            Toast.makeText(this, R.string.recordings_cant_open, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.recordings_delete_title)
                .setMessage(getString(R.string.recordings_delete_message, file.getName()))
                .setPositiveButton(R.string.recordings_delete_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!file.delete()) {
                            Toast.makeText(RecordingsActivity.this, R.string.recordings_cant_delete, Toast.LENGTH_LONG).show();
                        }
                        reload();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class RecordingsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(RecordingsActivity.this).inflate(R.layout.item_recording, parent, false);
                applyTypefaces((ViewGroup) view);
            }

            final Recordings.Entry entry = entries.get(position);
            final String start = timeFormat.format(new Date(entry.startMillis));
            final String end = timeFormat.format(new Date(entry.endMillis));
            final String approx = entry.exactStart ? "" : "~";

            ((TextView) view.findViewById(R.id.recording_range)).setText(
                    getString(R.string.recordings_item_range, approx, dayLabel(entry.startMillis), start, end));
            ((TextView) view.findViewById(R.id.recording_detail)).setText(
                    getString(R.string.recordings_item_detail,
                            longDuration((long) (entry.durationSeconds * 1000)),
                            StringFormat.shortFileSize(entry.sizeBytes),
                            entry.sampleRate / 1000,
                            entry.file.getName()));
            return view;
        }
    }
}
