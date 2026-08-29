package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.util.concurrent.atomic.AtomicBoolean;

public class GooglePushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {

    private Boolean hasServices;
    private final AtomicBoolean tokenRequestInFlight = new AtomicBoolean(false);
    private int retryAttempt;
    private static final long[] RETRY_DELAYS_MS = {5_000L, 30_000L, 120_000L, 600_000L};

    public GooglePushListenerServiceProvider() {}

    @Override
    public String getLogTitle() {
        return "Google Play Services";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    @Override
    public void onRequestPushToken() {
        final String currentPushString = SharedConfig.pushString;
        if (!TextUtils.isEmpty(currentPushString)) {
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM regId = " + currentPushString);
            }
            // Re-assert the last known-good token immediately. A transient Firebase failure below
            // must never replace a working registration with an empty value.
            PushListenerController.sendRegistrationToServer(getPushType(), currentPushString);
        } else {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM Registration not found.");
            }
        }
        requestToken(0L);
    }

    private void requestToken(long delayMs) {
        Utilities.globalQueue.postRunnable(() -> {
            if (!tokenRequestInFlight.compareAndSet(false, true)) return;
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                FirebaseApp.initializeApp(ApplicationLoader.applicationContext);
                FirebaseMessaging.getInstance().getToken()
                        .addOnCompleteListener(task -> {
                            tokenRequestInFlight.set(false);
                            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
                            if (!task.isSuccessful()) {
                                if (BuildVars.LOGS_ENABLED) {
                                    FileLog.d("Failed to refresh FCM regid; keeping last known token");
                                }
                                SharedConfig.pushStringStatus = "__FIREBASE_RETRYING__";
                                scheduleRetry();
                                return;
                            }
                            String token = task.getResult();
                            if (!TextUtils.isEmpty(token)) {
                                retryAttempt = 0;
                                PushListenerController.sendRegistrationToServer(getPushType(), token);
                            } else {
                                scheduleRetry();
                            }
                        });
            } catch (Throwable e) {
                tokenRequestInFlight.set(false);
                FileLog.e(e);
                scheduleRetry();
            }
        }, delayMs);
    }

    private void scheduleRetry() {
        final int index = Math.min(retryAttempt++, RETRY_DELAYS_MS.length - 1);
        requestToken(RETRY_DELAYS_MS[index]);
    }

    @Override
    public boolean hasServices() {
        if (hasServices == null) {
            try {
                int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext);
                hasServices = resultCode == ConnectionResult.SUCCESS;
            } catch (Exception e) {
                FileLog.e(e);
                hasServices = false;
            }
        }
        return hasServices;
    }
}
