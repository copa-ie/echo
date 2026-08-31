package com.copa.echo;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import simplesound.pcm.WavAudioFormat;
import simplesound.pcm.WavFileWriter;
import static com.copa.echo.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final String NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";

    /** Pass as memorySeconds to save the whole audio memory. */
    public static final float ALL_MEMORY = -1f;

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;

    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread
    Handler mainHandler; // used to post messages back to the UI thread

    /** uptimeMillis of the next automatic save, or -1 when none is scheduled. */
    private volatile long nextAutoSaveUptime = -1;
    /** Wall clock time of the last save, or 0 if nothing was saved yet. */
    private volatile long lastSaveMillis = 0;

    volatile int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    @Override
    public void onCreate() {

        Log.d(TAG, "Reading native sample rate");

        mainHandler = new Handler(Looper.getMainLooper());

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate (AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        createNotificationChannel();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }

        rescheduleAutoSave();
    }

    @Override
    public void onDestroy() {
        audioHandler.removeCallbacks(autoSaveTicker);
        nextAutoSaveUptime = -1;
        stopRecording(null, "");
        innerStopListening();
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();

        innerStartListening();
        rescheduleAutoSave();
        updateNotification();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
        rescheduleAutoSave();
    }

    private void innerStartListening() {
        switch(state) {
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");

                audioRecord = new AudioRecord(
                       MediaRecorder.AudioSource.MIC,
                       SAMPLE_RATE,
                       AudioFormat.CHANNEL_IN_MONO,
                       AudioFormat.ENCODING_PCM_16BIT,
                       AudioMemory.CHUNK_SIZE);

                if(audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    return;
                }

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioMemory.allocate(memorySize);

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });


    }

    private void innerStopListening() {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null)
                    audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });

    }

    /** Directory every recording is written to. */
    public static File recordingsDir(Context context) {
        if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            // Use public storage directory for Android 11+ (min SDK 30)
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
        }
        return new File(context.getFilesDir(), "Echo");
    }

    public File getRecordingsDir() {
        return recordingsDir(this);
    }

    /** Recordings are named after the wall clock time of their first sample, see {@link Recordings}. */
    private static String timestampName(long millis) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(millis));
    }

    private static File uniqueFile(File dir, String baseName) {
        File file = new File(dir, baseName + ".wav");
        for(int i = 2; file.exists(); ++i) {
            file = new File(dir, baseName + "_" + i + ".wav");
        }
        return file;
    }

    /**
     * Saves the whole audio memory into a new file, named after the time of its first sample.
     * Memory is emptied afterwards, so consecutive saves never overlap.
     */
    public void saveEverything(final WavFileReceiver wavFileReceiver) {
        saveMemory(ALL_MEMORY, null, wavFileReceiver);
    }

    /**
     * Saves the most recent memorySeconds of audio memory into a new file, emptying the memory.
     * Pass {@link #ALL_MEMORY} to save everything. A null baseName means "name it after the time of its first sample".
     */
    public void saveMemory(final float memorySeconds, final String baseName, final WavFileReceiver wavFileReceiver) {
        if(state == STATE_READY) {
            showToast(getString(R.string.nothing_to_save));
            return;
        }
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                writeMemoryToFile(memorySeconds, baseName, wavFileReceiver);
            }
        });
        rescheduleAutoSave();
    }

    /**
     * Writes audio memory into a fresh wav file and empties the memory.
     * Only allowed on the audio thread, right after {@link #flushAudioRecord()}.
     */
    private void writeMemoryToFile(float memorySeconds, String baseName, final WavFileReceiver wavFileReceiver) {
        assert audioHandler.getLooper() == Looper.myLooper();

        final int bytesAvailable = audioMemory.countFilled();
        if(bytesAvailable <= 0) {
            Log.d(TAG, "Nothing to save, audio memory is empty");
            showToast(getString(R.string.nothing_to_save));
            return;
        }

        int skipBytes = 0;
        if(memorySeconds >= 0) {
            final long keepBytes = (long)(memorySeconds * FILL_RATE);
            skipBytes = (int) Math.max(0, bytesAvailable - keepBytes);
        }

        final File storageDir = getRecordingsDir();
        if(!storageDir.exists() && !storageDir.mkdirs()) {
            showToast(getString(R.string.cant_create_file) + storageDir.getAbsolutePath());
            return;
        }

        final int useBytes = bytesAvailable - skipBytes;
        final long startMillis = System.currentTimeMillis() - 1000L * useBytes / FILL_RATE;
        final File file = uniqueFile(storageDir,
                (baseName == null || baseName.isEmpty()) ? timestampName(startMillis) : baseName);
        final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();

        int written = 0;
        boolean complete = false;
        try {
            final WavFileWriter writer = new WavFileWriter(format, file);
            try {
                audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                    @Override
                    public int consume(byte[] array, int offset, int count) throws IOException {
                        writer.write(array, offset, count);
                        return 0;
                    }
                });
                complete = true;
            } finally {
                written = writer.getTotalSampleBytesWritten();
                writer.close(); // rewrites the riff header with the final size
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while writing audio history into " + file.getAbsolutePath(), e);
            showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
        }

        if(written <= 0) {
            // Nothing landed on disk, so keep the memory around for the next attempt.
            file.delete();
            return;
        }
        audioMemory.reset();

        Log.d(TAG, "Saved " + written + " B into " + file.getAbsolutePath() + (complete ? "" : " (truncated)"));
        lastSaveMillis = System.currentTimeMillis();
        final float runtime = written * getBytesToSeconds();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateNotification();
                if(wavFileReceiver != null) wavFileReceiver.fileReady(file, runtime);
            }
        });
    }

    private void showToast(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void startRecording(final float prependedMemorySeconds) {
        switch(state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;
        updateNotification();

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int)(prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);
                final long startMillis = System.currentTimeMillis() - 1000L * (bytesAvailable - skipBytes) / FILL_RATE;

                final File storageDir = getRecordingsDir();
                if(!storageDir.exists() && !storageDir.mkdirs()) {
                    showToast(getString(R.string.cant_create_file) + storageDir.getAbsolutePath());
                    return;
                }

                wavFile = uniqueFile(storageDir, timestampName(startMillis));
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + wavFile.getAbsolutePath();
                    showToast(errorMessage);
                    Log.e(TAG, errorMessage, e);
                    return;
                }

                if(skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                wavFileWriter.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        final String errorMessage = getString(R.string.error_during_writing_history_into) + wavFile.getAbsolutePath();
                        showToast(errorMessage);
                        Log.e(TAG, errorMessage, e);
                        stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
                    }
                }
            }
        });

    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
        rescheduleAutoSave();
    }

    public interface WavFileReceiver {
        public void fileReady(File file, float runtime);
    }

    public void stopRecording(final WavFileReceiver wavFileReceiver, String newFileName) {
        switch(state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                final File file = wavFile;
                int written = 0;
                try {
                    written = wavFileWriter.getTotalSampleBytesWritten();
                    wavFileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "CLOSING ERROR", e);
                }
                wavFileWriter = null;
                lastSaveMillis = System.currentTimeMillis();
                final float runtime = written * getBytesToSeconds();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateNotification();
                        if(wavFileReceiver != null) wavFileReceiver.fileReady(file, runtime);
                    }
                });
            }
        });

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }
        rescheduleAutoSave();
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AUDIO RECORD ERROR - BAD VALUE");
                return 0;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AUDIO RECORD ERROR - INVALID OPERATION");
                return 0;
            }
            if (read == AudioRecord.ERROR) {
                Log.e(TAG, "AUDIO RECORD ERROR - UNKNOWN ERROR");
                return 0;
            }
            if (wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            if (read == count) {
                // We've filled the buffer, so let's read again.
                audioHandler.post(audioReader);
            } else {
                // It seems we've read everything!
                //
                // Estimate how long do we have until audioRecord fills up completely and post the callback 1 second before that
                // (but not earlier than half the buffer and no later than 90% of the buffer).
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float)SAMPLE_RATE;
                float delaySeconds = bufferSizeInSeconds - 1;
                delaySeconds = Math.max(delaySeconds, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader, (long)(delaySeconds * 1000));
            }
            return read;
        }
    };
    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                final String errorMessage = getString(R.string.error_during_recording_into) + wavFile.getName();
                showToast(errorMessage);
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
            }
        }
    };

    /** Everything the UI needs to draw the current state of the recorder. */
    public static class State {
        public boolean listeningEnabled;
        public boolean recording;
        /** Seconds of audio currently held in memory. */
        public float memorized;
        /** Seconds of audio memory can hold. */
        public float totalMemory;
        /** Seconds already written into the file of an ongoing file recording. */
        public float recorded;
        public boolean autoSaveEnabled;
        public int autoSaveIntervalMinutes;
        /** Milliseconds until the next automatic save, or -1 when none is scheduled. */
        public long nextAutoSaveInMillis;
        /** Wall clock time of the last save, or 0 when nothing was saved yet. */
        public long lastSaveMillis;
    }

    public interface StateCallback {
        public void state(State state);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final State result = new State();
        result.listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        result.recording = (state == STATE_RECORDING);
        result.autoSaveEnabled = isAutoSaveEnabled();
        result.autoSaveIntervalMinutes = getAutoSaveIntervalMinutes();
        final long nextUptime = nextAutoSaveUptime;
        result.nextAutoSaveInMillis = (nextUptime < 0) ? -1 : Math.max(0, nextUptime - SystemClock.uptimeMillis());
        result.lastSaveMillis = lastSaveMillis;

        // Note that we may not run this for quite a while, if audioReader decides to read a lot of audio!
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);

                int recorded = 0;
                if(wavFileWriter != null) {
                    recorded += wavFileWriter.getTotalSampleBytesWritten();
                    recorded += stats.estimation;
                }
                final float bytesToSeconds = getBytesToSeconds();
                result.memorized = (stats.overwriting ? stats.total : stats.filled + stats.estimation) * bytesToSeconds;
                result.totalMemory = stats.total * bytesToSeconds;
                result.recorded = recorded * bytesToSeconds;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(result);
                    }
                });
            }
        });
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    public boolean isAutoSaveEnabled() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getBoolean(AUTO_SAVE_ENABLED_KEY, true);
    }

    public void setAutoSaveEnabled(boolean enabled) {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).edit().putBoolean(AUTO_SAVE_ENABLED_KEY, enabled).commit();
        rescheduleAutoSave();
        updateNotification();
    }

    public int getAutoSaveIntervalMinutes() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getInt(AUTO_SAVE_INTERVAL_KEY, AUTO_SAVE_INTERVAL_DEFAULT);
    }

    public void setAutoSaveIntervalMinutes(int minutes) {
        final int clamped = Math.max(1, minutes);
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).edit().putInt(AUTO_SAVE_INTERVAL_KEY, clamped).commit();
        rescheduleAutoSave();
        updateNotification();
    }

    /**
     * Drops the whole audio memory into a file and queues the next automatic save.
     * Runs on the audio thread, which keeps ticking as long as audio is being captured,
     * so it does not depend on AlarmManager (whose while-idle alarms are throttled to
     * roughly one every 9 minutes and could not honour shorter intervals).
     */
    private final Runnable autoSaveTicker = new Runnable() {
        @Override
        public void run() {
            if(state == STATE_LISTENING) {
                flushAudioRecord();
                writeMemoryToFile(ALL_MEMORY, null, null);
            } else {
                // While recording straight into a file the memory is already being persisted,
                // saving it again would duplicate the audio.
                Log.d(TAG, "Auto-save skipped, state = " + state);
            }
            rescheduleAutoSave();
        }
    };

    private void rescheduleAutoSave() {
        audioHandler.removeCallbacks(autoSaveTicker);
        if(state == STATE_READY || !isAutoSaveEnabled()) {
            nextAutoSaveUptime = -1;
            Log.d(TAG, "Auto-save not scheduled");
            return;
        }
        final long delayMillis = getAutoSaveIntervalMinutes() * 60000L;
        nextAutoSaveUptime = SystemClock.uptimeMillis() + delayMillis;
        audioHandler.postDelayed(autoSaveTicker, delayMillis);
        Log.d(TAG, "Auto-save scheduled in " + (delayMillis / 1000) + " s");
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_STICKY;
    }

    // Workaround for bug where recent app removal caused service to stop
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT| PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if(notificationManager != null) notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        final int title;
        if(state == STATE_RECORDING) title = R.string.notification_recording_to_file;
        else if(state == STATE_LISTENING) title = R.string.notification_listening;
        else title = R.string.notification_idle;

        String detail;
        if(state == STATE_READY) {
            detail = getString(R.string.notification_idle_detail);
        } else if(isAutoSaveEnabled()) {
            detail = getResources().getQuantityString(R.plurals.notification_auto_save_on,
                    getAutoSaveIntervalMinutes(), getAutoSaveIntervalMinutes());
        } else {
            detail = getString(R.string.notification_auto_save_off);
        }

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(title))
                .setContentText(detail)
                .setSmallIcon(state == STATE_READY ? R.drawable.ic_stat_notify_recorded : R.drawable.ic_stat_notify_recording)
                .setTicker(getString(title))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    /** Refreshes the ongoing notification so it always shows the real state. */
    private void updateNotification() {
        if(state == STATE_READY) return; // the foreground notification is gone already
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if(notificationManager != null) {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification());
        }
    }

}
