package com.copa.echo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Takes a screenshot every so often while the phone is being used, using MediaProjection.
 *
 * There is no silent way to read the whole screen on Android: MediaProjection is the only route
 * short of root or a system app, and it costs a one time consent dialog and a persistent capture
 * indicator. The token it hands back cannot outlive the process, so screen capture stops when the
 * service is killed and has to be switched on again by hand.
 *
 * "While the phone is being used" is read as "while the screen is interactive": a frame is grabbed
 * on unlock and then on a timer for as long as the screen stays on, and never while it is off.
 */
public class ScreenCapturer {

    private static final String TAG = ScreenCapturer.class.getSimpleName();

    /** The virtual display is never bigger than this on its long edge, to keep shots small. */
    private static final int MAX_EDGE = 1280;

    public interface Listener {
        File tracesDir();
        void onCaptured(File file);
    }

    private final Context context;
    private final Listener listener;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private PowerManager powerManager;

    private int width, height, densityDpi;
    private volatile boolean running = false;

    /** Shortest time between two screenshots, and the tick period while the screen is on. */
    private volatile long minIntervalMillis = 60000;
    private volatile long lastShotElapsed = 0;

    public ScreenCapturer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** The shortest gap between two screenshots. Also the poll rate while the screen is on. */
    public void setMinIntervalMillis(long millis) {
        minIntervalMillis = Math.max(1000, millis);
    }

    /**
     * Starts capturing with a projection consent just granted to the activity. The service must
     * already be a foreground service of the mediaProjection type before this is called.
     */
    public synchronized boolean start(int resultCode, Intent data) {
        if(running) return true;
        final MediaProjectionManager manager = context.getSystemService(MediaProjectionManager.class);
        if(manager == null || data == null) return false;

        projection = manager.getMediaProjection(resultCode, data);
        if(projection == null) return false;

        powerManager = context.getSystemService(PowerManager.class);
        measureScreen();

        thread = new HandlerThread("screenCapture");
        thread.start();
        handler = new Handler(thread.getLooper());

        // A registered callback is mandatory from Android 14 on, and it is also where a projection
        // the user stops from the system indicator is cleaned up.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "Projection stopped by the system");
                stop();
            }
        }, handler);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("echo-screen",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);

        running = true;
        context.registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        handler.postDelayed(shotTick, minIntervalMillis);
        Log.d(TAG, "Screen capture on at " + width + "x" + height);
        return true;
    }

    public synchronized void stop() {
        if(!running) return;
        running = false;
        try { context.unregisterReceiver(screenReceiver); } catch (Exception ignore) {}
        if(handler != null) handler.removeCallbacks(shotTick);
        if(virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if(imageReader != null) { imageReader.close(); imageReader = null; }
        if(projection != null) { projection.stop(); projection = null; }
        if(thread != null) { thread.quitSafely(); thread = null; }
        handler = null;
        Log.d(TAG, "Screen capture off");
    }

    private void measureScreen() {
        final WindowManager wm = context.getSystemService(WindowManager.class);
        final DisplayMetrics metrics = new DisplayMetrics();
        final android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        int w = bounds.width();
        int h = bounds.height();
        densityDpi = context.getResources().getConfiguration().densityDpi;
        if(densityDpi <= 0) densityDpi = DisplayMetrics.DENSITY_DEFAULT;

        // Scale the whole thing down so the long edge is at most MAX_EDGE. The aspect ratio is
        // kept, which is all the virtual display cares about.
        final int longEdge = Math.max(w, h);
        if(longEdge > MAX_EDGE) {
            final float scale = MAX_EDGE / (float) longEdge;
            w = Math.round(w * scale);
            h = Math.round(h * scale);
        }
        // Widths that are not even upset some encoders; round to a safe multiple.
        width = Math.max(2, w - (w % 2));
        height = Math.max(2, h - (h % 2));
    }

    private final Runnable shotTick = new Runnable() {
        @Override
        public void run() {
            if(!running) return;
            if(powerManager != null && powerManager.isInteractive()) maybeCaptureFrame();
            if(handler != null) handler.postDelayed(this, minIntervalMillis);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The phone has just been unlocked, which is the clearest moment of "being used".
            if(handler != null) handler.post(new Runnable() {
                @Override
                public void run() { maybeCaptureFrame(); }
            });
        }
    };

    /** Takes a screenshot only if the minimum since the last one has passed. */
    private void maybeCaptureFrame() {
        final long now = SystemClock.elapsedRealtime();
        if(now - lastShotElapsed < minIntervalMillis) return;
        lastShotElapsed = now;
        captureFrame();
    }

    /** Camera-of-the-screen: grab the newest mirrored frame and write it out as a JPEG. */
    private void captureFrame() {
        if(!running || imageReader == null) return;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if(image == null) return;
            final Bitmap bitmap = toBitmap(image);
            final File dir = listener.tracesDir();
            final File file = SaidItService.uniqueFile(dir,
                    SaidItService.timestampName(System.currentTimeMillis()) + "_screen", ".jpg");
            final FileOutputStream out = new FileOutputStream(file);
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
            } finally {
                out.close();
            }
            bitmap.recycle();
            Log.d(TAG, "Saved " + file.getName());
            listener.onCaptured(file);
        } catch (Exception e) {
            Log.w(TAG, "Can't take a screenshot: " + e.getMessage());
        } finally {
            if(image != null) image.close();
        }
    }

    private Bitmap toBitmap(Image image) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        // The reader hands back rows padded to a stride, so the bitmap is made a little wider and
        // then cropped back to the real width.
        final int rowPadding = rowStride - pixelStride * width;
        final int paddedWidth = width + rowPadding / pixelStride;
        final Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if(paddedWidth == width) return padded;
        final Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }
}
