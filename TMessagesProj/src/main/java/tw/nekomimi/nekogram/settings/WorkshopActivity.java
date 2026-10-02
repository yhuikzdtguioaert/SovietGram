package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.WorkshopHelper;
import tw.nekomimi.nekogram.helpers.WorkshopStyle;
import tw.nekomimi.nekogram.helpers.SovietWorkshop;
import tw.nekomimi.nekogram.helpers.CustomProfileHelper;
import tw.nekomimi.nekogram.ui.cells.WorkshopCell;

/**
 * The workshop: galleries of published profile looks and frames, in a grid of preview shots.
 * Tapping one offers to install it, which overwrites the matching local appearance settings.
 * <p>
 * Sections come from a picker in the title rather than a tab strip, which is how the reference
 * plugin arranges them, and only "Лучшее" carries a period.
 */
public class WorkshopActivity extends BaseFragment {

    private static final int menu_section = 1;
    private static final int menu_publish = 2;
    private static final int menu_search = 3;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private FlickerLoadingView progressView;
    private TextView emptyView;
    private ActionBarMenuItem sectionItem;
    private ActionBarMenuItem searchItem;
    private String search = "";
    private String authorFilter;
    private Runnable pendingSearch;

    @Override public void onFragmentDestroy() {
        if (pendingSearch != null) AndroidUtilities.cancelRunOnUIThread(pendingSearch);
        requestId++;
        super.onFragmentDestroy();
    }

    private final List<WorkshopHelper.Work> works = new ArrayList<>();
    private final List<WorkshopHelper.Work> allWorks = new ArrayList<>();

    /** First five sections are the reference gallery; the last four use SovietGram's server. */
    private int section = 5;
    /**
     * Which of the workshop's two galleries this screen shows — looks or avatar frames. Both are the
     * same endpoints, the same sections and the same grid; only what installing a work does differs.
     */
    private String kind;

    public WorkshopActivity() {
        this(WorkshopHelper.KIND_PROFILE);
    }

    public WorkshopActivity(String kind) {
        this.kind = kind == null ? WorkshopHelper.KIND_PROFILE : kind;
    }
    /** Bumped on every reload so a slow answer to an abandoned request is ignored. */
    private int requestId;
    private boolean loading;

    private static final String[][] SECTIONS = {
            {WorkshopHelper.MODE_NEW, ""},
            {WorkshopHelper.MODE_POPULAR, ""},
            {WorkshopHelper.MODE_BEST, WorkshopHelper.PERIOD_DAY},
            {WorkshopHelper.MODE_BEST, WorkshopHelper.PERIOD_WEEK},
            {WorkshopHelper.MODE_BEST, WorkshopHelper.PERIOD_MONTH},
            {"soviet-new", ""},
            {"soviet-popular", ""},
            {"soviet-mine", ""},
            {"soviet-favorites", ""},
    };

