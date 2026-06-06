package com.copa.echo;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class AutoSaveReceiver extends android.content.BroadcastReceiver {
    private static final String TAG = AutoSaveReceiver.class.getSimpleName();
    public static final String ACTION_AUTO_SAVE = "com.copa.echo.AUTO_SAVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_AUTO_SAVE.equals(intent.getAction())) {
            Log.d(TAG, "Auto-save broadcast received");
            
            // Start the service to ensure it's running
            Intent serviceIntent = new Intent(context, SaidItService.class);
            serviceIntent.setAction(ACTION_AUTO_SAVE);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}
