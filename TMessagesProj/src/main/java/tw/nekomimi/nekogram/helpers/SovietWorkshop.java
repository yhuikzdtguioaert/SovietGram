package tw.nekomimi.nekogram.helpers;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.frame.FrameBlanks;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/** SovietGram's account-authenticated, server-backed gallery alongside the legacy gallery. */
public final class SovietWorkshop {
    private SovietWorkshop() { }

    public static void list(int account, String kind, String mode, String query,
                            @Nullable String author,
                            WorkshopHelper.Callback<List<WorkshopHelper.Work>> callback) {
        if (!SovietGramApiClient.isReady(account)) {
            callback.onResult(new ArrayList<>(), null);
            return;
        }
        final String path = "/v1/works?kind=" + enc(kind) + "&mode=" + enc(mode)
                + "&q=" + enc(query)
                + (author == null ? "" : "&author=" + enc(author));
        SovietGramApiClient.get(account, path, (body, error) -> {
            if (body == null) {
                callback.onResult(null, error);
                return;
            }
            final List<WorkshopHelper.Work> works = new ArrayList<>();
            final JSONArray rows = body.optJSONArray("works");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row != null) works.add(parse(row));
            }
            callback.onResult(works, null);
        });
    }

    public static void load(int account, WorkshopHelper.Work work,
                            WorkshopHelper.Callback<WorkshopHelper.Work> callback) {
        SovietGramApiClient.get(account, "/v1/works/" + enc(work.id), (body, error) -> {
            final JSONObject row = body == null ? null : body.optJSONObject("work");
            if (row == null) {
                callback.onResult(null, error);
                return;
            }
            work.config = row.optJSONObject("payload");
            callback.onResult(work.config == null ? null : work,
                    work.config == null ? "Invalid workshop style" : null);
        });
    }

    public static void publish(int account, String kind, String title,
                               WorkshopHelper.Callback<String> callback) {
        if (account != UserConfig.selectedAccount || !SovietGramAccountScope.isLive(account)
                || !SovietGramApiClient.isReady(account)) {
            callback.onResult(null, "SovietGram server sign-in required");
            return;
        }
        try {
            if (title == null || title.trim().isEmpty() || title.trim().length() > 100) {
                callback.onResult(null, "Title must be 1–100 characters");
                return;
            }
            final JSONObject payload;
            String previewSha = null;
            if (WorkshopHelper.KIND_FRAME.equals(kind)) {
                final String spec = NekoConfig.customProfileFrameSpec.String();
                final FrameSpec frame = FrameSpec.parse(spec);
                if (frame.isEmpty()) {
                    callback.onResult(null, "Frame is empty");
                    return;
                }
                for (String source : frame.assets()) {
                    if (!FrameBlanks.is(source) && !source.startsWith("https://")) {
                        callback.onResult(null, "Publish the frame project to the server first");
                        return;
                    }
                }
                payload = new JSONObject().put("frame_spec", spec);
            } else {
                payload = CustomProfileHelper.exportProfileJson(account);
                if (!shareableFrame(NekoConfig.customProfileFrameSpec.String())) {
                    callback.onResult(null, "Publish the frame project to the server first");
                    return;
                }
                if (!shareableMedia(NekoConfig.customProfileBannerType.Int(),
                        NekoConfig.customProfileBannerMedia.String())
                        || !shareableMedia(NekoConfig.customProfileBackgroundType.Int(),
                        NekoConfig.customProfileBackgroundMedia.String())
                        || !shareableMedia(NekoConfig.customProfileNameFont.Int() == 7 ? 3 : 0,
                        NekoConfig.customProfileNameFontMedia.String())
                        || !shareableMedia(!NekoConfig.customProfileThoughtFontCopy.Bool()
                                && NekoConfig.customProfileThoughtFont.Int() == 7 ? 3 : 0,
                        NekoConfig.customProfileThoughtFontMedia.String())) {
                    CustomProfileMedia.ensurePublished();
                    callback.onResult(null, "Profile media is still uploading. Try again shortly.");
                    return;
                }
                final String descriptor = NekoConfig.customProfileBannerMedia.String();
                if (!descriptor.isEmpty()) {
                    final JSONObject media = new JSONObject(descriptor);
                    if ("api".equals(media.optString("src"))) {
                        previewSha = media.optString("sha", null);
                    }
                }
            }
            final String id = UUID.randomUUID().toString();
            final JSONObject body = new JSONObject()
                    .put("kind", kind)
                    .put("title", title.trim())
                    .put("payload", payload)
                    .put("preview_sha", previewSha == null ? JSONObject.NULL : previewSha)
                    .put("updated_ms", System.currentTimeMillis());
            SovietGramApiClient.putSigned(account, "/v1/works/" + id, body,
                    (result, error) -> callback.onResult(result == null ? null : id, error));
        } catch (Exception e) {
            FileLog.e(e);
            callback.onResult(null, e.getMessage());
        }
    }

    private static boolean shareableFrame(String spec) {
        for (String source : FrameSpec.parse(spec).assets()) {
            if (!FrameBlanks.is(source) && !source.startsWith("https://")) return false;
        }
        return true;
    }

    private static boolean shareableMedia(int type, String raw) {
        if (type != 3 && type != 4) return true;
        try {
            final JSONObject descriptor = new JSONObject(raw);
            final String sha = descriptor.optString("sha", "");
            if (!sha.matches("[a-fA-F0-9]{64}")) return false;
            return "api".equals(descriptor.optString("src"))
                    || descriptor.optString("url", "").startsWith("https://");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void favorite(int account, WorkshopHelper.Work work, boolean wanted,
                                WorkshopHelper.Callback<Boolean> callback) {
        final SovietGramApiClient.Callback done = (body, error) ->
                callback.onResult(body == null ? null : wanted, error);
        final String path = "/v1/works/" + enc(work.id) + "/favorite";
        if (wanted) SovietGramApiClient.putSigned(account, path, new JSONObject(), done);
        else SovietGramApiClient.deleteSigned(account, path, done);
    }

    public static void delete(int account, WorkshopHelper.Work work,
                              WorkshopHelper.Callback<Boolean> callback) {
        SovietGramApiClient.deleteSigned(account, "/v1/works/" + enc(work.id),
                (body, error) -> callback.onResult(body == null ? null : true, error));
    }

    public static void report(int account, WorkshopHelper.Work work, String reason,
                              WorkshopHelper.Callback<Boolean> callback) {
        try {
            SovietGramApiClient.postSigned(account, "/v1/works/" + enc(work.id) + "/report",
                    new JSONObject().put("reason", reason),
                    (body, error) -> callback.onResult(body == null ? null : true, error));
        } catch (Exception e) {
            callback.onResult(null, e.getMessage());
        }
    }

    @Nullable
    public static String previewUrl(WorkshopHelper.Work work) {
        return work.previewSha == null ? null
                : ApiServersHelper.baseUrl() + "/v1/media/" + work.previewSha;
    }

    private static WorkshopHelper.Work parse(JSONObject row) {
        final WorkshopHelper.Work work = new WorkshopHelper.Work();
        work.soviet = true;
        work.id = row.optString("id", "");
        work.kind = row.optString("kind", WorkshopHelper.KIND_PROFILE);
        work.title = row.optString("title", "");
        work.author = row.optString("owner_id", "");
        work.authorName = row.optString("author_name", work.author);
        work.previewSha = row.optString("preview_sha", null);
        work.updated = row.optLong("updated_ms", 0);
        work.likes = row.optInt("likes", 0);
        work.liked = row.optBoolean("liked", false);
        work.favorited = row.optBoolean("favorited", false);
        return work;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
