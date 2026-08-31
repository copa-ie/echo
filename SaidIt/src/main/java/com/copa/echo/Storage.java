package com.copa.echo;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the directory recordings are written to.
 *
 * The public Music/Echo directory is only reachable with MANAGE_EXTERNAL_STORAGE, and holding
 * that permission is not the same as being able to write: on GrapheneOS, Storage Scopes let the
 * user grant the app a narrow set of paths while {@link Environment#isExternalStorageManager()}
 * still reports true, so creating a file fails even though every check says it should work.
 * Rather than failing silently, probe each candidate and fall back to app storage.
 */
public class Storage {

    private static final String TAG = Storage.class.getSimpleName();
    private static final String DIR_NAME = "Echo";
    private static final String PROBE_NAME = ".echo_write_probe";

    /** Every directory recordings may live in, best first. */
    public static List<File> candidates(Context context) {
        final List<File> dirs = new ArrayList<File>();
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            dirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), DIR_NAME));
            final File external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (external != null) dirs.add(new File(external, DIR_NAME));
        }
        dirs.add(new File(context.getFilesDir(), DIR_NAME));
        return dirs;
    }

    /** The first candidate a file can actually be created in. */
    public static File resolve(Context context) {
        final List<File> dirs = candidates(context);
        for (File dir : dirs) {
            if (canWrite(dir)) return dir;
        }
        // Nothing is writable; hand back internal storage so callers have a path to complain about.
        return dirs.get(dirs.size() - 1);
    }

    /** True when the public Music directory is the one being used. */
    public static boolean isPublic(Context context, File dir) {
        return dir.getAbsolutePath().startsWith(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
    }

    /**
     * Creates the directory and a throwaway file in it, which is the only way to know a write
     * will succeed. Returns false, never throws.
     */
    public static boolean canWrite(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Can't create " + dir.getAbsolutePath());
                return false;
            }
            final File probe = new File(dir, PROBE_NAME);
            final FileOutputStream out = new FileOutputStream(probe);
            try {
                out.write('e');
            } finally {
                out.close();
            }
            probe.delete();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Can't write into " + dir.getAbsolutePath() + ": " + e.getMessage());
            return false;
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to write into " + dir.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }
}
