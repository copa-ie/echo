package com.copa.echo;

import android.app.Activity;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;


import java.io.File;
import java.util.Date;

import com.copa.echo.android.Fonts;
import com.copa.echo.android.TimeFormat;
import com.copa.echo.android.Views;

public class SaidItFragment extends Fragment {

    private static final String TAG = SaidItFragment.class.getSimpleName();
    /**
     * How often the screen refreshes itself while it is visible. Every refresh wakes the audio
     * thread for a read, and nothing on screen is finer grained than a second, so a second it is.
     */
    private static final long REFRESH_MILLIS = 1000;
    /** Low power mode polls even less often. */
    private static final long REFRESH_MILLIS_LOW_POWER = 3000;
    /** An error older than this is history, not a live problem. */
    private static final long ERROR_FRESH_MILLIS = 120000;

    private View statusBanner;
    private View statusDot;
    private TextView statusText;
    private TextView statusHint;

    private TextView memorySize;
    private TextView memoryLimit;
    private ProgressBar memoryBar;

    private Button saveEverythingButton;
    private TextView autoSaveStatus;
    private TextView autoSaveNext;
    private TextView autoSaveCount;
    private TextView lastSave;
    private TextView warningBox;
    private Button lowPowerButton;

    private Animation dotPulse;
    /** Whether the pulsing dot is currently animating, so we only start/stop it on real changes. */
    private boolean dotPulsing = false;
    /** Last drawn banner state: null until the first refresh. */
    private Boolean shownListening = null;

