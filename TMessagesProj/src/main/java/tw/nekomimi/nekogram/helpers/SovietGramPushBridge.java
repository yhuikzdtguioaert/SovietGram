package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.concurrent.atomic.AtomicBoolean;

import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/**
 * Converts the app's private Firebase token into a Telegram Simple Push endpoint.
 *
 * <p>Telegram cannot send to an FCM token belonging to this fork's Firebase project. Instead it
 * PUTs a tiny wake signal to the opaque endpoint returned by our API (token_type 4); the API sends
 * a high-priority, data-only FCM wake to this installation. The normal Telegram difference sync
 * then creates the ordinary message notification. No resident foreground service is involved.
 */
public final class SovietGramPushBridge {

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final long[] RETRY_DELAYS_MS = {5_000L, 30_000L, 120_000L, 600_000L};
    private static volatile String lastFcmToken;
    private static int retryAttempt;

    private SovietGramPushBridge() {
    }

    public static void registerFcmToken(String fcmToken) {
        if (TextUtils.isEmpty(fcmToken)) return;
        lastFcmToken = fcmToken;
        final int account = readyAccount();
        if (account < 0) {
            Log.i("SovietGramPush", "FCM bridge waiting for SovietGram API authentication");
            return;
        }
        if (!inFlight.compareAndSet(false, true)) return;

        try {
            final JSONObject body = new JSONObject().put("fcm_token", fcmToken);
            SovietGramApiClient.putSigned(account, "/v1/push/device", body, (json, error) -> {
                inFlight.set(false);
                final String path = json == null ? null : json.optString("endpoint_path", null);
                final String base = ApiServersHelper.baseUrl();
                if (error == null && path != null && path.startsWith("/v1/push/wake/")
                        && !TextUtils.isEmpty(base)) {
                    retryAttempt = 0;
                    final String endpoint = base + path;
                    PushListenerController.sendRegistrationToServer(
                            PushListenerController.PUSH_TYPE_SIMPLE, endpoint);
                    Log.i("SovietGramPush", "Telegram Simple Push bridge registered");
                    return;
                }
                Log.w("SovietGramPush", "FCM bridge registration failed; retry scheduled");
                scheduleRetry();
            });
        } catch (Throwable e) {
            inFlight.set(false);
            FileLog.e(e);
            scheduleRetry();
        }
    }

    /** Called after the API token bootstrap finishes, when an earlier FCM callback had to wait. */
    public static void onApiAuthenticationReady() {
        final String token = lastFcmToken;
        if (!TextUtils.isEmpty(token)) {
            registerFcmToken(token);
        } else {
            PushListenerController.getProvider().onRequestPushToken();
        }
    }

    private static int readyAccount() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()
                    && SovietGramApiClient.isReady(account)) {
                return account;
            }
        }
        return -1;
    }

    private static void scheduleRetry() {
        final int index = Math.min(retryAttempt++, RETRY_DELAYS_MS.length - 1);
        Utilities.globalQueue.postRunnable(() -> {
            final String token = lastFcmToken;
            if (!TextUtils.isEmpty(token)) registerFcmToken(token);
        }, RETRY_DELAYS_MS[index]);
    }
}
