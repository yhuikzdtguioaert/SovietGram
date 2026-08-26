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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
import tw.nekomimi.nekogram.ui.cells.WorkshopCell;

/**
 * The workshop: the gallery of profile looks other people have published, in a grid of preview
 * shots. Tapping one offers to install it, which overwrites the local Custom Profile settings with
 * what the author saved.
 * <p>
 * Sections come from a picker in the title rather than a tab strip, which is how the reference
 * plugin arranges them, and only "Лучшее" carries a period.
 */
public class WorkshopActivity extends BaseFragment {

    private static final int menu_section = 1;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private FlickerLoadingView progressView;
    private TextView emptyView;
    private ActionBarMenuItem sectionItem;

    private final List<WorkshopHelper.Work> works = new ArrayList<>();

    /** Index into {@link #SECTIONS}; "Лучшее" appears three times, once per period. */
    private int section;
    /**
     * Which of the workshop's two galleries this screen shows — looks or avatar frames. Both are the
     * same endpoints, the same sections and the same grid; only what installing a work does differs.
     */
    private final String kind;

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
    };

    private static ArrayList<String> sectionNames() {
        final ArrayList<String> names = new ArrayList<>();
        names.add(getString(R.string.WorkshopSectionNew));
        names.add(getString(R.string.WorkshopSectionPopular));
        names.add(getString(R.string.WorkshopSectionBestDay));
        names.add(getString(R.string.WorkshopSectionBestWeek));
        names.add(getString(R.string.WorkshopSectionBestMonth));
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
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        sectionItem = menu.addItem(menu_section, R.drawable.msg_list);
        sectionItem.setContentDescription(getString(R.string.WorkshopSection));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        progressView = new FlickerLoadingView(context);
        progressView.setViewType(FlickerLoadingView.DIALOG_CELL_TYPE);
        progressView.showDate(false);
        root.addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        load();
        return fragmentView;
    }

    private void showSectionPicker() {
        if (getParentActivity() == null) {
            return;
        }
        PopupHelper.show(sectionNames(), getString(R.string.WorkshopSection), section,
                getParentActivity(), which -> {
                    if (which == section) {
                        return;
                    }
                    section = which;
                    actionBar.setSubtitle(sectionNames().get(section));
                    load();
                });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void load() {
        final int id = ++requestId;
        loading = true;
        works.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyState(null);
        WorkshopHelper.list(SECTIONS[section][0], SECTIONS[section][1], kind, (result, error) -> {
            if (id != requestId) {
                return;
            }
            loading = false;
            if (result != null) {
                works.addAll(result);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            // Keep the reason. The workshop lives on a plain-HTTP host on a non-standard port, so the
            // usual failure is the network refusing to reach it — "could not load" alone leaves the
            // user unable to tell a blocked port from an empty section or a server that is really down.
            updateEmptyState(result == null ? loadFailedText(error) : null);
        });
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
        listView.setVisibility(loading || empty ? View.GONE : View.VISIBLE);
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
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        // The list only carries a summary; the style itself arrives with the full record.
        WorkshopHelper.load(work, (loaded, error) -> {
            if (loaded == null) {
                progress.dismiss();
                showError(error);
                return;
            }
            final WorkshopHelper.Callback<Boolean> done0 = (done, installError) -> {
                progress.dismiss();
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
            ((WorkshopCell) holder.itemView).setWork(works.get(position));
        }

        @Override
        public int getItemCount() {
            return works.size();
        }
    }
}
