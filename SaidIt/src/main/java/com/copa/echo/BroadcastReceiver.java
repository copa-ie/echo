package com.copa.echo;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BroadcastReceiver extends android.content.BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
        // Start only if tutorial has been finished
        if (context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE).getBoolean("skip_tutorial", false)) {
            ContextCompat.startForegroundService(context, new Intent(context, SaidItService.class));
        }
    }
}
