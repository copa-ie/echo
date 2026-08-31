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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
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

    /** Audio memory is dumped early once it is this full, so nothing gets overwritten unsaved. */
    private static final float MEMORY_FULL_FRACTION = 0.9f;

    /** How many of the most recent events the diagnostics screen keeps. */
    private static final int EVENT_LOG_SIZE = 40;

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;

    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread
    Handler mainHandler; // used to post messages back to the UI thread

    /** elapsedRealtime the next automatic save is due at, or -1 when automatic saving is off. */
    private volatile long autoSaveDeadline = -1;
    /** Wall clock time of the last save, or 0 if nothing was saved yet. */
    private volatile long lastSaveMillis = 0;
    private volatile String lastSaveName = null;
    private volatile int autoSaveCount = 0;
    private volatile int memoryFullSaveCount = 0;

    /** elapsedRealtime capture was (re)started at, to tell a slow start from a dead microphone. */
    private volatile long captureStartedElapsed = 0;
    /** elapsedRealtime of the last read that actually returned audio, or 0 if there was none. */
    private volatile long lastReadElapsed = 0;
    private volatile long bytesCaptured = 0;
    private volatile int readErrorCount = 0;
    /** How long a gap between reads is still normal, derived from the AudioRecord buffer. */
    private volatile long readGapToleranceMillis = 30000;

    private volatile String lastError = null;
    private volatile long lastErrorMillis = 0;

    /** Resolved lazily because it depends on what the filesystem actually allows. */
    private volatile File storageDir = null;

    /** Guards against a save being started from inside another one. Audio thread only. */
    private boolean writing = false;
    /** Set while a save asked for from the UI is queued, so the timer does not steal its audio. */
    private volatile boolean manualSavePending = false;

    private final LinkedList<String> events = new LinkedList<String>();

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
        if(preferences.getBoolean(LOW_POWER_KEY, false)) SAMPLE_RATE = LOW_POWER_SAMPLE_RATE;
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        createNotificationChannel();
        logEvent(getString(R.string.event_service_started));

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }
    }

    @Override
    public void onDestroy() {
        logEvent(getString(R.string.event_service_stopped));
        audioHandler.removeCallbacks(autoSaveWatchdog);
        autoSaveDeadline = -1;
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
        updateNotification();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
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

        // Counters describe the current capture, so they start over with it.
        captureStartedElapsed = SystemClock.elapsedRealtime();
        lastReadElapsed = 0;
        bytesCaptured = 0;
        readErrorCount = 0;

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
                    recordError(getString(R.string.error_mic_unavailable));
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    autoSaveDeadline = -1;
                    return;
                }

                // The reader has to come back before AudioRecord's own buffer overflows; remember
                // how long that is so a stalled capture can be told apart from a slow one.
                final float bufferSeconds = audioRecord.getBufferSizeInFrames() / (float) SAMPLE_RATE;
                readGapToleranceMillis = Math.max(30000, (long) (bufferSeconds * 3000));

                Log.d(TAG, "Audio: STARTING AudioRecord, buffer " + bufferSeconds + " s");
                audioMemory.allocate(memorySize);

                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });

        armAutoSave();
        logEvent(getString(R.string.event_listening_started, SAMPLE_RATE / 1000f));
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
        autoSaveDeadline = -1;
        audioHandler.removeCallbacks(autoSaveWatchdog);
        Log.d(TAG, "Queueing: STOP LISTENING");
        logEvent(getString(R.string.event_listening_stopped));

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });

    }

    // ------------------------------------------------------------------ storage

    /**
     * Directory recordings are written to. Resolved on first use by actually probing the
     * filesystem, see {@link Storage}.
     */
    public File getRecordingsDir() {
        File dir = storageDir;
        if(dir == null) {
            dir = Storage.resolve(this);
            storageDir = dir;
            if(!Storage.isPublic(this, dir)) {
                logEvent(getString(R.string.event_storage_fallback, dir.getAbsolutePath()));
            }
        }
        return dir;
    }

    /** Forgets the resolved directory, so the next save probes the filesystem again. */
    public void forgetStorageDir() {
        storageDir = null;
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

    // ------------------------------------------------------------------ saving

    /**
     * Saves the whole audio memory into a new file, named after the time of its first sample.
     * Memory is emptied afterwards, so consecutive saves never overlap.
     */
    public void saveEverything(final WavFileReceiver wavFileReceiver) {
        saveMemory(ALL_MEMORY, null, wavFileReceiver);
    }

    /**
     * Saves the most recent memorySeconds of audio memory into a new file, emptying the memory.
     * Pass {@link #ALL_MEMORY} to save everything. A null baseName means "name it after the
     * time of its first sample".
     */
    public void saveMemory(final float memorySeconds, final String baseName, final WavFileReceiver wavFileReceiver) {
        queueSave(memorySeconds, baseName, wavFileReceiver, false);
    }

    private void queueSave(final float memorySeconds, final String baseName,
                           final WavFileReceiver wavFileReceiver, final boolean silent) {
        if(state == STATE_READY) {
            if(!silent) showToast(getString(R.string.nothing_to_save));
            return;
        }
        // Whatever is in memory was captured at today's rate; remember it, because the caller may
        // be about to change the rate (that is exactly what applySampleRate does).
        final int rate = SAMPLE_RATE;
        final int fillRate = FILL_RATE;
        manualSavePending = true;
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    flushAudioRecord();
                    writeMemoryToFile(memorySeconds, baseName, wavFileReceiver, silent, rate, fillRate);
                } finally {
                    manualSavePending = false;
                }
            }
        });
        armAutoSave();
    }

    /**
     * Writes audio memory into a fresh wav file and empties the memory.
     * Only allowed on the audio thread, right after {@link #flushAudioRecord()}.
     * Returns true when a file was actually written.
     */
    private boolean writeMemoryToFile(float memorySeconds, String baseName,
                                      final WavFileReceiver wavFileReceiver, boolean silent,
                                      int sampleRate, int fillRate) {
        assert audioHandler.getLooper() == Looper.myLooper();
        if(writing) return false; // never nest saves
        writing = true;
        try {
            return doWriteMemoryToFile(memorySeconds, baseName, wavFileReceiver, silent, sampleRate, fillRate);
        } finally {
            writing = false;
        }
    }

    private boolean doWriteMemoryToFile(float memorySeconds, String baseName,
                                        final WavFileReceiver wavFileReceiver, boolean silent,
                                        int sampleRate, int fillRate) {
        final int bytesAvailable = audioMemory.countFilled();
        if(bytesAvailable <= 0) {
            Log.d(TAG, "Nothing to save, audio memory is empty");
            if(!silent) showToast(getString(R.string.nothing_to_save));
            return false;
        }

        int skipBytes = 0;
        if(memorySeconds >= 0) {
            final long keepBytes = (long)(memorySeconds * fillRate);
            skipBytes = (int) Math.max(0, bytesAvailable - keepBytes);
        }

        final File dir = getRecordingsDir();
        if(!Storage.canWrite(dir)) {
            // Whatever we resolved to is not usable any more; probe again next time.
            forgetStorageDir();
            recordError(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
            if(!silent) showToast(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
            return false;
        }

        final int useBytes = bytesAvailable - skipBytes;
        final long startMillis = System.currentTimeMillis() - 1000L * useBytes / fillRate;
        final File file = uniqueFile(dir,
                (baseName == null || baseName.isEmpty()) ? timestampName(startMillis) : baseName);
        final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(sampleRate).build();

        int written = 0;
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
            } finally {
                written = writer.getTotalSampleBytesWritten();
                writer.close(); // rewrites the riff header with the final size
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while writing audio history into " + file.getAbsolutePath(), e);
            recordError(getString(R.string.error_during_writing_history_into) + file.getName());
            if(!silent) showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
        }

        if(written <= 0) {
            // Nothing landed on disk, so keep the memory around for the next attempt.
            file.delete();
            return false;
        }
        audioMemory.reset();

        Log.d(TAG, "Saved " + written + " B into " + file.getAbsolutePath());
        lastSaveMillis = System.currentTimeMillis();
        lastSaveName = file.getName();
        final float runtime = written / (float) fillRate;
        logEvent(getString(R.string.event_saved, file.getName(),
                RecordingsActivity.longDuration((long) (runtime * 1000))));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateNotification();
                if(wavFileReceiver != null) wavFileReceiver.fileReady(file, runtime);
            }
        });
        return true;
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

                final File dir = getRecordingsDir();
                if(!Storage.canWrite(dir)) {
                    forgetStorageDir();
                    recordError(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
                    showToast(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
                    return;
                }

                wavFile = uniqueFile(dir, timestampName(startMillis));
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + wavFile.getAbsolutePath();
                    recordError(errorMessage);
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
                        recordError(errorMessage);
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
        // Low power mode owns the sample rate while it is on.
        if(isLowPowerEnabled()) return;
        applySampleRate(sampleRate);
    }

    /**
     * Persists the sample rate and, when capturing, restarts AudioRecord with it.
     * Audio memory cannot survive a format change, so whatever is in it is saved first.
     */
    private void applySampleRate(int sampleRate) {
        if(state == STATE_RECORDING) return; // never change format halfway through a file
        if(sampleRate == SAMPLE_RATE && state != STATE_READY) return;

        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        if(state == STATE_LISTENING) {
            // Queued before the restart below, and it carries the old rate with it.
            queueSave(ALL_MEMORY, null, null, true);
            innerStopListening();
            SAMPLE_RATE = sampleRate;
            FILL_RATE = 2 * sampleRate;
            innerStartListening();
        } else {
            SAMPLE_RATE = sampleRate;
            FILL_RATE = 2 * sampleRate;
        }
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
                lastSaveName = file == null ? null : file.getName();
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
        } else {
            armAutoSave();
        }
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    // ------------------------------------------------------------------ capture loop

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read < 0) {
                // Any error at all used to end the capture loop for good, because nothing
                // re-posted the reader: the service stayed "recording" and never saved again.
                // Keep the loop alive so a transient failure can recover.
                readErrorCount++;
                recordError(getString(R.string.error_audio_read, read));
                Log.e(TAG, "AUDIO RECORD READ ERROR " + read + ", retrying");
                audioHandler.postDelayed(audioReader, 1000);
                return 0;
            }
            if (read > 0) {
                lastReadElapsed = SystemClock.elapsedRealtime();
                bytesCaptured += read;
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
                final String errorMessage = getString(R.string.error_during_recording_into)
                        + (wavFile == null ? "" : wavFile.getName());
                recordError(errorMessage);
                showToast(errorMessage);
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
            }
            // Automatic saving rides along with capture: if audio is flowing this runs, and if it
            // is not there is nothing to save anyway.
            maybeAutoSave();
        }
    };

    // ------------------------------------------------------------------ automatic saving

    private long autoSaveIntervalMillis() {
        return getAutoSaveIntervalMinutes() * 60000L;
    }

    /** Sets the next deadline and makes sure the watchdog is pending. */
    private void armAutoSave() {
        if(state == STATE_READY || !isAutoSaveEnabled()) {
            autoSaveDeadline = -1;
            audioHandler.removeCallbacks(autoSaveWatchdog);
            return;
        }
        autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
        audioHandler.removeCallbacks(autoSaveWatchdog);
        audioHandler.postDelayed(autoSaveWatchdog, watchdogPeriodMillis());
        Log.d(TAG, "Auto-save due in " + (autoSaveIntervalMillis() / 1000) + " s");
    }

    /**
     * The capture loop can sleep for as long as AudioRecord's buffer lasts, which at 8 kHz is
     * minutes, so a cheap timer checks the deadline in between. It only reads a clock, the real
     * work still happens on the audio thread.
     */
    private long watchdogPeriodMillis() {
        // A tenth of the interval keeps a one minute interval punctual, capped so a long interval
        // does not mean a long blind spot, and floored so we never spin.
        final long base = isLowPowerEnabled() ? 30000 : 15000;
        return Math.max(3000, Math.min(base, autoSaveIntervalMillis() / 10));
    }

    private final Runnable autoSaveWatchdog = new Runnable() {
        @Override
        public void run() {
            maybeAutoSave();
            if(state != STATE_READY && isAutoSaveEnabled()) {
                audioHandler.postDelayed(autoSaveWatchdog, watchdogPeriodMillis());
            }
        }
    };

    /**
     * Saves audio memory when its time is up, or early when it is about to overflow: memory is a
     * ring buffer, so an interval longer than what memory holds would silently drop audio.
     * Idempotent and safe to call as often as we like; audio thread only.
     */
    private void maybeAutoSave() {
        if(state != STATE_LISTENING) return;
        if(manualSavePending) return; // the queued manual save is about to take this audio
        if(!isAutoSaveEnabled()) {
            autoSaveDeadline = -1;
            return;
        }
        if(autoSaveDeadline < 0) {
            autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
            return;
        }

        final long allocated = audioMemory.getAllocatedMemorySize();
        final boolean memoryFull = allocated > 0
                && audioMemory.countFilled() >= allocated * MEMORY_FULL_FRACTION;
        final boolean due = SystemClock.elapsedRealtime() >= autoSaveDeadline;
        if(!due && !memoryFull) return;

        if(writeMemoryToFile(ALL_MEMORY, null, null, true, SAMPLE_RATE, FILL_RATE)) {
            autoSaveCount++;
            if(!due) {
                memoryFullSaveCount++;
                logEvent(getString(R.string.event_saved_memory_full));
            }
        }
        autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
    }

    public boolean isAutoSaveEnabled() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getBoolean(AUTO_SAVE_ENABLED_KEY, true);
    }

    public void setAutoSaveEnabled(boolean enabled) {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).edit().putBoolean(AUTO_SAVE_ENABLED_KEY, enabled).commit();
        armAutoSave();
        updateNotification();
    }

    public int getAutoSaveIntervalMinutes() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getInt(AUTO_SAVE_INTERVAL_KEY, AUTO_SAVE_INTERVAL_DEFAULT);
    }

    public void setAutoSaveIntervalMinutes(int minutes) {
        final int clamped = Math.max(1, minutes);
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).edit().putInt(AUTO_SAVE_INTERVAL_KEY, clamped).commit();
        armAutoSave();
        updateNotification();
    }

    // ------------------------------------------------------------------ low power mode

    public boolean isLowPowerEnabled() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getBoolean(LOW_POWER_KEY, false);
    }

    /**
     * Trades audio quality for battery: capture drops to 8 kHz, which is a sixth of the data of
     * 48 kHz, so AudioRecord wakes the CPU far less often, files are a sixth of the size and the
     * UI slows its refresh down. The previous sample rate comes back when it is switched off.
     */
    public void setLowPowerEnabled(boolean enabled) {
        final SharedPreferences preferences = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(enabled == preferences.getBoolean(LOW_POWER_KEY, false)) return;

        if(enabled) {
            preferences.edit()
                    .putBoolean(LOW_POWER_KEY, true)
                    .putInt(PRE_LOW_POWER_SAMPLE_RATE_KEY, SAMPLE_RATE)
                    .commit();
            logEvent(getString(R.string.event_low_power_on));
            applySampleRate(LOW_POWER_SAMPLE_RATE);
        } else {
            final int previous = preferences.getInt(PRE_LOW_POWER_SAMPLE_RATE_KEY, SAMPLE_RATE);
            preferences.edit().putBoolean(LOW_POWER_KEY, false).commit();
            logEvent(getString(R.string.event_low_power_off, previous / 1000f));
            applySampleRate(previous);
        }
        updateNotification();
    }

    // ------------------------------------------------------------------ state and diagnostics

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
        /** Milliseconds until the next automatic save, or -1 when none is due. */
        public long nextAutoSaveInMillis;
        /** Wall clock time of the last save, or 0 when nothing was saved yet. */
        public long lastSaveMillis;
        public String lastSaveName;
        public int autoSaveCount;
        public int memoryFullSaveCount;

        public boolean lowPower;
        public int sampleRate;

        /** False when audio should be flowing but no sample has arrived in a long time. */
        public boolean capturing;
        public long bytesCaptured;
        public int readErrorCount;
        /** Milliseconds since the last read that returned audio, or -1 if there was none. */
        public long sinceLastReadMillis;

        public String lastError;
        public long lastErrorMillis;

        public String storagePath;
        public boolean storagePublic;
        /** True when the interval is longer than memory holds, so audio would be dropped. */
        public boolean intervalExceedsMemory;

        /** Nothing is wrong and audio is being kept. */
        public boolean isHealthy() {
            if(!listeningEnabled) return true; // stopped on purpose
            return capturing && lastError == null && !intervalExceedsMemory;
        }
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
        final long deadline = autoSaveDeadline;
        result.nextAutoSaveInMillis = (deadline < 0) ? -1 : Math.max(0, deadline - SystemClock.elapsedRealtime());
        result.lastSaveMillis = lastSaveMillis;
        result.lastSaveName = lastSaveName;
        result.autoSaveCount = autoSaveCount;
        result.memoryFullSaveCount = memoryFullSaveCount;
        result.lowPower = isLowPowerEnabled();
        result.sampleRate = SAMPLE_RATE;
        result.bytesCaptured = bytesCaptured;
        result.readErrorCount = readErrorCount;
        result.lastError = lastError;
        result.lastErrorMillis = lastErrorMillis;
        final File dir = getRecordingsDir();
        result.storagePath = dir.getAbsolutePath();
        result.storagePublic = Storage.isPublic(this, dir);

        final long lastRead = lastReadElapsed;
        if(state == STATE_READY) {
            result.capturing = false;
            result.sinceLastReadMillis = -1;
        } else if(lastRead == 0) {
            // Nothing has arrived yet. Give AudioRecord its buffer's worth of time before
            // calling it dead, but do call it dead after that: a microphone that never delivers
            // is precisely what happens when the service was started from the background.
            result.sinceLastReadMillis = -1;
            result.capturing = SystemClock.elapsedRealtime() - captureStartedElapsed < readGapToleranceMillis;
        } else {
            result.sinceLastReadMillis = SystemClock.elapsedRealtime() - lastRead;
            result.capturing = result.sinceLastReadMillis < readGapToleranceMillis;
        }

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
                result.intervalExceedsMemory = result.autoSaveEnabled && result.totalMemory > 0
                        && result.autoSaveIntervalMinutes * 60f > result.totalMemory;
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

    private void recordError(String message) {
        lastError = message;
        lastErrorMillis = System.currentTimeMillis();
        logEvent(message);
    }

    /** Clears the sticky error shown in the UI, so the user can see whether it comes back. */
    public void clearLastError() {
        lastError = null;
        lastErrorMillis = 0;
    }

    private void logEvent(String text) {
        synchronized (events) {
            events.addLast(System.currentTimeMillis() + "\t" + text);
            while(events.size() > EVENT_LOG_SIZE) events.removeFirst();
        }
        Log.d(TAG, "EVENT " + text);
    }

    /** The most recent events, oldest first, each as "wallClockMillis\ttext". */
    public List<String> getEvents() {
        synchronized (events) {
            return new ArrayList<String>(events);
        }
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            // Android 14 refuses a microphone foreground service started while the app is not in
            // use, which is exactly what happens on a start from the background, such as at boot.
            Log.e(TAG, "Can't go foreground", e);
            recordError(getString(R.string.error_cant_go_foreground));
        }
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
        if(isLowPowerEnabled()) {
            detail = detail + " " + getString(R.string.notification_low_power_suffix);
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