    SaidItService echo;

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        final Activity activity = getActivity();
        assert activity != null;
        activity.bindService(new Intent(activity, SaidItService.class), echoConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
        final Activity activity = getActivity();
        assert activity != null;
        final View view = getView();
        if (view != null) view.removeCallbacks(updater);
        activity.unbindService(echoConnection);
        echo = null;
    }

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (getView() == null) return;
            if (echo == null) return;
            echo.getState(serviceStateCallback);
        }
    };

    private final ServiceConnection echoConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, "onServiceConnected");
            SaidItService.BackgroundRecorderBinder typedBinder = (SaidItService.BackgroundRecorderBinder) binder;
            if (echo != null && echo == typedBinder.getService()) {
                Log.d(TAG, "update loop already running, skipping");
                return;
            }
            echo = typedBinder.getService();
            final View view = getView();
            if (view != null) view.post(updater);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected");
            echo = null;
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        final View rootView = inflater.inflate(R.layout.fragment_background_recorder, container, false);
        if (rootView == null) return null;

        final Activity activity = getActivity();
        final Typeface robotoCondensedBold = Fonts.bold(activity);
        final Typeface robotoCondensedRegular = Fonts.regular(activity);
        final float density = activity.getResources().getDisplayMetrics().density;

        Views.search((ViewGroup) rootView, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof Button) {
                    final Button button = (Button) view;
                    button.setTypeface(robotoCondensedBold);
                    final int shadowColor = button.getShadowColor();
                    button.setShadowLayer(0.01f, 0, density * 2, shadowColor);
                } else if (view instanceof TextView) {
                    ((TextView) view).setTypeface(robotoCondensedRegular);
                }
            }
        });

        statusBanner = rootView.findViewById(R.id.status_banner);
        statusDot = rootView.findViewById(R.id.status_dot);
        statusText = (TextView) rootView.findViewById(R.id.status_text);
        statusHint = (TextView) rootView.findViewById(R.id.status_hint);
        statusText.setTypeface(robotoCondensedBold);

        memorySize = (TextView) rootView.findViewById(R.id.memory_size);
        memoryLimit = (TextView) rootView.findViewById(R.id.memory_limit);
        memoryBar = (ProgressBar) rootView.findViewById(R.id.memory_bar);
        memorySize.setTypeface(robotoCondensedBold);

        saveEverythingButton = (Button) rootView.findViewById(R.id.save_everything);
        autoSaveStatus = (TextView) rootView.findViewById(R.id.auto_save_status);
        autoSaveNext = (TextView) rootView.findViewById(R.id.auto_save_next);
        autoSaveCount = (TextView) rootView.findViewById(R.id.auto_save_count);
        lastSave = (TextView) rootView.findViewById(R.id.last_save);
        warningBox = (TextView) rootView.findViewById(R.id.warning_box);
        lowPowerButton = (Button) rootView.findViewById(R.id.low_power_button);

        dotPulse = AnimationUtils.loadAnimation(activity, R.anim.dot_pulse);

        // The banner sits under the status bar, so it has to make room for it itself.
        final int statusBarHeight = Views.statusBarHeight(activity);
        statusBanner.setPadding(statusBanner.getPaddingLeft(), statusBanner.getPaddingTop() + statusBarHeight,
                statusBanner.getPaddingRight(), statusBanner.getPaddingBottom());

        statusBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });

        saveEverythingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo == null) return;
                echo.saveEverything(new PromptFileReceiver(getActivity()));
            }
        });

        lowPowerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo == null) return;
                echo.setLowPowerEnabled(!echo.isLowPowerEnabled());
            }
        });

        rootView.findViewById(R.id.diagnostics_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(activity, DiagnosticsActivity.class));
            }
        });

        rootView.findViewById(R.id.recordings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(activity, RecordingsActivity.class));
            }
        });

        rootView.findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(activity, SettingsActivity.class));
            }
        });

        return rootView;
    }

    private void toggleListening() {
        if (echo == null) return;
        echo.getState(new SaidItService.StateCallback() {
            @Override
            public void state(SaidItService.State state) {
                if (echo == null) return;
                if (state.listeningEnabled) {
                    echo.disableListening();
                } else {
                    final WorkingDialog dialog = new WorkingDialog();
                    dialog.setDescriptionStringId(R.string.work_preparing_memory);
                    dialog.show(getFragmentManager(), "Preparing memory");
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (echo == null) return;
                            echo.enableListening();
                            echo.getState(new SaidItService.StateCallback() {
                                @Override
                                public void state(SaidItService.State state) {
                                    if (dialog.isVisible()) dialog.dismiss();
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    private final SaidItService.StateCallback serviceStateCallback = new SaidItService.StateCallback() {
        @Override
        public void state(SaidItService.State state) {
            final Activity activity = getActivity();
            final View view = getView();
            if (activity == null || view == null) return;
            final Resources resources = activity.getResources();

            drawBanner(resources, state);
            drawWarning(resources, state);
            drawMemory(resources, state);
            drawAutoSave(resources, activity, state);
            drawLowPower(state);

            view.postDelayed(updater, state.lowPower ? REFRESH_MILLIS_LOW_POWER : REFRESH_MILLIS);
        }
    };

    private void drawBanner(Resources resources, SaidItService.State state) {
        final boolean listening = state.listeningEnabled;
        if (shownListening != null && shownListening == listening) return;
        shownListening = listening;

        if (listening) {
            statusBanner.setBackgroundColor(resources.getColor(R.color.dark_green));
            statusText.setText(R.string.status_listening);
            statusHint.setText(R.string.status_hint_stop);
        } else {
            statusBanner.setBackgroundColor(resources.getColor(R.color.gray_6));
            statusText.setText(R.string.status_stopped);
            statusHint.setText(R.string.status_hint_start);
        }

        if (listening != dotPulsing) {
            dotPulsing = listening;
            if (listening) {
                statusDot.startAnimation(dotPulse);
            } else {
                statusDot.clearAnimation();
            }
        }

        // Nothing is being captured while stopped, so there is nothing to save either.
        saveEverythingButton.setEnabled(listening);
        saveEverythingButton.setAlpha(listening ? 1f : 0.4f);
    }

    /**
     * The one thing the old build could not tell you: whether saving is actually working.
     * Anything that stops audio from being kept shows up here in words.
     */
    private void drawWarning(Resources resources, SaidItService.State state) {
        String message = null;
        if (state.listeningEnabled) {
            if (!state.capturing) {
                message = resources.getString(R.string.warning_not_capturing);
            } else if (state.lastError != null
                    && System.currentTimeMillis() - state.lastErrorMillis < ERROR_FRESH_MILLIS) {
                message = resources.getString(R.string.warning_error, state.lastError);
            } else if (state.intervalExceedsMemory) {
                message = resources.getString(R.string.warning_interval_too_long,
                        state.autoSaveIntervalMinutes, TimeFormat.shortTimer(state.totalMemory));
            }
        }

        if (message == null) {
            if (warningBox.getVisibility() != View.GONE) warningBox.setVisibility(View.GONE);
            return;
        }
        if (!message.equals(warningBox.getText().toString())) warningBox.setText(message);
        if (warningBox.getVisibility() != View.VISIBLE) warningBox.setVisibility(View.VISIBLE);
    }

    private void drawLowPower(SaidItService.State state) {
        lowPowerButton.setText(state.lowPower ? R.string.low_power_on : R.string.low_power_off);
        lowPowerButton.setBackgroundResource(state.lowPower ? R.drawable.green_button : R.drawable.gray_button);
    }

    private void drawMemory(Resources resources, SaidItService.State state) {
        TimeFormat.naturalLanguage(resources, state.memorized, timeFormatResult);
        if (!timeFormatResult.text.equals(memorySize.getText().toString())) {
            memorySize.setText(timeFormatResult.text);
        }

        TimeFormat.naturalLanguage(resources, state.totalMemory, timeFormatResult);
        final String limit = resources.getString(R.string.memory_limit, timeFormatResult.text);
        if (!limit.equals(memoryLimit.getText().toString())) {
            memoryLimit.setText(limit);
        }

        final int progress = (state.totalMemory > 0)
                ? (int) (1000 * Math.min(1f, state.memorized / state.totalMemory)) : 0;
        memoryBar.setProgress(progress);
    }

    private void drawAutoSave(Resources resources, Context context, SaidItService.State state) {
        if (state.autoSaveEnabled) {
            autoSaveStatus.setText(resources.getQuantityString(R.plurals.auto_save_enabled_status,
                    state.autoSaveIntervalMinutes, state.autoSaveIntervalMinutes));
            if (state.nextAutoSaveInMillis >= 0) {
                autoSaveNext.setText(resources.getString(R.string.auto_save_next,
                        TimeFormat.shortTimer(state.nextAutoSaveInMillis / 1000f)));
            } else {
                autoSaveNext.setText(R.string.auto_save_paused);
            }
        } else {
            autoSaveStatus.setText(R.string.auto_save_disabled_status);
            autoSaveNext.setText("");
        }

        if (state.autoSaveCount > 0) {
            autoSaveCount.setText(resources.getQuantityString(R.plurals.auto_save_count,
                    state.autoSaveCount, state.autoSaveCount));
        } else if (state.autoSaveEnabled) {
            autoSaveCount.setText(R.string.auto_save_count_none);
        } else {
            autoSaveCount.setText("");
        }

        if (state.lastSaveMillis > 0) {
            final String time = DateFormat.getTimeFormat(context).format(new Date(state.lastSaveMillis));
            lastSave.setText(state.lastSaveName == null
                    ? resources.getString(R.string.last_save, time)
                    : resources.getString(R.string.last_save_named, time, state.lastSaveName));
        } else {
            lastSave.setText("");
        }
    }

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

    static class PromptFileReceiver implements SaidItService.WavFileReceiver {

        private final Activity activity;

        public PromptFileReceiver(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void fileReady(final File file, float runtime) {
            if (activity == null || activity.isFinishing()) return;
            new RecordingDoneDialog()
                    .setFile(file)
                    .setRuntime(runtime)
                    .show(activity.getFragmentManager(), "Recording Done");
        }
    }
}
