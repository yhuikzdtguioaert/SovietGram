/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.telegram.tgnet.ConnectionsManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PushListenerController;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GcmPushListenerService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String from = message.getFrom();
        Map<String, String> data = message.getData();
        long time = message.getSentTime();

        // Keep a release-build logcat breadcrumb without writing encrypted payloads or tokens.
        MessagesController.getGlobalNotificationsSettings().edit()
                .putLong("last_fcm_receive_time", System.currentTimeMillis())
                .apply();
        android.util.Log.i("SovietGramPush", "FCM received: sender=" + from + ", keys=" + data.keySet().size());

        String payload = data.get("p");
        if (payload != null && !payload.isEmpty()) {
            PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, payload, time);
        } else {
            // A collapsed/data-less wake-up still means Telegram has state for us. Sync every
            // signed-in account instead of dropping the wake-up or crashing on a null payload.
            wakeAccountsForSync();
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Refreshed FCM token: " + token);
            }
            ApplicationLoader.postInitApplication();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, token);
        });
    }

    @Override
    public void onDeletedMessages() {
        // FCM collapsed or evicted older pushes. A full difference sync recovers every missed
        // notification while the system-awakened service still owns execution time.
        android.util.Log.w("SovietGramPush", "FCM deleted messages; forcing account difference sync");
        wakeAccountsForSync();
        ApplicationLoader.runPushHealthCheck();
    }

    private static void wakeAccountsForSync() {
        CountDownLatch completed = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                        if (!UserConfig.getInstance(account).isClientActivated()) {
                            continue;
                        }
                        ConnectionsManager.onInternalPushReceived(account);
                        ConnectionsManager.getInstance(account).resumeNetworkMaybe();
                    }
                } finally {
                    completed.countDown();
                }
            });
        });
        try {
            completed.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
