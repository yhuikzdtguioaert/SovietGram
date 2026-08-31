package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Short, notification-free wake-up used by ApplicationLoader's push watchdog. */
public class PushHealthReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ApplicationLoader.ACTION_PUSH_HEALTH.equals(intent.getAction())) {
            return;
        }
        final PendingResult pending = goAsync();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ApplicationLoader.postInitApplication();
                ApplicationLoader.runPushHealthCheck();
                ApplicationLoader.startPushService();
            } catch (Throwable e) {
                FileLog.e("Push health watchdog failed", e);
            } finally {
                // Token refresh/registration is asynchronous. Keep the process available briefly;
                // the receiver is still bounded and never becomes a foreground service.
                Utilities.globalQueue.postRunnable(pending::finish, 20_000L);
            }
        });
    }
}
