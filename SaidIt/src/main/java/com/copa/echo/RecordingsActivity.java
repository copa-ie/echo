package com.copa.echo;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.copa.echo.android.StringFormat;
import com.copa.echo.android.TimeFormat;
import com.copa.echo.android.Views;

/**
 * Shows what is actually on disk: every recording, the stretch of time it covers and,
 * on top, the uninterrupted ranges those files add up to.
 */
public class RecordingsActivity extends Activity {

    private static final String TAG = RecordingsActivity.class.getSimpleName();

    private final List<Recordings.Entry> entries = new ArrayList<Recordings.Entry>();
    private final RecordingsAdapter adapter = new RecordingsAdapter();
    private final Handler handler = new Handler();

    private Typeface bold;
    private Typeface regular;

    private TextView summary;
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

        summary = (TextView) root.findViewById(R.id.recordings_summary);
        rangesTitle = (TextView) root.findViewById(R.id.ranges_title);
        rangesContainer = (LinearLayout) root.findViewById(R.id.ranges_container);
        empty = (TextView) root.findViewById(R.id.recordings_empty);
        list = (ListView) root.findViewById(R.id.recordings_list);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                play(entries.get(position).file);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(entries.get(position).file);
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Recordings.Entry> scanned = Recordings.scanAll(dirs);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        show(dir, scanned);
                    }
                });
            }
        }, "recordings-scan").start();
    }

    private void show(File dir, List<Recordings.Entry> scanned) {
        entries.clear();
        // Newest first, which is what you want to look at when you open this screen.
        for (int i = scanned.size() - 1; i >= 0; --i) entries.add(scanned.get(i));
        adapter.notifyDataSetChanged();

        final int count = scanned.size();
        if (count == 0) {
            summary.setText(getString(R.string.recordings_summary_empty, dir.getAbsolutePath()));
            empty.setVisibility(View.VISIBLE);
            rangesTitle.setVisibility(View.GONE);
            rangesContainer.removeAllViews();
            return;
        }

        empty.setVisibility(View.GONE);
        summary.setText(getString(R.string.recordings_summary,
                getResources().getQuantityString(R.plurals.recordings_file_count, count, count),
                longDuration((long) (Recordings.totalDurationSeconds(scanned) * 1000)),
                StringFormat.shortFileSize(Recordings.totalSizeBytes(scanned)),
                dir.getAbsolutePath()));

        final List<Recordings.Range> ranges = Recordings.ranges(scanned, Recordings.DEFAULT_MAX_GAP_MILLIS);
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

    private String dayLabel(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
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
