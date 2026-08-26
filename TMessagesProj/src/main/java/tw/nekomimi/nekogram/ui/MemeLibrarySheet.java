package tw.nekomimi.nekogram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.helpers.MemeLibraryHelper;

/**
 * The picker behind the "Мемы" button in the attach menu: a searchable grid of the pictures the user
 * stashed away, tap to send.
 * <p>
 * Tags are the whole point, so the search field filters on them and the chip row narrows by
 * category. Long-pressing a cell is where the housekeeping lives — favourite, retag, delete.
 */
public class MemeLibrarySheet extends BottomSheet {

    /** Handed to {@link BaseFragment#startActivityForResult}; routed back in by the host fragment. */
    public static final int PICK_REQUEST_CODE = 1601;

    private static WeakReference<MemeLibrarySheet> awaitingPick;

    /** Decoding a thumbnail costs enough that scrolling back up should not pay for it twice. */
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(80);

    private final BaseFragment fragment;
    private final long dialogId;

    private final LinearLayout chipsLayout;
    private final RecyclerListView listView;
    private final TextView emptyView;
    private final Adapter adapter = new Adapter();

    private final int cellWidth;
    private final int thumbSide;

    private String query = "";
    private String category;
    private boolean favoritesOnly;
    private List<MemeLibraryHelper.Meme> items = new ArrayList<>();

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new MemeLibrarySheet(fragment, dialogId));
    }

    /** Called by the host fragment when the system picker comes back. */
    public static boolean onActivityResult(int requestCode, Intent data) {
        if (requestCode != PICK_REQUEST_CODE) {
            return false;
        }
        final MemeLibrarySheet sheet = awaitingPick == null ? null : awaitingPick.get();
        awaitingPick = null;
        if (sheet != null && data != null && data.getData() != null) {
            sheet.askTagsAndAdd(data.getData());
        }
        return true;
    }

    private MemeLibrarySheet(BaseFragment fragment, long dialogId) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.dialogId = dialogId;

        final Context context = fragment.getParentActivity();
        // Matches the reference plugin's metrics: three columns with dp(48) of chrome around them.
        cellWidth = (AndroidUtilities.displaySize.x - AndroidUtilities.dp(48)) / 3;
        thumbSide = cellWidth - AndroidUtilities.dp(8);

        smoothKeyboardAnimationEnabled = true;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(createHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
        root.addView(createSearchField(context),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 0, 16, 8));

        chipsLayout = new LinearLayout(context);
        chipsLayout.setOrientation(LinearLayout.HORIZONTAL);
        chipsLayout.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.addView(chipsLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32));
        root.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 0, 0, 4));

        listView = new RecyclerListView(context, fragment.getResourceProvider());
        listView.setLayoutManager(new GridLayoutManager(context, 3));
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        listView.setSelectorDrawableColor(0);
        listView.setOnItemClickListener((view, position) -> sendAt(position));
        listView.setOnItemLongClickListener((view, position) -> {
            showOptions(position);
            return true;
        });

        emptyView = new TextView(context);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);

        final FrameLayout listContainer = new FrameLayout(context);
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                (int) (AndroidUtilities.displaySize.y * 0.42f / AndroidUtilities.density)));

        setCustomView(root);
        rebuildChips();
        refresh();
    }
    private View createHeader(Context context) {
        final FrameLayout header = new FrameLayout(context);

        final TextView title = new TextView(context);
        title.setText(getString(R.string.MemeLibrary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL, 18, 0, 60, 0));

        final ImageView add = new ImageView(context);
        add.setScaleType(ImageView.ScaleType.CENTER);
        add.setImageResource(R.drawable.msg_add);
        add.setColorFilter(getThemedColor(Theme.key_dialogTextBlack));
        add.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1));
        add.setContentDescription(getString(R.string.MemeLibraryAdd));
        add.setOnClickListener(v -> pickFile());
        header.addView(add, LayoutHelper.createFrame(48, 48, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        return header;
    }

    private View createSearchField(Context context) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint(getString(R.string.MemeLibrarySearch));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        field.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18),
                getThemedColor(Theme.key_dialogSearchBackground)));
        field.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setCursorSize(AndroidUtilities.dp(18));
        field.setCursorWidth(1.5f);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                refresh();
            }
        });
        return field;
    }

    // ----------------------------------------------------------------- filters

    private void rebuildChips() {
        chipsLayout.removeAllViews();
        addChip(getString(R.string.MemeLibraryCategoryAll), category == null && !favoritesOnly, () -> {
            category = null;
            favoritesOnly = false;
        });
        addChip(getString(R.string.MemeLibraryCategoryFavorites), favoritesOnly, () -> {
            favoritesOnly = true;
            category = null;
        });
        for (String name : MemeLibraryHelper.getCategories()) {
            addChip(name, !favoritesOnly && name.equals(category), () -> {
                category = name;
                favoritesOnly = false;
            });
        }
    }

    private void addChip(String text, boolean selected, Runnable onClick) {
        final Context context = getContext();
        final TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(selected ? AndroidUtilities.bold() : Typeface.DEFAULT);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        chip.setTextColor(getThemedColor(selected ? Theme.key_featuredStickers_buttonText : Theme.key_dialogTextBlack));
        chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(15), getThemedColor(
                selected ? Theme.key_featuredStickers_addButton : Theme.key_dialogSearchBackground)));
        chip.setOnClickListener(v -> {
            onClick.run();
            rebuildChips();
            refresh();
        });
        chipsLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
    }

    private void refresh() {
        items = MemeLibraryHelper.filter(category, favoritesOnly, query);
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            if (!TextUtils.isEmpty(query)) {
                emptyView.setText(getString(R.string.MemeLibraryNothingFound));
            } else if (favoritesOnly || category != null) {
                emptyView.setText(getString(R.string.MemeLibraryNothingFound));
            } else {
                emptyView.setText(getString(R.string.MemeLibraryEmpty) + "\n\n"
                        + getString(R.string.MemeLibraryEmptyInfo));
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }
    // -------------------------------------------------------------------- add

    private void pickFile() {
        if (fragment.getParentActivity() == null) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            awaitingPick = new WeakReference<>(this);
            fragment.startActivityForResult(intent, PICK_REQUEST_CODE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The tags are what makes a saved picture findable later, so ask for them before storing it. */
    private void askTagsAndAdd(Uri uri) {
        final Context context = getContext();
        final EditTextBoldCursor tags = dialogField(context, getString(R.string.MemeLibraryTags));
        final EditTextBoldCursor cat = dialogField(context, getString(R.string.MemeLibraryCategory));
        cat.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(MemeLibraryHelper.MAX_CATEGORY_LENGTH)});

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);
        layout.addView(tags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        layout.addView(cat, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 12, 0, 0));

        new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getString(R.string.MemeLibraryAdd))
                .setView(layout)
                .setPositiveButton(getString(R.string.MemeLibrarySave), (d, which) -> Utilities.globalQueue.postRunnable(() -> {
                    final MemeLibraryHelper.Meme added = MemeLibraryHelper.addFromUri(uri,
                            tags.getText().toString(), cat.getText().toString());
                    AndroidUtilities.runOnUIThread(() -> {
                        if (added == null) {
                            org.telegram.ui.Components.BulletinFactory.global()
                                    .createErrorBulletin(getString(R.string.MemeLibrarySaveFailed)).show();
                            return;
                        }
                        rebuildChips();
                        refresh();
                    });
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private EditTextBoldCursor dialogField(Context context, String hint) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint(hint);
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        field.setSingleLine(true);
        field.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10),
                getThemedColor(Theme.key_dialogSearchBackground)));
        field.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        field.setCursorSize(AndroidUtilities.dp(18));
        field.setCursorWidth(1.5f);
        return field;
    }

    // ------------------------------------------------------------ send / edit

    private void sendAt(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        MemeLibraryHelper.send(AccountInstance.getInstance(currentAccount), dialogId,
                Collections.singletonList(items.get(position)));
        dismiss();
    }

    private void showOptions(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final MemeLibraryHelper.Meme meme = items.get(position);
        final CharSequence[] options = {
                getString(meme.favorite ? R.string.MemeLibraryUnfavorite : R.string.MemeLibraryFavorite),
                getString(R.string.MemeLibraryEdit),
                getString(R.string.MemeLibraryDelete)
        };
        new AlertDialog.Builder(getContext(), fragment.getResourceProvider())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        MemeLibraryHelper.toggleFavorite(meme);
                        refresh();
                    } else if (which == 1) {
                        askRetag(meme);
                    } else {
                        MemeLibraryHelper.remove(meme);
                        THUMBS.remove(meme.filepath);
                        refresh();
                    }
                })
                .show();
    }

    private void askRetag(MemeLibraryHelper.Meme meme) {
        final Context context = getContext();
        final EditTextBoldCursor tags = dialogField(context, getString(R.string.MemeLibraryTags));
        tags.setText(meme.tags);
        final EditTextBoldCursor cat = dialogField(context, getString(R.string.MemeLibraryCategory));
        cat.setText(meme.category);
        cat.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(MemeLibraryHelper.MAX_CATEGORY_LENGTH)});

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);
        layout.addView(tags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        layout.addView(cat, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 12, 0, 0));

        new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getString(R.string.MemeLibraryEdit))
                .setView(layout)
                .setPositiveButton(getString(R.string.MemeLibrarySave), (d, which) -> {
                    MemeLibraryHelper.update(meme, tags.getText().toString(), cat.getText().toString());
                    rebuildChips();
                    refresh();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
    // ------------------------------------------------------------------ grid

    private class Cell extends FrameLayout {

        private final ImageView image = new ImageView(getContext());
        private final ImageView play = new ImageView(getContext());
        private final TextView label = new TextView(getContext());
        private String boundPath;

        Cell(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8),
                    getThemedColor(Theme.key_dialogSearchBackground)));
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(8));
                }
            });
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, thumbSide / AndroidUtilities.density,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

            play.setImageResource(R.drawable.play_mini_video);
            play.setScaleType(ImageView.ScaleType.CENTER);
            addView(play, LayoutHelper.createFrame(20, 20, Gravity.BOTTOM | Gravity.END, 0, 0, 5, 5));

            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 16,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 6, 0, 6, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(thumbSide + AndroidUtilities.dp(28), MeasureSpec.EXACTLY));
        }

        void bind(MemeLibraryHelper.Meme meme) {
            boundPath = meme.filepath;
            label.setText(TextUtils.isEmpty(meme.tags) ? meme.filename : meme.tags);
            play.setVisibility(meme.isVideo() ? VISIBLE : GONE);

            final Bitmap cached = THUMBS.get(meme.filepath);
            if (cached != null && !cached.isRecycled()) {
                image.setImageBitmap(cached);
                return;
            }
            image.setImageDrawable(null);
            final String wanted = meme.filepath;
            Utilities.globalQueue.postRunnable(() -> {
                final Bitmap bitmap = MemeLibraryHelper.thumbnail(meme, thumbSide);
                AndroidUtilities.runOnUIThread(() -> {
                    if (bitmap == null) {
                        return;
                    }
                    THUMBS.put(wanted, bitmap);
                    // The cell may have been recycled onto another meme while we were decoding.
                    if (wanted.equals(boundPath)) {
                        image.setImageBitmap(bitmap);
                    }
                });
            });
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new Cell(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Cell) holder.itemView).bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }
    }
}


