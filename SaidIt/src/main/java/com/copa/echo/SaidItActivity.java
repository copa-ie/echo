package com.copa.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

public class SaidItActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 5465;
    private boolean isFragmentSet = false;
    private AlertDialog permissionDeniedDialog;
    private AlertDialog storagePermissionDialog;
    /** Set once the user says they are fine without all files access, so we stop nagging. */
    private boolean storageAskDeclined = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_recorder);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(permissionDeniedDialog != null) {
            permissionDeniedDialog.dismiss();
        }
        if(storagePermissionDialog != null) {
            storagePermissionDialog.dismiss();
        }
        requestPermissions();
    }

    /**
     * Only the permissions the user actually gets asked about. FOREGROUND_SERVICE used to be in
     * here, but it is granted at install time, so asking for it did nothing.
     */
    private void requestPermissions() {
        if(granted(Manifest.permission.RECORD_AUDIO)
                && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))) {
            onPermissionsSettled();
            return;
        }
        final String[] permissions = (Build.VERSION.SDK_INT >= 33)
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        // An interrupted request comes back with empty arrays. That is not consent, so do not
        // treat it as one: the microphone is what the whole app is built on.
        boolean allPermissionsGranted = grantResults.length == permissions.length;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }
        // Notifications are nice to have; only the microphone is worth blocking on.
        if (!allPermissionsGranted && granted(Manifest.permission.RECORD_AUDIO)) {
            allPermissionsGranted = true;
        }

        if (allPermissionsGranted) {
            onPermissionsSettled();
        } else if (permissionDeniedDialog == null || !permissionDeniedDialog.isShowing()) {
            showPermissionDeniedDialog();
        }
    }

    /**
     * Recording works either way: without all files access {@link Storage} falls back to the
     * app's own directory. So this asks, it does not block, which also covers GrapheneOS, where
     * users routinely deny all files access and use Storage Scopes instead.
     */
    private void onPermissionsSettled() {
        showFragment();
        if (Environment.isExternalStorageManager()) {
            if (storagePermissionDialog != null) storagePermissionDialog.dismiss();
            return;
        }
        if (storageAskDeclined) return;
        if (storagePermissionDialog != null && storagePermissionDialog.isShowing()) return;
        storagePermissionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_message)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.fromParts("package", getPackageName(), null));
                            startActivity(intent);
                        } catch (Exception e) {
                            // Some builds have no such screen; recording still works without it.
                            storageAskDeclined = true;
                        }
                    }
                })
                .setNegativeButton(R.string.continue_anyway, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        storageAskDeclined = true;
                    }
                })
                .show();
    }

    private void showFragment() {
        if (!isFragmentSet) {
            isFragmentSet = true;
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, new SaidItFragment(), "main-fragment")
                    .commit();
        }
    }
    private void showPermissionDeniedDialog() {
        permissionDeniedDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.permission_required_message)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Open app settings
                        Intent intent = new Intent();
                        intent.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }
}