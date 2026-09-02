package com.copa.echo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Watches how far the phone is tilted away from lying flat and, each time it crosses a
 * configurable angle, takes one still from the front camera and one from the back and writes them
 * beside the audio traces.
 *
 * The tilt is read off the gravity sensor on its own background thread, and the cameras are driven
 * with Camera2 with no preview at all: a foreground service with the {@code camera} type is what
 * lets this happen while the screen is off or another app is in front.
 *
 * A trigger is edge based. It fires once when the tilt first reaches the threshold and does not
 * fire again until the phone has been brought back below the threshold (less a few degrees of
 * hysteresis), and never more often than {@link #CAPTURE_COOLDOWN_MILLIS}. That keeps a phone
 * left standing at an angle from taking a photo every second.
 */
public class TiltCameraCapturer implements SensorEventListener {

    private static final String TAG = TiltCameraCapturer.class.getSimpleName();

    /** How far the tilt has to fall back below the threshold before it may fire again. */
    private static final float HYSTERESIS_DEGREES = 8f;
    /** Stills are scaled down to at most this wide, so a photo trace stays small. */
    private static final int MAX_JPEG_WIDTH = 1600;
    /** A camera that neither delivers an image nor errors is abandoned after this long. */
    private static final long CAMERA_TIMEOUT_MILLIS = 6000;

    /** What the capturer needs from the service: where to write, and a nudge once a file lands. */
    public interface Listener {
        /** The directory traces are written to. Called off the main thread; may touch the disk. */
        File tracesDir();
        /** A still has just been written. */
        void onCaptured(File file);
    }

    private final Context context;
    private final Listener listener;

    private SensorManager sensorManager;
    private Sensor tiltSensor;
    private HandlerThread sensorThread;
    private Handler sensorHandler;

    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private volatile boolean running = false;
    private volatile int thresholdDegrees = 45;

    /** Shortest time between two shots of the same camera, kept apart back from front. */
    private volatile long backMinMillis = 8000;
    private volatile long frontMinMillis = 8000;
    /** elapsedRealtime of the last shot taken with each camera, so each is rate limited alone. */
    private volatile long lastBackElapsed = 0;
    private volatile long lastFrontElapsed = 0;

    /** Sensor thread only: true once a trigger has fired and until the phone drops back flat. */
    private boolean triggered = false;
    /** Guards against a second capture starting while one is still in flight. */
    private volatile boolean capturing = false;

    public TiltCameraCapturer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean hasCameraPermission() {
        return context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void setThresholdDegrees(int degrees) {
        thresholdDegrees = Math.max(5, Math.min(175, degrees));
    }

    /** The shortest gap between two shots, set apart for the back camera and the front. */
    public void setMinIntervals(long backMillis, long frontMillis) {
        backMinMillis = Math.max(0, backMillis);
        frontMinMillis = Math.max(0, frontMillis);
    }

    public synchronized void start(int thresholdDegrees, long backMinMillis, long frontMinMillis) {
        setThresholdDegrees(thresholdDegrees);
        setMinIntervals(backMinMillis, frontMinMillis);
        if(running) return;
        if(!hasCameraPermission()) {
            Log.w(TAG, "No camera permission, tilt capture stays off");
            return;
        }
        sensorManager = context.getSystemService(SensorManager.class);
        cameraManager = context.getSystemService(CameraManager.class);
        if(sensorManager == null || cameraManager == null) return;

        tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if(tiltSensor == null) tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if(tiltSensor == null) {
            Log.w(TAG, "No gravity or accelerometer sensor, tilt capture unavailable");
            return;
        }

        cameraThread = new HandlerThread("tiltCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        sensorThread = new HandlerThread("tiltSensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        triggered = false;
        running = true;
        sensorManager.registerListener(this, tiltSensor,
                SensorManager.SENSOR_DELAY_UI, sensorHandler);
        Log.d(TAG, "Tilt capture on, threshold " + thresholdDegrees + " deg");
    }

    public synchronized void stop() {
        if(!running) return;
        running = false;
        if(sensorManager != null) sensorManager.unregisterListener(this);
        if(sensorThread != null) { sensorThread.quitSafely(); sensorThread = null; }
        if(cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        sensorHandler = null;
        cameraHandler = null;
        Log.d(TAG, "Tilt capture off");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(!running) return;
        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];
        final double norm = Math.sqrt(x * x + y * y + z * z);
        if(norm < 1e-3) return;

        // Angle between the screen normal and the up direction: 0 when the phone lies flat face
        // up, 90 when it stands on edge, 180 face down. That is the "tilt away from flat" the
        // threshold is measured against.
        double cos = z / norm;
        if(cos > 1) cos = 1; else if(cos < -1) cos = -1;
        final double tilt = Math.toDegrees(Math.acos(cos));

        final int threshold = thresholdDegrees;
        if(!triggered && tilt >= threshold) {
            triggered = true;
            maybeCapture();
        } else if(triggered && tilt <= threshold - HYSTERESIS_DEGREES) {
            triggered = false;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void maybeCapture() {
        if(capturing) return;
        // A shot only fires for a camera whose own minimum has passed; that per-camera gap is the
        // whole rate limit, so if neither is due there is nothing to do.
        final long now = android.os.SystemClock.elapsedRealtime();
        final boolean backDue = now - lastBackElapsed >= backMinMillis;
        final boolean frontDue = now - lastFrontElapsed >= frontMinMillis;
        if(!backDue && !frontDue) return;
        capturing = true;
        final Handler handler = cameraHandler;
        if(handler == null) { capturing = false; return; }
        handler.post(new Runnable() {
            @Override
            public void run() {
                captureDueCameras();
            }
        });
    }

    /** Camera thread: shoots whichever of the two cameras are due, one after another. */
    private void captureDueCameras() {
        final File dir = listener.tracesDir();
        final String base = SaidItService.timestampName(System.currentTimeMillis());
        final long now = android.os.SystemClock.elapsedRealtime();
        final boolean backDue = now - lastBackElapsed >= backMinMillis;
        final boolean frontDue = now - lastFrontElapsed >= frontMinMillis;

        final ArrayDeque<String[]> queue = new ArrayDeque<String[]>();
        try {
            String back = null, front = null;
            for(String id : cameraManager.getCameraIdList()) {
                final Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if(facing == null) continue;
                if(facing == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
                else if(facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id;
            }
            // The clock is stamped the moment a shot is committed to, not when it lands, so a burst
            // of tilts cannot slip several shots of one camera inside its minimum.
            if(back != null && backDue) { queue.add(new String[]{ back, "back" }); lastBackElapsed = now; }
            if(front != null && frontDue) { queue.add(new String[]{ front, "front" }); lastFrontElapsed = now; }
        } catch (Exception e) {
            Log.w(TAG, "Can't list cameras: " + e.getMessage());
        }

        if(queue.isEmpty()) { capturing = false; return; }
        captureNext(queue, dir, base);
    }

    private void captureNext(final ArrayDeque<String[]> queue, final File dir, final String base) {
        final String[] next = queue.poll();
        if(next == null) { capturing = false; return; }
        final Runnable onDone = new Runnable() {
            @Override
            public void run() {
                captureNext(queue, dir, base);
            }
        };
        try {
            captureOne(next[0], next[1], dir, base, onDone);
        } catch (Throwable t) {
            Log.w(TAG, "Capture of " + next[1] + " camera failed: " + t.getMessage());
            onDone.run();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void captureOne(final String cameraId, final String facing, final File dir,
                            final String base, final Runnable onDone) throws CameraAccessException {
        final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        final Size size = chooseSize(characteristics);
        final ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                ImageFormat.JPEG, 1);
        final File file = SaidItService.uniqueFile(dir, base + "_" + facing, ".jpg");

        // Everything below runs on the camera thread. A single latch of "done" makes sure the
        // camera, session and reader are released exactly once, whether by success, error or the
        // timeout below.
        final boolean[] finished = { false };
        final CameraDevice[] deviceHolder = { null };
        final CameraCaptureSession[] sessionHolder = { null };

        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if(finished[0]) return;
                finished[0] = true;
                cameraHandler.removeCallbacksAndMessages(reader);
                try { if(sessionHolder[0] != null) sessionHolder[0].close(); } catch (Exception ignore) {}
                try { if(deviceHolder[0] != null) deviceHolder[0].close(); } catch (Exception ignore) {}
                reader.close();
                onDone.run();
            }
        };

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image image = null;
                try {
                    image = r.acquireLatestImage();
                    if(image != null && writeJpeg(image, file)) {
                        Log.d(TAG, "Saved " + file.getName());
                        listener.onCaptured(file);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Can't save " + file.getName() + ": " + e.getMessage());
                } finally {
                    if(image != null) image.close();
                    finish.run();
                }
            }
        }, cameraHandler);

        // A camera that goes quiet must not wedge the queue forever.
        cameraHandler.postDelayed(finish, CAMERA_TIMEOUT_MILLIS);

        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice device) {
                deviceHolder[0] = device;
                try {
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    sessionHolder[0] = session;
                                    try {
                                        final CaptureRequest.Builder request = device
                                                .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        request.addTarget(reader.getSurface());
                                        request.set(CaptureRequest.CONTROL_MODE,
                                                CaptureRequest.CONTROL_MODE_AUTO);
                                        final Integer orientation = characteristics
                                                .get(CameraCharacteristics.SENSOR_ORIENTATION);
                                        if(orientation != null) {
                                            request.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                                        }
                                        session.capture(request.build(), null, cameraHandler);
                                    } catch (Exception e) {
                                        Log.w(TAG, "Can't issue capture: " + e.getMessage());
                                        finish.run();
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    Log.w(TAG, "Capture session config failed for camera " + cameraId);
                                    finish.run();
                                }
                            }, cameraHandler);
                } catch (Exception e) {
                    Log.w(TAG, "Can't create session: " + e.getMessage());
                    finish.run();
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice device) {
                deviceHolder[0] = device;
                finish.run();
            }

            @Override
            public void onError(@NonNull CameraDevice device, int error) {
                deviceHolder[0] = device;
                Log.w(TAG, "Camera " + cameraId + " error " + error);
                finish.run();
            }
        }, cameraHandler);
    }

    /** Picks a JPEG size no wider than {@link #MAX_JPEG_WIDTH}, or the smallest on offer. */
    private static Size chooseSize(CameraCharacteristics characteristics) {
        try {
            final Size[] sizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
            if(sizes != null && sizes.length > 0) {
                final List<Size> list = Arrays.asList(sizes);
                Size best = null;
                for(Size s : list) {
                    if(s.getWidth() <= MAX_JPEG_WIDTH
                            && (best == null || s.getWidth() > best.getWidth())) best = s;
                }
                if(best != null) return best;
                Size smallest = list.get(0);
                for(Size s : list) if(s.getWidth() < smallest.getWidth()) smallest = s;
                return smallest;
            }
        } catch (Exception ignore) { }
        return new Size(640, 480);
    }

    private static boolean writeJpeg(Image image, File file) throws IOException {
        final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if(bytes.length == 0) return false;
        final FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return true;
    }
}
