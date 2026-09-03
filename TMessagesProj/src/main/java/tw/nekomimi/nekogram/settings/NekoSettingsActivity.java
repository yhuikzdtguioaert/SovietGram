package tw.nekomimi.nekogram.settings;

import static android.view.View.OVER_SCROLL_NEVER;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.Cells.SettingsSearchCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.SettingsHelper;
import tw.nekomimi.nekogram.helpers.SettingsSearchResult;

public class NekoSettingsActivity extends BaseNekoSettingsActivity {

    private static final int MENU_SEARCH = 1;

    private int generalRow;
    private int translatorRow;
    private int chatRow;
    private int experimentRow;
    private int bypassBlockingRow;
    private int sovietGramExclusiveRow;
    private int categoriesEndRow;

    private int appRestartRow;
    private int nSettingsEndRow;


    private int aboutRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        generalRow = addRow();
        translatorRow = addRow();
        chatRow = addRow();
        experimentRow = addRow();
        bypassBlockingRow = addRow();
        sovietGramExclusiveRow = addRow();
        categoriesEndRow = addRow();

        appRestartRow = addRow();
        nSettingsEndRow = addRow();

        aboutRow = addRow();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, resourcesProvider);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SEARCH) {
                    showSettingsSearchDialog();
                }
            }
        });

        return view;
    }

    /**
     * @noinspection SizeReplaceableByIsEmpty
     */
    private void showSettingsSearchDialog() {
        try {
            Activity parent = getParentActivity();
            if (parent == null) return;

            ArrayList<SettingsSearchResult> results = SettingsHelper.onCreateSearchArray(fragment -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    presentFragment(fragment);
                } catch (Exception ignore) {
                }
            }));

            final ArrayList<SettingsSearchResult> filtered = new ArrayList<>(results);
            final String[] currentQuery = new String[]{""};
            final int searchHeight = dp(36);
            final int clearSize = dp(36);
            final int pad = dp(12);

            LinearLayout containerLayout = new LinearLayout(parent);
            containerLayout.setOrientation(LinearLayout.VERTICAL);
            containerLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            FrameLayout searchFrame = new FrameLayout(parent);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, searchHeight + dp(12));
            layoutParams.leftMargin = dp(10);
            layoutParams.rightMargin = dp(10);
            layoutParams.topMargin = dp(6);
            layoutParams.bottomMargin = dp(2);
            searchFrame.setLayoutParams(layoutParams);
            searchFrame.setClipToPadding(true);
            searchFrame.setClipChildren(true);

            ImageView searchIcon = new ImageView(parent);
            searchIcon.setScaleType(ImageView.ScaleType.CENTER);
            searchIcon.setImageResource(R.drawable.ic_ab_search_solar);
            searchIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            searchFrame.addView(searchIcon, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            EditTextBoldCursor searchField = new EditTextBoldCursor(parent);
            searchField.setHint(getString(R.string.Search));
            searchField.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
            searchField.setSingleLine(true);
            searchField.setBackground(null);
            searchField.setInputType(InputType.TYPE_CLASS_TEXT);
            searchField.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField), getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated), getThemedColor(Theme.key_text_RedRegular));
            searchField.setPadding(dp(61), pad / 2, dp(48), pad / 2);
            searchField.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));
            searchFrame.addView(searchField);

            ImageView clearButton = new ImageView(parent);
            clearButton.setScaleType(ImageView.ScaleType.CENTER);
            clearButton.setImageResource(R.drawable.ic_close_white);
            clearButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            clearButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            clearButton.setLayoutParams(new FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END | Gravity.CENTER_VERTICAL));
            searchFrame.addView(clearButton);
            containerLayout.addView(searchFrame);

            AlertDialog.Builder builder = new AlertDialog.Builder(parent, resourceProvider);
            builder.setView(containerLayout);
            builder.setNegativeButton(getString(R.string.Close), null);
            final AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                try {
                    searchField.requestFocus();
                    AndroidUtilities.showKeyboard(searchField);
                } catch (Exception ignore) {
                }
            });

            RecyclerListView searchListView = new RecyclerListView(parent);
            searchListView.setOverScrollMode(OVER_SCROLL_NEVER);
            searchListView.setLayoutManager(new LinearLayoutManager(parent, LinearLayoutManager.VERTICAL, false));

            var adapter = new RecyclerListView.SelectionAdapter() {
                @Override
                public boolean isEnabled(RecyclerView.ViewHolder holder) {
                    return true;
                }

                @NonNull
                @Override
                public RecyclerListView.Holder onCreateViewHolder(@NonNull ViewGroup parent1, int viewType) {
                    View view = new SettingsSearchCell(parent);
                    view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new RecyclerListView.Holder(view);
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    SettingsSearchCell cell = (SettingsSearchCell) holder.itemView;
                    SettingsSearchResult r = filtered.get(position);
                    String[] path = r.path2 != null ? new String[]{r.path1, r.path2} : new String[]{r.path1};
                    CharSequence titleToSet = r.searchTitle == null ? "" : r.searchTitle;
                    String q = currentQuery[0];
                    if (q != null && !q.isEmpty() && titleToSet.length() > 0) {
                        SpannableStringBuilder ss = new SpannableStringBuilder(titleToSet);
                        String lower = titleToSet.toString().toLowerCase();
                        String[] parts = q.split("\\s+");
                        int highlightColor = getThemedColor(Theme.key_windowBackgroundWhiteBlueText4);
                        for (String p : parts) {
                            if (p.isEmpty()) continue;
                            int idx = 0;
                            while (true) {
                                int found = lower.indexOf(p, idx);
                                if (found == -1) break;
                                try {
                                    ss.setSpan(new ForegroundColorSpan(highlightColor), found, found + p.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                } catch (Exception ignore) {
                                }
                                idx = found + p.length();
                            }
                        }
                        titleToSet = ss;
                    }
                    cell.setTextAndValueAndIcon(titleToSet, path, r.iconResId, position < filtered.size() - 1);
                }

                @Override
                public int getItemCount() {
                    return filtered.size();
                }
            };

            searchListView.setAdapter(adapter);
            searchListView.setOnItemClickListener((v, position) -> {
                if (position < 0 || position >= filtered.size()) return;
                SettingsSearchResult r = filtered.get(position);
                try {
                    if (r.openRunnable != null) r.openRunnable.run();
                } catch (Exception ignore) {
                }
                dialog.dismiss();
            });

            containerLayout.addView(searchListView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            searchField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @SuppressLint("NotifyDataSetChanged")
                @Override
                public void afterTextChanged(Editable s) {
                    String q = s.toString().toLowerCase().trim();
                    currentQuery[0] = q;
                    filtered.clear();
                    if (q.isEmpty()) {
                        filtered.addAll(results);
                    } else {
                        String[] parts = q.split("\\s+");
                        for (SettingsSearchResult item : results) {
                            String title = item.searchTitle == null ? "" : item.searchTitle.toLowerCase();
                            boolean ok = true;
                            for (String p : parts) {
                                if (!title.contains(p)) {
                                    ok = false;
                                    break;
                                }
                            }
                            if (ok) filtered.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    searchIcon.setVisibility(q.length() > 20 ? View.GONE : View.VISIBLE);
                    clearButton.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });

            clearButton.setOnClickListener(v -> {
                searchField.setText("");
                searchField.requestFocus();
                AndroidUtilities.showKeyboard(searchField);
            });
            clearButton.setVisibility(View.GONE);

            showDialog(dialog);
        } catch (Exception ignore) {
        }
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.NekoSettings);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == chatRow) {
            presentFragment(new NekoChatSettingsActivity());
        } else if (position == generalRow) {
            presentFragment(new NekoGeneralSettingsActivity());
        } else if (position == experimentRow) {
            presentFragment(new NekoExperimentalSettingsActivity());
        } else if (position == bypassBlockingRow) {
            presentFragment(new BypassBlockingActivity());
        } else if (position == sovietGramExclusiveRow) {
            presentFragment(new SovietGramExclusiveActivity());
        } else if (position == translatorRow) {
            presentFragment(new NekoTranslatorSettingsActivity());
        } else if (position == aboutRow) {
            presentFragment(new NekoAboutActivity());
        } else if (position == appRestartRow) {
            AppRestartHelper.triggerRebirth(getParentActivity(), new Intent(getParentActivity(), LaunchActivity.class));
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case TYPE_SHADOW: {
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_TEXT: {
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == chatRow) {
                        textCell.setTextAndIcon(getString(R.string.Chat), R.drawable.msg_discussion, true);
                    } else if (position == generalRow) {
                        textCell.setTextAndIcon(getString(R.string.General), R.drawable.msg_theme, true);
                    } else if (position == translatorRow) {
                        textCell.setTextAndIcon(getString(R.string.TranslatorSettings), R.drawable.ic_translate, true);
                    } else if (position == experimentRow) {
                        textCell.setTextAndIcon(getString(R.string.Experimental), R.drawable.msg_fave, true);
                    } else if (position == bypassBlockingRow) {
                        // The int overload is the one every other row here uses: it pins the icon to
                        // imageLeft = 16 with a 7dp top padding. The Drawable overload moves it to
                        // imageLeft = 18 with 6dp, which is why this icon used to sit further right
                        // and higher than its neighbours. The colour filter still has to go, though —
                        // this icon is multi-colour and the default MULTIPLY tint flattens the green
                        // tick into the same grey as the globe.
                        textCell.setTextAndIcon(getString(R.string.BypassBlocking), R.drawable.sovietgram_bypass_blocking, true);
                        textCell.imageView.setColorFilter(null);
                    } else if (position == sovietGramExclusiveRow) {
                        textCell.setTextAndIcon(getString(R.string.SovietGramExclusive), R.drawable.sovietgram_exclusive, true);
                        // The bypass row above binds a multi-colour Drawable, and that overload
                        // clears the icon colour filter. A recycled holder would carry the null
                        // filter over here and render the diamond white, so re-apply the tint the
                        // other category icons use.
                        textCell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                    } else if (position == appRestartRow) {
                        textCell.setTextAndIcon(getString(R.string.RestartApp), R.drawable.msg_retry_solar, true);
                    } else if (position == aboutRow) {
                        textCell.setTextAndIcon(getString(R.string.About), R.drawable.msg_info, true);
                    }
                    break;
                }

            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == categoriesEndRow || position == nSettingsEndRow) {
                return TYPE_SHADOW;
            } else if (position == chatRow || position == generalRow || position == experimentRow || position == bypassBlockingRow || position == sovietGramExclusiveRow || position == translatorRow ||
                    position == appRestartRow || position == aboutRow) {
                return TYPE_TEXT;
            }
            return TYPE_SHADOW;
        }
    }
}
