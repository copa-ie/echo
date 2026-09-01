package com.copa.echo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Date;
import java.util.List;

import com.copa.echo.android.Fonts;
import com.copa.echo.android.StringFormat;
import com.copa.echo.android.TimeFormat;
import com.copa.echo.android.Views;

/**
 * Answers one question: is Echo actually capturing and writing audio right now, and if not, why.
 * Every line here is a real check rather than a restatement of a setting.
 */
public class DiagnosticsActivity extends Activity {

    private static final String TAG = DiagnosticsActivity.class.getSimpleName();
    private static final long REFRESH_MILLIS = 1000;

    private Typeface bold;
    private Typeface regular;

    private TextView verdict;
    private LinearLayout rows;
    private LinearLayout log;

    /** How many rows of the container are in use this pass; the rest are hidden, not recreated. */
    private int rowsUsed = 0;
    /** Last event count drawn, so the log is only rebuilt when it actually changed. */
    private int drawnEvents = -1;
    private PowerManager power;

    private final Handler handler = new Handler();
    private boolean running = false;
    /** Result of the last real write probe, null while it has not finished yet. */
    private volatile Boolean storageWritable = null;

    SaidItService echo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bold = Fonts.bold(this);
        regular = Fonts.regular(this);
        power = (PowerManager) getSystemService(POWER_SERVICE);

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_diagnostics, null);
        Views.applyFonts(root, this);

        final LinearLayout layout = (LinearLayout) root.findViewById(R.id.diagnostics_layout);
        layout.setPadding(layout.getPaddingLeft(), layout.getPaddingTop() + Views.statusBarHeight(this),
                layout.getPaddingRight(), layout.getPaddingBottom());

        verdict = (TextView) root.findViewById(R.id.diagnostics_verdict);
        rows = (LinearLayout) root.findViewById(R.id.diagnostics_rows);
        log = (LinearLayout) root.findViewById(R.id.diagnostics_log);

        ((Button) root.findViewById(R.id.diagnostics_save_now)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo == null) return;
                echo.saveEverything(new SaidItService.WavFileReceiver() {
                    @Override
                    public void fileReady(File file, float runtime) {
                        Toast.makeText(DiagnosticsActivity.this,
                                getString(R.string.diagnostics_saved_toast, file.getName()),
                                Toast.LENGTH_LONG).show();
                    }
                });
                probeStorage();
            }
        });

        ((Button) root.findViewById(R.id.diagnostics_restart)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo == null) return;
                echo.clearLastError();
                echo.forgetStorageDir();
                echo.disableListening();
                storageWritable = null;
                drawnEvents = -1;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (echo != null) echo.enableListening();
                        probeStorage();
                    }
                }, 400);
            }
        });

        ((Button) root.findViewById(R.id.diagnostics_battery)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    Log.w(TAG, "No battery optimization settings screen", e);
                    Toast.makeText(DiagnosticsActivity.this, R.string.diagnostics_no_settings_screen,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        root.findViewById(R.id.diagnostics_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
        running = true;
        probeStorage();
    }

    /**
     * The write probe creates and deletes a real file, so it runs off the UI thread and only when
     * something might have changed, not on every one second refresh.
     */
    private void probeStorage() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean writable = Storage.canWrite(Storage.resolve(DiagnosticsActivity.this));
                storageWritable = writable;
            }
        }, "storage-probe").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false;
        handler.removeCallbacks(refresher);
        unbindService(connection);
        echo = null;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            echo = ((SaidItService.BackgroundRecorderBinder) binder).getService();
            handler.post(refresher);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            echo = null;
        }
    };

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!running || echo == null) return;
            echo.getState(new SaidItService.StateCallback() {
                @Override
                public void state(SaidItService.State state) {
                    if (!running) return;
                    draw(state);
                    handler.postDelayed(refresher, REFRESH_MILLIS);
                }
            });
        }
    };

    private static final int OK = 0;
    private static final int BAD = 1;
    private static final int INFO = 2;

    private void draw(SaidItService.State state) {
        rowsUsed = 0;

        final boolean healthy = state.isHealthy();
        verdict.setTypeface(bold);
        if (!state.listeningEnabled) {
            verdict.setText(R.string.diagnostics_verdict_stopped);
            verdict.setBackgroundColor(getResources().getColor(R.color.gray_6));
        } else if (healthy) {
            verdict.setText(R.string.diagnostics_verdict_ok);
            verdict.setBackgroundColor(getResources().getColor(R.color.dark_green));
        } else {
            verdict.setText(R.string.diagnostics_verdict_bad);
            verdict.setBackgroundColor(getResources().getColor(R.color.dark_red));
        }

        // --- permissions ---
        final boolean mic = granted(Manifest.permission.RECORD_AUDIO);
        addRow(getString(R.string.diagnostics_mic), value(mic), mic ? OK : BAD);
        final boolean notifications = granted(Manifest.permission.POST_NOTIFICATIONS);
        addRow(getString(R.string.diagnostics_notifications), value(notifications), notifications ? OK : INFO);
        final boolean allFiles = Environment.isExternalStorageManager();
        addRow(getString(R.string.diagnostics_all_files), value(allFiles), allFiles ? OK : INFO);

        // --- storage: the only check that proves a write will work ---
        final Boolean writable = storageWritable;
        addRow(getString(R.string.diagnostics_storage_writable),
                writable == null ? getString(R.string.diagnostics_checking) : value(writable),
                writable == null ? INFO : (writable ? OK : BAD));
        addRow(getString(R.string.diagnostics_storage_path),
                state.storagePath == null ? getString(R.string.diagnostics_checking) : state.storagePath,
                state.storagePublic ? OK : INFO);
        if (state.storagePath != null && !state.storagePublic) {
            addRow("", getString(R.string.diagnostics_storage_private_note), INFO);
        }
        if (state.storagePath != null) {
            addRow(getString(R.string.diagnostics_free_space), freeSpace(new File(state.storagePath)), INFO);
        }

        // --- capture ---
        addRow(getString(R.string.diagnostics_sample_rate),
                getString(R.string.diagnostics_khz, state.sampleRate / 1000f), INFO);
        addRow(getString(R.string.diagnostics_low_power), value(state.lowPower), INFO);
        if (state.listeningEnabled) {
            addRow(getString(R.string.diagnostics_capturing), value(state.capturing), state.capturing ? OK : BAD);
            addRow(getString(R.string.diagnostics_captured),
                    StringFormat.shortFileSize(state.bytesCaptured), state.bytesCaptured > 0 ? OK : BAD);
            addRow(getString(R.string.diagnostics_last_read),
                    state.sinceLastReadMillis < 0
                            ? getString(R.string.diagnostics_never)
                            : getString(R.string.diagnostics_seconds_ago, state.sinceLastReadMillis / 1000),
                    state.capturing ? OK : BAD);
            if (!state.capturing) {
                addRow("", getString(R.string.diagnostics_capture_stalled_note), BAD);
            }
        }
        addRow(getString(R.string.diagnostics_read_errors), String.valueOf(state.readErrorCount),
                state.readErrorCount == 0 ? OK : BAD);

        // --- memory and automatic saving ---
        addRow(getString(R.string.diagnostics_memory),
                TimeFormat.shortTimer(state.memorized) + " / " + TimeFormat.shortTimer(state.totalMemory), INFO);
        addRow(getString(R.string.diagnostics_auto_save), value(state.autoSaveEnabled),
                state.autoSaveEnabled ? OK : INFO);
        addRow(getString(R.string.diagnostics_interval),
                getString(R.string.diagnostics_minutes, state.autoSaveIntervalMinutes), INFO);
        addRow(getString(R.string.diagnostics_next_save),
                state.nextAutoSaveInMillis < 0
                        ? getString(R.string.diagnostics_not_scheduled)
                        : TimeFormat.shortTimer(state.nextAutoSaveInMillis / 1000f),
                state.nextAutoSaveInMillis >= 0 ? OK : INFO);
        addRow(getString(R.string.diagnostics_saves_done), String.valueOf(state.autoSaveCount),
                state.autoSaveCount > 0 ? OK : INFO);
        if (state.memoryFullSaveCount > 0) {
            addRow(getString(R.string.diagnostics_early_saves), String.valueOf(state.memoryFullSaveCount), INFO);
        }
        if (state.intervalExceedsMemory) {
            addRow("", getString(R.string.diagnostics_interval_too_long), BAD);
        }
        addRow(getString(R.string.diagnostics_last_save),
                state.lastSaveMillis == 0
                        ? getString(R.string.diagnostics_never)
                        : DateFormat.getTimeFormat(this).format(new Date(state.lastSaveMillis))
                                + (state.lastSaveName == null ? "" : "  " + state.lastSaveName),
                state.lastSaveMillis == 0 ? INFO : OK);

        // --- battery ---
        final boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        addRow(getString(R.string.diagnostics_battery_unrestricted), value(unrestricted), unrestricted ? OK : INFO);

        if (state.lastError != null) {
            addRow(getString(R.string.diagnostics_last_error),
                    DateFormat.getTimeFormat(this).format(new Date(state.lastErrorMillis)) + "  " + state.lastError,
                    BAD);
        }

        // Hide whatever the previous, longer pass left behind.
        for (int i = rowsUsed; i < rows.getChildCount(); ++i) {
            rows.getChildAt(i).setVisibility(View.GONE);
        }

        drawLog();
    }

    private void drawLog() {
        if (echo == null) return;
        final List<String> events = echo.getEvents();
        if (events.size() == drawnEvents) return; // nothing new to say
        drawnEvents = events.size();

        log.removeAllViews();
        if (events.isEmpty()) {
            log.addView(text(getString(R.string.diagnostics_log_empty), R.color.gray_8));
            return;
        }
        // Newest first, so the interesting part is right under the heading.
        for (int i = events.size() - 1; i >= 0; --i) {
            final String[] parts = events.get(i).split("\t", 2);
            String line = parts.length == 2 ? parts[1] : events.get(i);
            if (parts.length == 2) {
                try {
                    line = DateFormat.getTimeFormat(this).format(new Date(Long.parseLong(parts[0]))) + "  " + parts[1];
                } catch (NumberFormatException ignored) {
                    // keep the raw line
                }
            }
            log.addView(text(line, R.color.gray_c));
        }
    }

    /** Fills the next row of the container, creating it only the first time round. */
    private void addRow(String label, String value, int status) {
        final int color;
        final String glyph;
        switch (status) {
            case OK: color = R.color.green; glyph = "✓ "; break;
            case BAD: color = R.color.light_red; glyph = "✗ "; break;
            default: color = R.color.gray_c; glyph = "· "; break;
        }
        final String line = label.isEmpty() ? glyph + value : glyph + label + ": " + value;

        final TextView view;
        if (rowsUsed < rows.getChildCount()) {
            view = (TextView) rows.getChildAt(rowsUsed);
            view.setVisibility(View.VISIBLE);
        } else {
            view = text("", color);
            rows.addView(view);
        }
        ++rowsUsed;

        view.setTextColor(getResources().getColor(color));
        if (!line.equals(view.getText().toString())) view.setText(line);
    }

    private TextView text(String content, int colorId) {
        final TextView view = new TextView(this);
        view.setTypeface(regular);
        view.setTextSize(15);
        view.setPadding(0, 3, 0, 3);
        view.setTextColor(getResources().getColor(colorId));
        view.setText(content);
        return view;
    }

    private String value(boolean yes) {
        return getString(yes ? R.string.diagnostics_yes : R.string.diagnostics_no);
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String freeSpace(File dir) {
        try {
            final StatFs stat = new StatFs(dir.getAbsolutePath());
            return StringFormat.shortFileSize(stat.getAvailableBytes());
        } catch (Exception e) {
            return getString(R.string.diagnostics_unknown);
        }
    }
}
