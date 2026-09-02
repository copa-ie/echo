package com.copa.echo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.copa.echo.android.Fonts;
import com.copa.echo.android.StringFormat;
import com.copa.echo.android.TimeFormat;
import com.copa.echo.android.Views;

public class SettingsActivity extends Activity {
    static final String TAG = SettingsActivity.class.getSimpleName();
    private final MemoryOnClickListener memoryClickListener = new MemoryOnClickListener();
    private final QualityOnClickListener qualityClickListener = new QualityOnClickListener();
    private final AutoSaveToggleClickListener autoSaveToggleClickListener = new AutoSaveToggleClickListener();
    private final AutoSaveIntervalClickListener autoSaveIntervalClickListener = new AutoSaveIntervalClickListener();
    private final LowPowerToggleClickListener lowPowerToggleClickListener = new LowPowerToggleClickListener();
    private final GpsToggleClickListener gpsToggleClickListener = new GpsToggleClickListener();
    private final CameraToggleClickListener cameraToggleClickListener = new CameraToggleClickListener();
    private final TiltThresholdClickListener tiltThresholdClickListener = new TiltThresholdClickListener();
    private final ScreenshotToggleClickListener screenshotToggleClickListener = new ScreenshotToggleClickListener();
    private final UploadToggleClickListener uploadToggleClickListener = new UploadToggleClickListener();

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 5466;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 5467;
    private static final int PROJECTION_REQUEST_CODE = 5468;

    /** Selectable tilt angles, in degrees, paired with the buttons below. */
    private static final int[] TILT_DEGREES = { 30, 45, 60, 90 };
    private static final int[] TILT_BUTTONS = {
            R.id.tilt_30, R.id.tilt_45, R.id.tilt_60, R.id.tilt_90 };

    /** Selectable automatic save intervals, in minutes, paired with the buttons below. */
    private static final int[] INTERVAL_MINUTES = { 1, 5, 15, 30, 60 };
    private static final int[] INTERVAL_BUTTONS = {
            R.id.interval_1, R.id.interval_5, R.id.interval_15, R.id.interval_30, R.id.interval_60 };


