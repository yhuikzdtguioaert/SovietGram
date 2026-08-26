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
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.DeletedDialogSummary;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import sovietgram.com.NaConfig;

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
    // Surviving dialogId -> every id folded into that row (including itself). Only populated for
    // conversations that exist under more than one id; see mergeMigratedDialogs.
    private final HashMap<Long, List<Long>> mergedDialogIds = new HashMap<>();
    private String searchQuery = "";
    private boolean loading;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, AyuConstants.MESSAGES_DELETED_NOTIFICATION);
        loadDialogs();
        return true;
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
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.DeletedMessagesEmpty));
        emptyView.showTextView();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setEmptyView(emptyView);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= shownItems.size()) {
                return;
            }
            DeletedDialogSummary summary = shownItems.get(position);
            presentFragment(new AyuViewDeleted(summary.dialogId, mergedDialogIds.get(summary.dialogId)));
        });

        return fragmentView;
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
            int cmp = Integer.compare(a.latestDate, b.latestDate);
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
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                items.clear();
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
            ProfileSearchCell cell = new ProfileSearchCell(mContext);
            cell.useSeparator = true;
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DeletedDialogSummary summary = shownItems.get(position);
            ProfileSearchCell cell = (ProfileSearchCell) holder.itemView;
            TLObject peer = peerCache.get(summary.dialogId);
            CharSequence name = displayName(summary);
            if (peer == null) {
                // Never hand a bare null to ProfileSearchCell: its else-branch neither clears the
                // recycled user/chat nor draws anything, so a recycled row would keep a stale avatar.
                // A minimal placeholder makes it reset state and render a letter avatar.
                if (summary.dialogId < 0) {
                    TLRPC.Chat placeholder = new TLRPC.TL_chat();
                    placeholder.id = -summary.dialogId;
                    placeholder.title = name.toString();
                    peer = placeholder;
                } else {
                    TLRPC.User placeholder = new TLRPC.TL_user();
                    placeholder.id = summary.dialogId;
                    placeholder.first_name = name.toString();
                    peer = placeholder;
                }
            }
            CharSequence status = LocaleController.formatPluralString("DeletedMessagesCount", summary.count);
            cell.setData(peer, null, name, status, false, false);
        }
    }
}
