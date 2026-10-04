/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.DeletedDialogSummary;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import sovietgram.com.NaConfig;
import tw.nekomimi.nekogram.NekoConfig;

// Aggregate screen: lists every chat that has saved deleted messages.
// Tapping a row opens the existing per-chat AyuViewDeleted screen.
public class AyuDeletedDialogsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SORT = 1;
    private static final int MENU_SEARCH = 2;
    private static final int SUBITEM_SORT_NEWEST = 10;
    private static final int SUBITEM_SORT_OLDEST = 11;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;
    private ActionBarMenuItem sortItem;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuSubItem sortNewestItem;
    private ActionBarMenuSubItem sortOldestItem;
    // items = everything loaded from the database, already in the chosen sort order.
    // shownItems = items after the search query is applied; the adapter only ever reads this one.
    private final ArrayList<DeletedDialogSummary> items = new ArrayList<>();
    private final ArrayList<DeletedDialogSummary> shownItems = new ArrayList<>();
    private final HashMap<Long, TLObject> peerCache = new HashMap<>();
    private final HashMap<Long, String> nameCache = new HashMap<>();
    // The newest saved message of every row: what the row shows as its text and the time it is sorted and labelled by.
    private final HashMap<Long, String> lastText = new HashMap<>();
    private final HashMap<Long, Integer> lastDate = new HashMap<>();
    // Surviving dialogId -> every id folded into that row (including itself). Only populated for
    // conversations that exist under more than one id; see mergeMigratedDialogs.
    private final HashMap<Long, List<Long>> mergedDialogIds = new HashMap<>();
    private String searchQuery = "";
    private boolean loading;
    // Dialog ids the user pinned to the top of this list. Kept per account, in the shared preferences.
    private final HashSet<Long> pinned = new HashSet<>();
    // How many saved messages of each dialog were there when its row was last opened: the badge on the right
    // counts only what came after that, like an unread counter, and goes away once the dialog is opened.
    private final HashMap<Long, Integer> seen = new HashMap<>();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, AyuConstants.MESSAGES_DELETED_NOTIFICATION);
        loadPinned();
        loadSeen();
        loadDialogs();
        return true;
    }

    private String pinnedKey() {
        return "ayuDeletedPins_" + UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private void loadPinned() {
        pinned.clear();
        try {
            for (String id : NekoConfig.getPreferences().getStringSet(pinnedKey(), new HashSet<>())) {
                pinned.add(Long.parseLong(id));
            }
        } catch (Exception ignored) {
        }
    }

    private String seenKey() {
        return "ayuDeletedSeen_" + UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private void loadSeen() {
        seen.clear();
        try {
            for (String entry : NekoConfig.getPreferences().getStringSet(seenKey(), new HashSet<>())) {
                final int colon = entry.indexOf(':');
                if (colon > 0) {
                    seen.put(Long.parseLong(entry.substring(0, colon)), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSeen() {
        final HashSet<String> out = new HashSet<>();
        for (java.util.Map.Entry<Long, Integer> e : seen.entrySet()) {
            out.add(e.getKey() + ":" + e.getValue());
        }
        NekoConfig.getPreferences().edit().putStringSet(seenKey(), out).apply();
    }

    private int unreadOf(DeletedDialogSummary summary) {
        final Integer was = seen.get(summary.dialogId);
        return Math.max(0, summary.count - (was == null ? 0 : was));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from a dialog that was just read: its badge is gone.
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void savePinned() {
        final HashSet<String> out = new HashSet<>();
        for (long id : pinned) {
            out.add(Long.toString(id));
        }
        NekoConfig.getPreferences().edit().putStringSet(pinnedKey(), out).apply();
    }

    private void togglePinned(DeletedDialogSummary summary) {
        if (!pinned.remove(summary.dialogId)) {
            pinned.add(summary.dialogId);
        }
        savePinned();
        applySort();
        applySearchFilter();
        if (listView != null) {
            listView.scrollToPosition(0);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, AyuConstants.MESSAGES_DELETED_NOTIFICATION);
    }

    private boolean isOldestFirst() {
        return NaConfig.INSTANCE.getDeletedDialogsSortOldestFirst().Bool();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.DeletedMessagesChat));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == SUBITEM_SORT_NEWEST || id == SUBITEM_SORT_OLDEST) {
                    final boolean oldestFirst = id == SUBITEM_SORT_OLDEST;
                    if (oldestFirst == isOldestFirst()) {
                        return;
                    }
                    NaConfig.INSTANCE.getDeletedDialogsSortOldestFirst().setConfigBool(oldestFirst);
                    updateSortChecks();
                    applySort();
                    applySearchFilter();
                    if (listView != null) {
                        listView.scrollToPosition(0);
                    }
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();

        searchItem = menu.addItem(MENU_SEARCH, R.drawable.ic_ab_search_solar).setIsSearchField(true);
        searchItem.setSearchFieldHint(getString(R.string.DeletedMessagesSearchHint));
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                // ActionBarMenuItem already fades the sibling icons out for us; touching
                // their visibility here would change the bar's width mid-animation.
                searchItem.getSearchField().setText(searchQuery);
                searchItem.getSearchField().setSelection(searchItem.getSearchField().length());
            }

            @Override
            public void onSearchCollapse() {
                searchQuery = "";
                applySearchFilter();
            }

            @Override
            public void onTextChanged(EditText editText) {
                String newQuery = editText.getText().toString();
                if (!TextUtils.equals(searchQuery, newQuery)) {
                    searchQuery = newQuery;
                    applySearchFilter();
                }
            }

            @Override
            public void onSearchPressed(EditText editText) {
                searchQuery = editText.getText().toString();
                applySearchFilter();
            }
        });

        sortItem = menu.addItem(MENU_SORT, R.drawable.ic_filter_list);
        sortItem.setContentDescription(getString(R.string.SortBy));
        sortNewestItem = sortItem.addSubItem(SUBITEM_SORT_NEWEST, R.drawable.menu_sort_date, getString(R.string.DeletedMessagesSortNewest), true);
        sortOldestItem = sortItem.addSubItem(SUBITEM_SORT_OLDEST, R.drawable.menu_sort_date, getString(R.string.DeletedMessagesSortOldest), true);
        updateSortChecks();

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.DeletedMessagesEmpty));
        // Nothing is said about an empty list until the first load has actually finished.
        emptyView.showProgress();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setEmptyView(emptyView);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= shownItems.size()) {
                return;
            }
            DeletedDialogSummary summary = shownItems.get(position);
            openSummary(summary);
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= shownItems.size() || getParentActivity() == null) {
                return false;
            }
            final DeletedDialogSummary summary = shownItems.get(position);
            final boolean isPinned = pinned.contains(summary.dialogId);
            BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
            builder.setTitle(displayName(summary), true);
            builder.setItems(new CharSequence[]{
                    getString(R.string.Open),
                    getString(isPinned ? R.string.UnpinFromTop : R.string.PinToTop),
                    getString(R.string.Delete)
            }, new int[]{
                    R.drawable.msg_openin,
                    isPinned ? R.drawable.msg_unpin : R.drawable.msg_pin,
                    R.drawable.msg_delete
            }, (dialog, which) -> {
                if (which == 0) {
                    openSummary(summary);
                } else if (which == 1) {
                    togglePinned(summary);
                } else {
                    confirmDelete(summary);
                }
            });
            showDialog(builder.create());
            return true;
        });

        return fragmentView;
    }

    private void openSummary(DeletedDialogSummary summary) {
        seen.put(summary.dialogId, summary.count);
        saveSeen();
        // No transition: the screen is a list read from the phone, so it is there at once.
        presentFragment(new AyuViewDeleted(summary.dialogId, mergedDialogIds.get(summary.dialogId)), false, true);
    }

    private void confirmDelete(DeletedDialogSummary summary) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.Delete));
        builder.setMessage(LocaleController.formatString(R.string.DeletedMessagesDeleteConfirm, displayName(summary)));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> deleteDialog(summary));
        final AlertDialog alert = builder.create();
        showDialog(alert);
        final TextView button = (TextView) alert.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteDialog(DeletedDialogSummary summary) {
        final List<Long> ids = mergedDialogIds.get(summary.dialogId) != null
                ? new ArrayList<>(mergedDialogIds.get(summary.dialogId))
                : Collections.singletonList(summary.dialogId);
        // The row goes at once; the database and the saved files follow in the background.
        items.remove(summary);
        pinned.remove(summary.dialogId);
        savePinned();
        seen.remove(summary.dialogId);
        saveSeen();
        applySearchFilter();
        Utilities.globalQueue.postRunnable(() -> {
            for (long id : ids) {
                AyuMessagesController.getInstance().deleteCurrent(id, 0, null);
            }
        });
    }

    private static String describe(DeletedMessage message) {
        if (!TextUtils.isEmpty(message.text)) {
            return message.text.replace('\n', ' ');
        }
        final String mime = message.mimeType == null ? "" : message.mimeType;
        if (mime.startsWith("image/")) {
            return getString(R.string.AttachPhoto);
        } else if (mime.startsWith("video/")) {
            return getString(R.string.AttachVideo);
        } else if (mime.startsWith("audio/")) {
            return getString(R.string.AttachAudio);
        } else if (message.documentSerialized != null || !TextUtils.isEmpty(message.mediaPath)) {
            return getString(R.string.AttachDocument);
        }
        return "";
    }

    private void updateSortChecks() {
        final boolean oldestFirst = isOldestFirst();
        if (sortNewestItem != null) {
            sortNewestItem.setChecked(!oldestFirst);
        }
        if (sortOldestItem != null) {
            sortOldestItem.setChecked(oldestFirst);
        }
    }

    /**
     * Sorts in place instead of flipping the list, so toggling the option twice lands back
     * on exactly the order the DAO returned rather than on a stale reversal.
     * latestDate is the entityCreateDate of the newest deletion recorded for that chat.
     */
    private void applySort() {
        final int direction = isOldestFirst() ? 1 : -1;
        Collections.sort(items, (a, b) -> {
            // Pinned chats stay on top whichever way the rest is sorted.
            final boolean pinA = pinned.contains(a.dialogId);
            final boolean pinB = pinned.contains(b.dialogId);
            if (pinA != pinB) {
                return pinA ? -1 : 1;
            }
            int cmp = Integer.compare(dateOf(a), dateOf(b));
            if (cmp == 0) {
                // Same second: fall back to the message id, then the dialog id, so the order
                // is stable across reloads instead of depending on the map iteration.
                cmp = Integer.compare(a.latestMessageId, b.latestMessageId);
            }
            if (cmp == 0) {
                cmp = Long.compare(a.dialogId, b.dialogId);
            }
            return direction * cmp;
        });
    }

    private int dateOf(DeletedDialogSummary summary) {
        final Integer date = lastDate.get(summary.dialogId);
        return date != null ? date : summary.latestDate;
    }

    private void applySearchFilter() {
        shownItems.clear();
        if (TextUtils.isEmpty(searchQuery)) {
            shownItems.addAll(items);
        } else {
            final String query = searchQuery.trim().toLowerCase(Locale.getDefault());
            final String translitQuery = LocaleController.getInstance().getTranslitString(query);
            for (DeletedDialogSummary summary : items) {
                final String name = nameCache.get(summary.dialogId);
                if (name == null) {
                    continue;
                }
                if (name.contains(query)
                        || (translitQuery != null && LocaleController.getInstance().getTranslitString(name).contains(translitQuery))) {
                    shownItems.add(summary);
                }
            }
        }
        if (emptyView != null) {
            emptyView.setText(TextUtils.isEmpty(searchQuery)
                    ? getString(R.string.DeletedMessagesEmpty)
                    : getString(R.string.NoResult));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void loadDialogs() {
        if (loading) {
            return;
        }
        loading = true;
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            final List<DeletedDialogSummary> raw = AyuMessagesController.getInstance().getDialogsWithDeleted(userId);
            final HashMap<Long, TLObject> resolved = new HashMap<>();
            if (raw != null) {
                for (DeletedDialogSummary s : raw) {
                    // getUserOrChat is memory-only and returns null for peers not currently cached
                    // (e.g. chats you have since left); fall back to a synchronous storage read.
                    TLObject peer = getMessagesController().getUserOrChat(s.dialogId);
                    if (peer == null) {
                        if (s.dialogId > 0) {
                            peer = getMessagesStorage().getUserSync(s.dialogId);
                        } else if (s.dialogId < 0) {
                            peer = getMessagesStorage().getChatSync(-s.dialogId);
                        }
                    }
                    if (peer != null) {
                        resolved.put(s.dialogId, peer);
                    }
                }
            }
            final HashMap<Long, List<Long>> merged = new HashMap<>();
            final List<DeletedDialogSummary> result = mergeMigratedDialogs(raw, resolved, merged);
            final HashMap<Long, String> texts = new HashMap<>();
            final HashMap<Long, Integer> dates = new HashMap<>();
            if (result != null) {
                for (DeletedDialogSummary s : result) {
                    final List<Long> ids = merged.get(s.dialogId) != null ? merged.get(s.dialogId) : Collections.singletonList(s.dialogId);
                    DeletedMessage newest = null;
                    for (long id : ids) {
                        final List<DeletedMessageFull> latest = AyuMessagesController.getInstance().getLatestMessages(userId, id, 1);
                        if (latest != null && !latest.isEmpty() && latest.get(0).message != null
                                && (newest == null || latest.get(0).message.date > newest.date)) {
                            newest = latest.get(0).message;
                        }
                    }
                    if (newest != null) {
                        texts.put(s.dialogId, describe(newest));
                        dates.put(s.dialogId, newest.date);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                items.clear();
                lastText.clear();
                lastDate.clear();
                lastText.putAll(texts);
                lastDate.putAll(dates);
                peerCache.clear();
                nameCache.clear();
                mergedDialogIds.clear();
                if (result != null) {
                    items.addAll(result);
                }
                peerCache.putAll(resolved);
                mergedDialogIds.putAll(merged);
                for (DeletedDialogSummary s : items) {
                    nameCache.put(s.dialogId, displayName(s).toString().toLowerCase(Locale.getDefault()));
                }
                applySort();
                applySearchFilter();
                if (emptyView != null) {
                    emptyView.showTextView();
                }
            });
        });
    }

    /**
     * A basic group that was upgraded to a supergroup keeps its saved messages under the old
     * chat id while new ones land under the channel id, so the same conversation shows up twice.
     * Telegram itself treats the pair as one chat (see the migrated_to redirect in DialogsActivity),
     * so fold the rows together — but keep both ids around so opening the row still shows every
     * saved message rather than silently dropping the pre-migration half.
     * <p>
     * Only migration is folded. Two chats that merely share a title (a channel and its linked
     * discussion group, say) stay separate, because they really are separate conversations.
     */
    private static List<DeletedDialogSummary> mergeMigratedDialogs(List<DeletedDialogSummary> raw,
                                                                   HashMap<Long, TLObject> resolved,
                                                                   HashMap<Long, List<Long>> mergedOut) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // Old chat id -> the supergroup it became.
        final HashMap<Long, Long> redirect = new HashMap<>();
        for (DeletedDialogSummary s : raw) {
            TLObject peer = resolved.get(s.dialogId);
            if (peer instanceof TLRPC.Chat chat && chat.migrated_to != null) {
                redirect.put(s.dialogId, -chat.migrated_to.channel_id);
            }
        }
        if (redirect.isEmpty()) {
            return raw;
        }
        final HashMap<Long, DeletedDialogSummary> byDialog = new HashMap<>();
        for (DeletedDialogSummary s : raw) {
            byDialog.put(s.dialogId, s);
        }
        final List<DeletedDialogSummary> out = new ArrayList<>(raw.size());
        for (DeletedDialogSummary s : raw) {
            Long target = redirect.get(s.dialogId);
            // Only fold when the surviving supergroup also has saved messages; otherwise the old
            // chat is the only row there is and redirecting it would leave nothing to click.
            if (target != null && byDialog.containsKey(target)) {
                DeletedDialogSummary survivor = byDialog.get(target);
                survivor.count += s.count;
                if (s.latestDate > survivor.latestDate) {
                    survivor.latestDate = s.latestDate;
                }
                List<Long> ids = mergedOut.get(target);
                if (ids == null) {
                    ids = new ArrayList<>();
                    ids.add(target);
                    mergedOut.put(target, ids);
                }
                ids.add(s.dialogId);
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private CharSequence displayName(DeletedDialogSummary summary) {
        TLObject peer = peerCache.get(summary.dialogId);
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            return ContactsController.formatName(user.first_name, user.last_name);
        } else if (peer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) peer).title;
        }
        return String.valueOf(summary.dialogId);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == AyuConstants.MESSAGES_DELETED_NOTIFICATION) {
            loadDialogs();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return shownItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // The chat list's own row: photo, name, last text, time, badge and pin look exactly as there.
            final DialogCell cell = new DialogCell(null, mContext, false, false, currentAccount, null);
            cell.useSeparator = true;
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final DeletedDialogSummary summary = shownItems.get(position);
            final DialogCell cell = (DialogCell) holder.itemView;
            final DialogCell.CustomDialog row = new DialogCell.CustomDialog();
            row.name = displayName(summary).toString();
            row.id = (int) summary.dialogId;
            row.peer = peerCache.get(summary.dialogId);
            final String text = lastText.get(summary.dialogId);
            row.message = TextUtils.isEmpty(text) ? LocaleController.formatPluralString("DeletedMessagesCount", summary.count) : text;
            row.date = dateOf(summary);
            // The badge counts the saved messages, the way a chat counts what you have not read.
            row.unread_count = unreadOf(summary);
            row.pinned = pinned.contains(summary.dialogId);
            row.muted = false;
            cell.setDialog(row);
        }
    }
}