    final WorkingDialog dialog = new WorkingDialog();

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, SaidItService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveUploadUrl();
        saveIntervals();
        handler.removeCallbacks(gpsNoteRefresher);
        unbindService(connection);
    }

    /** Persists whatever is typed into the server URL field, so it survives leaving the screen. */
    private void saveUploadUrl() {
        if (service == null) return;
        final EditText url = (EditText) findViewById(R.id.upload_url);
        if (url != null) service.setUploadUrl(url.getText().toString());
    }

    private final Handler handler = new Handler();

    /**
     * Whether the GPS provider is on is the one thing on this screen the user can change from
     * outside it, and it can also settle a few seconds after logging is switched on. Drawn once,
     * the note under the toggle goes stale and says the opposite of the truth.
     */
    private final Runnable gpsNoteRefresher = new Runnable() {
        @Override
        public void run() {
            syncGpsUI();
            handler.postDelayed(this, 2000);
        }
    };

    SaidItService service;
    ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            SaidItService.BackgroundRecorderBinder typedBinder = (SaidItService.BackgroundRecorderBinder) binder;
            service = typedBinder.getService();
            syncUI();
            handler.removeCallbacks(gpsNoteRefresher);
            handler.postDelayed(gpsNoteRefresher, 2000);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            service = null;
        }
    };

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

    private void syncUI() {
        final long maxMemory = Runtime.getRuntime().maxMemory();
        System.out.println("maxMemory = " + maxMemory);
        System.out.println("totalMemory = " + Runtime.getRuntime().totalMemory());

        ((Button) findViewById(R.id.memory_low)).setText(StringFormat.shortFileSize(maxMemory / 4));
        ((Button) findViewById(R.id.memory_medium)).setText(StringFormat.shortFileSize(maxMemory / 2));
//        ((Button) findViewById(R.id.memory_high)).setText(StringFormat.shortFileSize(maxMemory * 3 / 4));
        ((Button) findViewById(R.id.memory_high)).setText(StringFormat.shortFileSize((long) (maxMemory * 0.90)));


        TimeFormat.naturalLanguage(getResources(), service.getBytesToSeconds() * service.getMemorySize(), timeFormatResult);
        ((TextView)findViewById(R.id.history_limit)).setText(timeFormatResult.text);

        highlightButtons();
        syncAutoSaveUI();
        syncLowPowerUI();
        syncGpsUI();
        syncCameraUI();
        syncScreenshotUI();
        syncUploadUI();
    }

    private void syncCameraUI() {
        if (service == null) return;
        final boolean enabled = service.isCameraEnabled();
        final Button toggle = (Button) findViewById(R.id.camera_toggle);
        toggle.setText(enabled ? R.string.camera_on : R.string.camera_off);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);

        final int current = service.getTiltThresholdDegrees();
        for (int i = 0; i < TILT_BUTTONS.length; ++i) {
            final Button button = (Button) findViewById(TILT_BUTTONS[i]);
            button.setText(getString(R.string.degrees_format, TILT_DEGREES[i]));
            final boolean selected = enabled && TILT_DEGREES[i] == current;
            button.setBackgroundResource(selected ? R.drawable.green_button : R.drawable.gray_button);
            button.setEnabled(enabled);
        }

        final TextView note = (TextView) findViewById(R.id.camera_note);
        if (enabled && !service.hasCameraPermission()) {
            note.setText(R.string.camera_permission_needed);
            note.setVisibility(View.VISIBLE);
        } else {
            note.setVisibility(View.GONE);
        }

        setNumberField(R.id.camera_min_back, service.getCameraMinBackSeconds());
        setNumberField(R.id.camera_min_front, service.getCameraMinFrontSeconds());
    }

    private void syncScreenshotUI() {
        if (service == null) return;
        final boolean on = service.isScreenshotEnabled();
        final Button toggle = (Button) findViewById(R.id.screenshot_toggle);
        toggle.setText(on ? R.string.screenshot_on : R.string.screenshot_off);
        toggle.setBackgroundResource(on ? R.drawable.green_button : R.drawable.gray_button);

        setNumberField(R.id.screenshot_min, service.getScreenshotMinSeconds());
    }

    /** Fills a number field, leaving it alone while its value already matches what is typed. */
    private void setNumberField(int id, int value) {
        final EditText field = (EditText) findViewById(id);
        if (!field.getText().toString().equals(Integer.toString(value))) {
            field.setText(Integer.toString(value));
        }
    }

    private int readNumberField(int id, int fallback) {
        final EditText field = (EditText) findViewById(id);
        if (field == null) return fallback;
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Persists the three minimum-interval fields, so they survive leaving the screen. */
    private void saveIntervals() {
        if (service == null) return;
        service.setCameraMinIntervals(
                readNumberField(R.id.camera_min_back, service.getCameraMinBackSeconds()),
                readNumberField(R.id.camera_min_front, service.getCameraMinFrontSeconds()));
        service.setScreenshotMinSeconds(
                readNumberField(R.id.screenshot_min, service.getScreenshotMinSeconds()));
    }

    private void syncUploadUI() {
        if (service == null) return;
        final boolean on = service.isUploadEnabled();
        final Button toggle = (Button) findViewById(R.id.upload_toggle);
        toggle.setText(on ? R.string.upload_on : R.string.upload_off);
        toggle.setBackgroundResource(on ? R.drawable.green_button : R.drawable.gray_button);

        final EditText url = (EditText) findViewById(R.id.upload_url);
        // Only overwrite what the user is typing when it is genuinely different, so the cursor
        // does not jump while the once-a-bind sync runs.
        if (!url.getText().toString().equals(service.getUploadUrl())) {
            url.setText(service.getUploadUrl());
        }
    }

    private void syncAutoSaveUI() {
        if (service == null) return;

        final boolean enabled = service.isAutoSaveEnabled();
        final Button toggle = (Button) findViewById(R.id.auto_save_toggle);
        toggle.setText(enabled ? R.string.auto_save_enabled : R.string.auto_save_disabled);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);

        final int current = service.getAutoSaveIntervalMinutes();
        for (int i = 0; i < INTERVAL_BUTTONS.length; ++i) {
            final Button button = (Button) findViewById(INTERVAL_BUTTONS[i]);
            button.setText(getResources().getQuantityString(R.plurals.interval_minutes,
                    INTERVAL_MINUTES[i], INTERVAL_MINUTES[i]));
            final boolean selected = enabled && INTERVAL_MINUTES[i] == current;
            button.setBackgroundResource(selected ? R.drawable.green_button : R.drawable.gray_button);
            button.setEnabled(enabled);
        }

        // The cached directory, never getTracesDir(): resolving it creates and deletes a probe
        // file, and this runs on the main thread.
        final java.io.File dir = service.getResolvedDir();
        ((TextView) findViewById(R.id.storage_path)).setText(
                dir == null ? getString(R.string.diagnostics_checking) : dir.getAbsolutePath());
    }

    private void syncLowPowerUI() {
        if (service == null) return;
        final boolean lowPower = service.isLowPowerEnabled();
        final Button toggle = (Button) findViewById(R.id.low_power_toggle);
        toggle.setText(lowPower ? R.string.low_power_on : R.string.low_power_off);
        toggle.setBackgroundResource(lowPower ? R.drawable.green_button : R.drawable.gray_button);

        // Low power mode drives the sample rate, so the quality buttons would only lie.
        for (int buttonId : new int[]{ R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz }) {
            final View button = findViewById(buttonId);
            button.setEnabled(!lowPower);
            button.setAlpha(lowPower ? 0.4f : 1f);
        }
    }

    private void syncGpsUI() {
        if (service == null) return;
        final boolean enabled = service.isGpsEnabled();
        final Button toggle = (Button) findViewById(R.id.gps_toggle);
        toggle.setText(enabled ? R.string.gps_on : R.string.gps_off);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);

        // Logging can be on and still produce nothing, so say which of the two is in the way.
        final TextView note = (TextView) findViewById(R.id.gps_note);
        String message = null;
        if (enabled && !service.hasLocationPermission()) {
            message = getString(R.string.gps_permission_needed);
        } else if (enabled && !service.isGpsProviderEnabled()) {
            message = getString(R.string.gps_provider_off);
        }
        if (message == null) {
            note.setVisibility(View.GONE);
        } else {
            note.setText(message);
            note.setVisibility(View.VISIBLE);
        }
    }

    void highlightButtons() {
        final long maxMemory = Runtime.getRuntime().maxMemory();

        int button = Math.round(service.getMemorySizePreference() / (float)(maxMemory / 4)); // 1 low, 2 medium, 3 high
        highlightButton(R.id.memory_low, R.id.memory_medium, R.id.memory_high, button);

        int samplingRate = service.getSamplingRate();
        if(samplingRate >= 44100) button = 3;
        else if(samplingRate >= 16000) button = 2;
        else button = 1;
        highlightButton(R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz, button);
    }

    private void highlightButton(int button1, int button2, int button3, int i) {
        findViewById(button1).setBackgroundResource(1 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(button2).setBackgroundResource(2 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(button3).setBackgroundResource(3 == i ? R.drawable.green_button : R.drawable.gray_button);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_settings, null);
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if(view instanceof Button) {
                    ((Button) view).setTypeface(Fonts.bold(SettingsActivity.this));
                } else if(view instanceof TextView) {
                    ((TextView) view).setTypeface("bold".equals(view.getTag())
                            ? Fonts.bold(SettingsActivity.this) : Fonts.regular(SettingsActivity.this));
                }
            }
        });

        root.findViewById(R.id.settings_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        final LinearLayout settingsLayout = (LinearLayout) root.findViewById(R.id.settings_layout);

        final FrameLayout myFrameLayout = new FrameLayout(this) {
            @Override
            protected boolean fitSystemWindows(Rect insets) {
                settingsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return true;
            }
        };

        myFrameLayout.addView(root);

        root.findViewById(R.id.memory_low).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_medium).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_high).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.auto_save_toggle).setOnClickListener(autoSaveToggleClickListener);
        root.findViewById(R.id.low_power_toggle).setOnClickListener(lowPowerToggleClickListener);
        root.findViewById(R.id.gps_toggle).setOnClickListener(gpsToggleClickListener);
        root.findViewById(R.id.camera_toggle).setOnClickListener(cameraToggleClickListener);
        root.findViewById(R.id.screenshot_toggle).setOnClickListener(screenshotToggleClickListener);
        root.findViewById(R.id.upload_toggle).setOnClickListener(uploadToggleClickListener);
        for (int buttonId : INTERVAL_BUTTONS) {
            root.findViewById(buttonId).setOnClickListener(autoSaveIntervalClickListener);
        }
        for (int buttonId : TILT_BUTTONS) {
            root.findViewById(buttonId).setOnClickListener(tiltThresholdClickListener);
        }

        initSampleRateButton(root, R.id.quality_8kHz, 8000, 11025);
        initSampleRateButton(root, R.id.quality_16kHz, 16000, 22050);
        initSampleRateButton(root, R.id.quality_48kHz, 48000, 44100);

        dialog.setDescriptionStringId(R.string.work_preparing_memory);

        setContentView(myFrameLayout);
    }

    private void initSampleRateButton(ViewGroup layout, int buttonId, int primarySampleRate, int secondarySampleRate) {
        Button button = (Button) layout.findViewById(buttonId);
        button.setOnClickListener(qualityClickListener);
        if(testSampleRateValid(primarySampleRate)) {
            button.setText(String.format(java.util.Locale.getDefault(), "%d kHz", primarySampleRate / 1000));
            button.setTag(primarySampleRate);
        } else if(testSampleRateValid(secondarySampleRate)) {
            button.setText(String.format(java.util.Locale.getDefault(), "%d kHz", secondarySampleRate / 1000));
            button.setTag(secondarySampleRate);
        } else {
            button.setVisibility(View.GONE);
        }
    }

    private boolean testSampleRateValid(int sampleRate) {
        final int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return bufferSize > 0;
    }

    private class MemoryOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            final long memory = getMultiplier(v) * Runtime.getRuntime().maxMemory() / 4;
            dialog.show(getFragmentManager(), "Preparing memory");

            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    service.setMemorySize(memory);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(SaidItService.State state) {
                            syncUI();
                            if (dialog.isVisible()) dialog.dismiss();
                        }
                    });
                }
            });
        }

        private int getMultiplier(View button) {
            final int id = button.getId();
            if (id == R.id.memory_high) return 3;
            if (id == R.id.memory_medium) return 2;
            if (id == R.id.memory_low) return 1;
            return 0;
        }
    }

    private class QualityOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            final int sampleRate = getSampleRate(v);
            dialog.show(getFragmentManager(), "Preparing memory");

            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    service.setSampleRate(sampleRate);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(SaidItService.State state) {
                            syncUI();
                            if (dialog.isVisible()) dialog.dismiss();
                        }
                    });
                }
            });
        }

        private int getSampleRate(View button) {
            Object tag = button.getTag();
            if(tag instanceof Integer) {
                return ((Integer) tag).intValue();
            }
            return 8000;
        }
    }

    private class AutoSaveToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            service.setAutoSaveEnabled(!service.isAutoSaveEnabled());
            syncAutoSaveUI();
        }
    }

    private class LowPowerToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            service.setLowPowerEnabled(!service.isLowPowerEnabled());
            syncLowPowerUI();
            highlightButtons();
        }
    }

    /**
     * Location is the one permission the app asks for out here rather than at startup: it is only
     * needed by a feature that is off by default, so nobody who does not want it is ever asked.
     */
    private class GpsToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            if (service.isGpsEnabled()) {
                service.setGpsEnabled(false);
                syncGpsUI();
                return;
            }
            if (!service.hasLocationPermission()) {
                requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
                        LOCATION_PERMISSION_REQUEST_CODE);
                return;
            }
            service.setGpsEnabled(true);
            syncGpsUI();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (service == null) return;
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            // Turning logging on without the permission would only write empty tracks, so the
            // answer decides whether it goes on at all. The note below the button explains a refusal.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                service.setGpsEnabled(true);
            }
            syncGpsUI();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                service.setCameraEnabled(true);
            }
            syncCameraUI();
        }
    }

    private class AutoSaveIntervalClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            for (int i = 0; i < INTERVAL_BUTTONS.length; ++i) {
                if (INTERVAL_BUTTONS[i] == v.getId()) {
                    service.setAutoSaveIntervalMinutes(INTERVAL_MINUTES[i]);
                    break;
                }
            }
            syncAutoSaveUI();
        }
    }

    /**
     * Camera capture, like GPS, asks for its permission out here rather than at startup: it is off
     * by default, so nobody who does not want it is ever prompted.
     */
    private class CameraToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            saveIntervals();
            if (service.isCameraEnabled()) {
                service.setCameraEnabled(false);
                syncCameraUI();
                return;
            }
            if (!service.hasCameraPermission()) {
                requestPermissions(new String[]{ Manifest.permission.CAMERA },
                        CAMERA_PERMISSION_REQUEST_CODE);
                return;
            }
            service.setCameraEnabled(true);
            syncCameraUI();
        }
    }

    private class TiltThresholdClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            for (int i = 0; i < TILT_BUTTONS.length; ++i) {
                if (TILT_BUTTONS[i] == v.getId()) {
                    service.setTiltThresholdDegrees(TILT_DEGREES[i]);
                    break;
                }
            }
            syncCameraUI();
        }
    }

    /**
     * Screenshots need a MediaProjection consent, which is an activity result. Turning them on
     * launches the system dialog; the answer comes back to {@link #onActivityResult}.
     */
    private class ScreenshotToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            saveIntervals();
            if (service.isScreenshotEnabled()) {
                service.stopScreenCapture();
                syncScreenshotUI();
                return;
            }
            if (!service.isListeningForScreenshots()) {
                android.widget.Toast.makeText(SettingsActivity.this,
                        R.string.screenshot_needs_listening, android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            final MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager != null) {
                startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION_REQUEST_CODE);
            }
        }
    }

    private class UploadToggleClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            saveUploadUrl();
            service.setUploadEnabled(!service.isUploadEnabled());
            syncUploadUI();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PROJECTION_REQUEST_CODE || service == null) return;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (!service.startScreenCapture(resultCode, data)) {
                android.widget.Toast.makeText(this, R.string.screenshot_denied,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } else {
            android.widget.Toast.makeText(this, R.string.screenshot_denied,
                    android.widget.Toast.LENGTH_LONG).show();
        }
        syncScreenshotUI();
    }

}
