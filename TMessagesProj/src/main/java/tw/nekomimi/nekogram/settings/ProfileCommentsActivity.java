package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.SovietGramApiClient;

/** Comments and likes attached to one SovietGram profile, scoped by the API token's account. */
public class ProfileCommentsActivity extends CustomProfileListActivity {
    private final long profileId;
    private final int account;
    private final boolean mine;
    private JSONArray comments = new JSONArray();
    private JSONArray blockedUsers = new JSONArray();
    private int likes;
    private boolean liked;
    private boolean commentsEnabled = true;
    private boolean commentsPreview = true;
    private String error;

    public ProfileCommentsActivity(long profileId) {
        this.profileId = profileId;
        account = UserConfig.selectedAccount;
        mine = profileId == UserConfig.getInstance(account).getClientUserId();
    }

    @Override
    protected String title() {
        return getString(R.string.CustomProfileComments);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        refresh();
        return view;
    }

    @Override
    protected void buildRows() {
        header(getString(R.string.CustomProfileSocial));
        setting(getString(R.string.CustomProfileLikes), String.valueOf(likes), this::toggleLike);
        if (mine) {
            check(getString(R.string.CustomProfileCommentsEnabled), commentsEnabled,
                    () -> updateSettings(!commentsEnabled, commentsPreview));
            check(getString(R.string.CustomProfileCommentsPreview), commentsPreview,
                    () -> updateSettings(commentsEnabled, !commentsPreview));
        }
        shadow();
        header(getString(R.string.CustomProfileComments));
        if (error != null) info(error);
        if (commentsEnabled) {
            setting(getString(R.string.CustomProfileWriteComment), null,
                    () -> compose(null));
        }
        for (int i = 0; i < comments.length(); i++) {
            final JSONObject comment = comments.optJSONObject(i);
            if (comment == null) continue;
            final String author = comment.optString("author_id");
            final String parent = comment.optString("parent_id", "");
            final String prefix = parent.isEmpty() || "null".equals(parent) ? "" : "↳ ";
            final String value = comment.optString("body") + "  ·  ♥ " + comment.optInt("likes");
            final Row row = setting(prefix + author, value, () -> commentMenu(comment));
            row.onLongClick = () -> commentMenu(comment);
        }
        if (comments.length() == 0 && error == null) {
            info(getString(R.string.CustomProfileNoComments));
        }
        if (mine) {
            shadow();
            header(getString(R.string.CustomProfileBlockedCommenters));
            for (int i = 0; i < blockedUsers.length(); i++) {
                final String id = blockedUsers.optString(i);
                setting(id, getString(R.string.CustomProfileUnblockCommenter), () ->
                        SovietGramApiClient.deleteSigned(account,
                                "/v1/profile-comment-blocks/" + id, (body, failure) -> {
                                    if (body != null) refresh();
                                    else showError(failure);
                                }));
            }
        }
    }

    private void refresh() {
        if (!SovietGramApiClient.isReady(account)) {
            error = getString(R.string.CustomProfileSocialUnavailable);
            rebuild();
            return;
        }
        SovietGramApiClient.get(account, "/v1/profile-social/" + profileId, (body, failure) -> {
            if (body == null) {
                error = failure;
                rebuild();
                return;
            }
            likes = body.optInt("likes");
            liked = body.optBoolean("liked");
            commentsEnabled = body.optBoolean("comments_enabled", true);
            commentsPreview = body.optBoolean("comments_preview", true);
            error = null;
            rebuild();
        });
        SovietGramApiClient.get(account, "/v1/profile-comments/" + profileId, (body, failure) -> {
            if (body != null) {
                comments = body.optJSONArray("comments");
                if (comments == null) comments = new JSONArray();
                rebuild();
            } else {
                error = failure;
                rebuild();
            }
        });
        if (mine) {
            SovietGramApiClient.get(account, "/v1/profile-comment-blocks", (body, failure) -> {
                if (body != null) {
                    blockedUsers = body.optJSONArray("users");
                    if (blockedUsers == null) blockedUsers = new JSONArray();
                    rebuild();
                }
            });
        }
    }

    private void toggleLike() {
        final SovietGramApiClient.Callback callback = (body, failure) -> {
            if (body != null) refresh();
            else showError(failure);
        };
        if (liked) SovietGramApiClient.deleteSigned(account, "/v1/profile-likes/" + profileId, callback);
        else SovietGramApiClient.putSigned(account, "/v1/profile-likes/" + profileId,
                new JSONObject(), callback);
    }

    private void updateSettings(boolean enabled, boolean preview) {
        try {
            final JSONObject body = new JSONObject().put("comments_enabled", enabled)
                    .put("comments_preview", preview);
            SovietGramApiClient.putSigned(account, "/v1/profile-social/settings", body,
                    (result, failure) -> {
                        if (result != null) refresh();
                        else showError(failure);
                    });
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void compose(String parentId) {
        if (getParentActivity() == null) return;
        final EditText input = new EditText(getParentActivity());
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setHint(getString(R.string.CustomProfileWriteComment));
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(parentId == null ? R.string.CustomProfileWriteComment
                        : R.string.CustomProfileReply))
                .setView(input)
                .setPositiveButton(getString(R.string.Send), (dialog, which) -> {
                    final String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    try {
                        final JSONObject body = new JSONObject().put("body", value);
                        if (parentId != null) body.put("parent_id", parentId);
                        SovietGramApiClient.postSigned(account, "/v1/profile-comments/" + profileId,
                                body, (result, failure) -> {
                                    if (result != null) refresh();
                                    else showError(failure);
                                });
                    } catch (Exception e) {
                        showError(e.getMessage());
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void commentMenu(JSONObject comment) {
        if (getParentActivity() == null) return;
        final String id = comment.optString("id");
        final String author = comment.optString("author_id");
        final boolean ownComment = author.equals(String.valueOf(UserConfig.getInstance(account).getClientUserId()));
        final ArrayList<String> labels = new ArrayList<>();
        labels.add(getString(R.string.CustomProfileReply));
        labels.add(getString(R.string.CustomProfileLikes) + (comment.optBoolean("liked") ? " ✓" : ""));
        if (mine || ownComment) labels.add(getString(R.string.Delete));
        if (mine && !ownComment) labels.add(getString(R.string.CustomProfileBlockCommenter));
        PopupHelper.show(labels, author, -1, getParentActivity(), item -> {
            if (item == 0) {
                compose(id);
            } else if (item == 1) {
                final String path = "/v1/profile-comments/" + id + "/like";
                final SovietGramApiClient.Callback callback = (body, failure) -> {
                    if (body != null) refresh();
                    else showError(failure);
                };
                if (comment.optBoolean("liked")) SovietGramApiClient.deleteSigned(account, path, callback);
                else SovietGramApiClient.putSigned(account, path, new JSONObject(), callback);
            } else if (labels.get(item).equals(getString(R.string.Delete))) {
                SovietGramApiClient.deleteSigned(account, "/v1/profile-comments/" + id,
                        (body, failure) -> {
                            if (body != null) refresh();
                            else showError(failure);
                        });
            } else if (mine) {
                SovietGramApiClient.putSigned(account, "/v1/profile-comment-blocks/" + author,
                        new JSONObject(), (body, failure) -> {
                            if (body != null) refresh();
                            else showError(failure);
                        });
            }
        });
    }

    private void showError(String message) {
        error = message == null ? getString(R.string.CustomProfileSocialUnavailable) : message;
        rebuild();
    }
}