    private static ArrayList<String> sectionNames() {
        final ArrayList<String> names = new ArrayList<>();
        names.add(getString(R.string.WorkshopSectionNew));
        names.add(getString(R.string.WorkshopSectionPopular));
        names.add(getString(R.string.WorkshopSectionBestDay));
        names.add(getString(R.string.WorkshopSectionBestWeek));
        names.add(getString(R.string.WorkshopSectionBestMonth));
        names.add(getString(R.string.WorkshopSovietNew));
        names.add(getString(R.string.WorkshopSovietPopular));
        names.add(getString(R.string.WorkshopSovietMine));
        names.add(getString(R.string.WorkshopSovietFavorites));
        names.add(getString(R.string.WorkshopTopAuthors));
        return names;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(WorkshopHelper.KIND_FRAME.equals(kind)
                ? R.string.CustomProfileFrames : R.string.CustomProfileWorkshop));
        actionBar.setSubtitle(sectionNames().get(section));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == menu_section) {
                    showSectionPicker();
                } else if (id == menu_publish) {
                    publish();
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(menu_search, R.drawable.outline_header_search).setIsSearchField(true);
        searchItem.setSearchPaddingStart(12);
        searchItem.setSearchFieldHint(getString(R.string.Search));
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override public void onSearchCollapse() { applySearch(""); }
            @Override public void onTextChanged(EditText editText) { applySearch(editText.getText().toString()); }
            @Override public void onSearchPressed(EditText editText) { applySearch(editText.getText().toString()); }
        });
        sectionItem = menu.addItem(menu_section, R.drawable.msg_list);
        sectionItem.setContentDescription(getString(R.string.WorkshopSection));
        menu.addItem(menu_publish, R.drawable.msg_add)
                .setContentDescription(getString(R.string.WorkshopPublish));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        progressView = new FlickerLoadingView(context);
        progressView.setViewType(FlickerLoadingView.DIALOG_CELL_TYPE);
        progressView.showDate(false);
        root.addView(progressView, contentParams());

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, contentParams());

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new GridLayoutManager(context, 2));
        listView.setPadding(dp(8), dp(8), dp(8), dp(8));
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof WorkshopCell) {
                confirmInstall(((WorkshopCell) view).getWork());
            }
        });
        listView.setOnItemLongClickListener((cell, position) -> {
            if (cell instanceof WorkshopCell) {
                showWorkMenu(((WorkshopCell) cell).getWork());
                return true;
            }
            return false;
        });
        root.addView(listView, contentParams());

        load();
        return fragmentView;
    }

    private static FrameLayout.LayoutParams contentParams() {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return params;
    }

    private void applySearch(String text) {
        final String next = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if (next.equals(search)) return;
        search = next;
        if (section >= 5) {
            if (pendingSearch != null) AndroidUtilities.cancelRunOnUIThread(pendingSearch);
            pendingSearch = WorkshopActivity.this::load;
            AndroidUtilities.runOnUIThread(pendingSearch, 300);
        } else filter();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void filter() {
        works.clear();
        for (WorkshopHelper.Work work : allWorks) {
            if (search.isEmpty() || work.title.toLowerCase(java.util.Locale.ROOT).contains(search)
                    || work.authorName.toLowerCase(java.util.Locale.ROOT).contains(search)) {
                works.add(work);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmptyState(null);
    }

    private void showSectionPicker() {
        if (getParentActivity() == null) {
            return;
        }
        PopupHelper.show(sectionNames(), getString(R.string.WorkshopSection), section,
                getParentActivity(), which -> {
                    if (which == SECTIONS.length) {
                        showTopAuthors();
                        return;
                    }
                    if (which == section) {
                        return;
                    }
                    section = which;
                    authorFilter = null;
                    actionBar.setSubtitle(sectionNames().get(section));
                    load();
                });
    }

    private void showTopAuthors() {
        if (getParentActivity() == null) return;
        final ArrayList<String> modes = new ArrayList<>();
        modes.add(getString(R.string.WorkshopTopByLikes));
        modes.add(getString(R.string.WorkshopTopByRatio));
        PopupHelper.show(modes, getString(R.string.WorkshopTopAuthors), -1,
                getParentActivity(), which -> {
                    if (which < 0 || which >= modes.size()) return;
                    final int account = UserConfig.selectedAccount;
                    SovietWorkshop.topAuthors(account, kind, which == 0 ? "likes" : "ratio",
                            (authors, error) -> {
                        if (account != UserConfig.selectedAccount) return;
                        if (authors == null) { showError(error); return; }
                        if (authors.isEmpty()) {
                            showError(getString(R.string.WorkshopEmpty));
                            return;
                        }
                        final ArrayList<String> labels = new ArrayList<>();
                        for (int i = 0; i < authors.size(); i++) {
                            final SovietWorkshop.Author author = authors.get(i);
                            labels.add((i + 1) + ". " + author.name + " · "
                                    + author.works + " / ♥ " + author.likes
                                    + (which == 1 ? " (" + String.format(java.util.Locale.US,
                                    "%.1f", author.ratio) + ")" : ""));
                        }
                        PopupHelper.show(labels, getString(R.string.WorkshopTopAuthors), -1,
                                getParentActivity(), picked -> {
                            if (picked < 0 || picked >= authors.size()) return;
                            authorFilter = authors.get(picked).id;
                            section = 5;
                            actionBar.setSubtitle(getString(R.string.WorkshopAuthorWorks)
                                    + " " + authors.get(picked).name);
                            load();
                        });
                    });
                });
    }

    private void publish() {
        if (getParentActivity() == null) return;
        final int account = UserConfig.selectedAccount;
        final EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint(getString(R.string.WorkshopPublishTitle));
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.WorkshopPublish))
                .setView(input)
                .setPositiveButton(getString(R.string.WorkshopPublish), (dialog, which) -> {
                    final String title = input.getText().toString().trim();
                    if (title.isEmpty()) return;
                    SovietWorkshop.publish(account, kind, title, (id, error) -> {
                        if (id == null) showError(error);
                        else {
                            section = 7;
                            actionBar.setSubtitle(sectionNames().get(section));
                            load();
                        }
                    });
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showWorkMenu(WorkshopHelper.Work work) {
        if (work == null || !work.soviet || getParentActivity() == null) return;
        final int account = UserConfig.selectedAccount;
        final boolean mine = String.valueOf(UserConfig.getInstance(account).getClientUserId())
                .equals(work.author);
        final ArrayList<String> options = new ArrayList<>();
        options.add(getString(R.string.WorkshopAuthorWorks));
        options.add(getString(work.favorited ? R.string.WorkshopUnfavorite : R.string.WorkshopFavorite));
        options.add(getString(mine ? R.string.Delete : R.string.WorkshopReport));
        PopupHelper.show(options, work.title, -1, getParentActivity(), choice -> {
            if (choice == 0) {
                authorFilter = work.author;
                section = 5;
                actionBar.setSubtitle(getString(R.string.WorkshopAuthorWorks) + " " + work.author);
                load();
            } else if (choice == 1) {
                SovietWorkshop.favorite(account, work, !work.favorited, (done, error) -> {
                    if (done == null) showError(error);
                    else { work.favorited = done; if (section == 8) load(); }
                });
            } else if (choice == 2 && mine) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(getString(R.string.Delete))
                        .setMessage(getString(R.string.WorkshopDeleteConfirm))
                        .setPositiveButton(getString(R.string.Delete), (dialog, which) ->
                                SovietWorkshop.delete(account, work, (done, error) -> {
                                    if (done == null) showError(error);
                                    else load();
                                }))
                        .setNegativeButton(getString(R.string.Cancel), null).show();
            } else if (choice == 2) {
                final EditText reason = new EditText(getParentActivity());
                reason.setHint(getString(R.string.WorkshopReportReason));
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(getString(R.string.WorkshopReport))
                        .setView(reason)
                        .setPositiveButton(getString(R.string.Send), (dialog, which) -> {
                            final String value = reason.getText().toString().trim();
                            if (value.length() < 3) return;
                            SovietWorkshop.report(account, work, value, (done, error) -> {
                                if (done == null) showError(error);
                            });
                        })
                        .setNegativeButton(getString(R.string.Cancel), null).show();
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void load() {
        if (isFinished) return;
        final int id = ++requestId;
        loading = true;
        allWorks.clear();
        works.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyState(null);
        final int account = UserConfig.selectedAccount;
        final long owner = UserConfig.getInstance(account).getClientUserId();
        final WorkshopHelper.Callback<List<WorkshopHelper.Work>> finished = (result, error) -> {
            if (isFinished || id != requestId || account != UserConfig.selectedAccount
                    || owner != UserConfig.getInstance(account).getClientUserId()) {
                return;
            }
            loading = false;
            if (result != null) {
                allWorks.addAll(result);
            }
            filter();
            // Keep the reason. The workshop lives on a plain-HTTP host on a non-standard port, so the
            // usual failure is the network refusing to reach it — "could not load" alone leaves the
            // user unable to tell a blocked port from an empty section or a server that is really down.
            updateEmptyState(result == null ? loadFailedText(error) : null);
        };
        if (section >= 5) {
            SovietWorkshop.list(account, kind, SECTIONS[section][0].substring(7),
                    search, authorFilter, finished);
        } else {
            WorkshopHelper.list(SECTIONS[section][0], SECTIONS[section][1], kind, finished);
        }
    }

    private String loadFailedText(@Nullable String error) {
        final String failed = getString(R.string.WorkshopLoadFailed);
        return TextUtils.isEmpty(error) ? failed : failed + "\n" + error;
    }

    /** Exactly one of the three states is visible: the shimmer, the grid, or a line of text. */
    private void updateEmptyState(String error) {
        if (progressView == null) {
            return;
        }
        progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
        final boolean empty = !loading && works.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyView.setText(error != null ? error : getString(R.string.WorkshopEmpty));
        listView.setVisibility(View.VISIBLE);
    }

    /** Installing replaces every Custom Profile setting, so it is worth one confirmation. */
    private void confirmInstall(WorkshopHelper.Work work) {
        if (work == null || getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.WorkshopInstall))
                .setMessage(getString(R.string.WorkshopInstallConfirm))
                .setPositiveButton(getString(R.string.WorkshopInstall), (dialog, which) -> install(work))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void install(WorkshopHelper.Work work) {
        if (isFinished || getParentActivity() == null) return;
        final int account = UserConfig.selectedAccount;
        final long owner = UserConfig.getInstance(account).getClientUserId();
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        // The list only carries a summary; the style itself arrives with the full record.
        WorkshopHelper.load(work, (loaded, error) -> {
            if (isFinished || account != UserConfig.selectedAccount
                    || owner != UserConfig.getInstance(account).getClientUserId()) {
                progress.dismiss();
                return;
            }
            if (loaded == null) {
                progress.dismiss();
                showError(error);
                return;
            }
            final WorkshopHelper.Callback<Boolean> done0 = (done, installError) -> {
                progress.dismiss();
                if (isFinished || account != UserConfig.selectedAccount
                        || owner != UserConfig.getInstance(account).getClientUserId()) return;
                if (done == null) {
                    showError(installError);
                    return;
                }
                if (!TextUtils.isEmpty(installError)) {
                    // The style installed, but a picture the work declares could not be fetched — so
                    // say which one instead of reporting a clean install and leaving the user to work
                    // out why their new look has no banner.
                    BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.formatString(R.string.WorkshopInstalledPartly, installError)).show();
                    return;
                }
                // An installed frame is very often the starting point for one of your own, so the
                // way into the editor is offered right here rather than back in the settings.
                if (WorkshopHelper.KIND_FRAME.equals(loaded.kind)) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done,
                                    getString(R.string.WorkshopInstalled),
                                    getString(R.string.CustomProfileFrameStudio),
                                    () -> presentFragment(new FrameStudioActivity()))
                            .show();
                    return;
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done,
                        getString(R.string.WorkshopInstalled)).show();
            };
            // A frame work carries no banner, no background and no colours — installing it as a look
            // would wipe the look the user is wearing it with.
            if (WorkshopHelper.KIND_FRAME.equals(loaded.kind)) {
                WorkshopStyle.installFrame(loaded, done0);
            } else if (loaded.soviet) {
                if (CustomProfileHelper.importProfileJson(loaded.config)) {
                    done0.onResult(Boolean.TRUE, null);
                } else {
                    done0.onResult(null, getString(R.string.WorkshopLoadFailed));
                }
            } else {
                WorkshopStyle.install(loaded, done0);
            }
        });
    }

    private void showError(String error) {
        BulletinFactory.of(this).createErrorBulletin(TextUtils.isEmpty(error)
                ? getString(R.string.WorkshopLoadFailed) : error).show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final WorkshopCell cell = new WorkshopCell(context);
            final RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            cell.setLayoutParams(params);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final WorkshopCell cell = (WorkshopCell) holder.itemView;
            final WorkshopHelper.Work work = works.get(position);
            cell.setWork(work);
            cell.setOnLikeClickListener(() -> {
                final int account = UserConfig.selectedAccount;
                if (work.soviet && !tw.nekomimi.nekogram.helpers.SovietGramApiClient
                        .isReady(account)) {
                    showError("SovietGram server sign-in required");
                    return;
                }
                final boolean wanted = !work.liked;
                WorkshopHelper.like(work, wanted, (count, error) -> {
                if (account != UserConfig.selectedAccount) return;
                if (count == null) {
                    showError(error);
                    return;
                }
                work.liked = wanted;
                work.likes = count;
                if (cell.getWork() == work) cell.updateLikes();
                });
            });
        }

        @Override
        public int getItemCount() {
            return works.size();
        }

    }
}
