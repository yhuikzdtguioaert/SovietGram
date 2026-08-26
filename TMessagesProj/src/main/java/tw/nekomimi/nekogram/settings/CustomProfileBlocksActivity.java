package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.config.cell.ConfigCellColor;
import tw.nekomimi.nekogram.helpers.CustomProfileExtraRows;
import tw.nekomimi.nekogram.helpers.CustomProfileHelper;
import tw.nekomimi.nekogram.helpers.CustomProfileMedia;
import tw.nekomimi.nekogram.helpers.PopupHelper;

/**
 * The rows a look invents for itself — a link, a button, a heading, a line of text, a picture.
 *
 * <p>These sit at the end of the profile, above the shared media, and are not the profile's own rows
 * rearranged; {@link CustomProfileRowsActivity} does that. Two screens in one class, as with the
 * header layout: the list, and one row's own settings.
 *
 * <p>A row marked "only me" is drawn on the author's own profile and dropped for everybody else,
 * which is what makes it usable as a note to oneself rather than as something published.
 */
public class CustomProfileBlocksActivity extends CustomProfileListActivity {

    private static final int[] TYPES = {
            CustomProfileExtraRows.TYPE_LINK,
            CustomProfileExtraRows.TYPE_TEXT,
            CustomProfileExtraRows.TYPE_HEADER,
            CustomProfileExtraRows.TYPE_NOTE,
            CustomProfileExtraRows.TYPE_BUTTON,
            CustomProfileExtraRows.TYPE_DIVIDER,
            CustomProfileExtraRows.TYPE_MEDIA,
    };

    private static final int[] ACTIONS = {
            CustomProfileExtraRows.ACTION_NONE,
            CustomProfileExtraRows.ACTION_OPEN,
            CustomProfileExtraRows.ACTION_COPY,
            CustomProfileExtraRows.ACTION_SHARE,
    };

    /** −1 for the list, otherwise the row being edited. */
    private final int index;

    public CustomProfileBlocksActivity() {
        this(-1);
    }

    private CustomProfileBlocksActivity(int index) {
        this.index = index;
    }

    @Override
    protected String title() {
        return getString(index < 0 ? R.string.CustomProfileExtraRows : R.string.CustomProfileExtraRow);
    }

    @Override
    protected void buildRows() {
        if (index < 0) {
            buildList();
        } else {
            buildOne();
        }
    }

    // ---------------------------------------------------------------- the list

    private void buildList() {
        final List<CustomProfileExtraRows.Block> blocks = CustomProfileExtraRows.stored();
        header(getString(R.string.CustomProfileExtraRows));
        for (int i = 0; i < blocks.size(); i++) {
            final CustomProfileExtraRows.Block block = blocks.get(i);
            final int at = i;
            final Row row = setting(describe(block), typeName(block.type),
                    () -> presentFragment(new CustomProfileBlocksActivity(at)));
            row.onLongClick = () -> order(blocks, at);
        }
        if (blocks.isEmpty()) {
            info(getString(R.string.CustomProfileExtraRowsEmpty));
        }
        shadow();
        setting(getString(R.string.CustomProfileExtraRowAdd), null, this::add);
        info(getString(R.string.CustomProfileExtraRowsInfo));
    }

    private void add() {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        for (int type : TYPES) {
            names.add(typeName(type));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileExtraRowAdd), -1,
                getParentActivity(), picked -> {
                    final List<CustomProfileExtraRows.Block> blocks = CustomProfileExtraRows.stored();
                    blocks.add(CustomProfileExtraRows.create(TYPES[picked]));
                    CustomProfileExtraRows.store(blocks);
                    presentFragment(new CustomProfileBlocksActivity(blocks.size() - 1));
                });
    }

    private void order(List<CustomProfileExtraRows.Block> blocks, int at) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (at > 0) {
            names.add(getString(R.string.CustomProfileRowUp));
            actions.add(() -> move(blocks, at, at - 1));
        }
        if (at + 1 < blocks.size()) {
            names.add(getString(R.string.CustomProfileRowDown));
            actions.add(() -> move(blocks, at, at + 1));
        }
        names.add(getString(R.string.Delete));
        actions.add(() -> {
            blocks.remove(at);
            CustomProfileExtraRows.store(blocks);
        });
        PopupHelper.show(names, describe(blocks.get(at)), -1, getParentActivity(), picked -> {
            actions.get(picked).run();
            rebuild();
        });
    }

    private static void move(List<CustomProfileExtraRows.Block> blocks, int from, int to) {
        blocks.add(to, blocks.remove(from));
        CustomProfileExtraRows.store(blocks);
    }

    // ---------------------------------------------------------------- one row

    private void buildOne() {
        final List<CustomProfileExtraRows.Block> blocks = CustomProfileExtraRows.stored();
        if (index >= blocks.size()) {
            return;
        }
        final CustomProfileExtraRows.Block block = blocks.get(index);

        header(typeName(block.type));
        setting(getString(R.string.CustomProfileExtraRowType), typeName(block.type),
                () -> pickType(blocks, block));

        if (block.type != CustomProfileExtraRows.TYPE_DIVIDER
                && block.type != CustomProfileExtraRows.TYPE_MEDIA) {
            setting(getString(R.string.CustomProfileExtraRowTitle), preview(block.title),
                    () -> askText(getString(R.string.CustomProfileExtraRowTitle), block.title, 40,
                            value -> {
                                block.title = value;
                                CustomProfileExtraRows.store(blocks);
                            }));
        }
        if (block.type == CustomProfileExtraRows.TYPE_TEXT
                || block.type == CustomProfileExtraRows.TYPE_NOTE
                || block.type == CustomProfileExtraRows.TYPE_LINK) {
            setting(getString(R.string.CustomProfileExtraRowText), preview(block.text),
                    () -> askText(getString(R.string.CustomProfileExtraRowText), block.text, 1024,
                            value -> {
                                block.text = value;
                                CustomProfileExtraRows.store(blocks);
                            }));
        }
        if (block.type == CustomProfileExtraRows.TYPE_LINK
                || block.type == CustomProfileExtraRows.TYPE_BUTTON) {
            setting(getString(R.string.CustomProfileExtraRowUrl), preview(block.url),
                    () -> askText(getString(R.string.CustomProfileExtraRowUrl), block.url, 512,
                            value -> {
                                block.url = value;
                                CustomProfileExtraRows.store(blocks);
                            }));
            setting(getString(R.string.CustomProfileExtraRowAction), actionName(block.action),
                    () -> pickAction(blocks, block, false));
            setting(getString(R.string.CustomProfileExtraRowLongAction),
                    actionName(block.longAction), () -> pickAction(blocks, block, true));
        }
        if (block.type == CustomProfileExtraRows.TYPE_MEDIA) {
            setting(getString(R.string.CustomProfileExtraRowPick),
                    block.media.isEmpty() ? null : getString(R.string.CustomProfileExtraRowPicked),
                    () -> pickPicture(index));
            final Row media = setting(getString(R.string.CustomProfileExtraRowMedia),
                    preview(block.picture()),
                    () -> askText(getString(R.string.CustomProfileExtraRowMedia),
                            block.picture(), 512, value -> {
                                // A picture row keeps its picture in whichever of the two fields
                                // suits it: an address in the one that travels, anything else in
                                // the one that names a file. Only ever one of them, so there is no
                                // question of which the profile will draw.
                                if (travels(value)) {
                                    block.url = value;
                                    block.mediaPath = "";
                                } else {
                                    block.mediaPath = value;
                                    block.url = "";
                                }
                                // An address typed by hand replaces a picked picture, so the copy
                                // hosted for it is no longer what this row draws.
                                block.media = "";
                                CustomProfileExtraRows.store(blocks);
                            }));
            // A picture that lives on this phone and has not been uploaded draws for the author and
            // for nobody else, so the row says so rather than letting it look like it worked.
            if (block.media.isEmpty() && !block.picture().isEmpty() && !travels(block.picture())) {
                media.valueColor = org.telegram.ui.ActionBar.Theme.getColor(
                        org.telegram.ui.ActionBar.Theme.key_text_RedRegular);
                info(getString(R.string.CustomProfileExtraRowMediaLocal));
            }
            setting(getString(R.string.CustomProfileExtraRowHeight), block.mediaHeight + " dp",
                    () -> askNumber(getString(R.string.CustomProfileExtraRowHeight),
                            block.mediaHeight, 60, 400, value -> {
                                block.mediaHeight = value;
                                CustomProfileExtraRows.store(blocks);
                            }));
        }
        shadow();

        if (block.type != CustomProfileExtraRows.TYPE_DIVIDER) {
            header(getString(R.string.CustomProfileExtraRowLook));
            colorRow(R.string.CustomProfileExtraRowTitleColor, block.titleColor,
                    color -> {
                        block.titleColor = color;
                        CustomProfileExtraRows.store(blocks);
                    });
            colorRow(R.string.CustomProfileExtraRowValueColor, block.valueColor,
                    color -> {
                        block.valueColor = color;
                        CustomProfileExtraRows.store(blocks);
                    });
            if (block.type == CustomProfileExtraRows.TYPE_BUTTON) {
                colorRow(R.string.CustomProfileExtraRowFill, block.iconBackground,
                        color -> {
                            block.iconBackground = color;
                            CustomProfileExtraRows.store(blocks);
                        });
            }
            setting(getString(R.string.CustomProfileExtraRowRadius), block.radius + " dp",
                    () -> askNumber(getString(R.string.CustomProfileExtraRowRadius),
                            block.radius, 0, 48, value -> {
                                block.radius = value;
                                CustomProfileExtraRows.store(blocks);
                            }));
            shadow();
        }

        check(getString(R.string.CustomProfileExtraRowOwnOnly), block.ownOnly, () -> {
            block.ownOnly = !block.ownOnly;
            CustomProfileExtraRows.store(blocks);
            rebuild();
        });
        info(getString(R.string.CustomProfileExtraRowOwnOnlyInfo));

        setting(getString(R.string.Delete), null, () -> confirmDelete(blocks));
        shadow();
    }

    private void colorRow(int titleRes, int current, java.util.function.IntConsumer sink) {
        final Row row = setting(getString(titleRes),
                current == 0 ? getString(R.string.CustomProfilePaletteDefault)
                        : String.format(java.util.Locale.US, "#%06X", current & 0xFFFFFF),
                () -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    final int start = current == 0 ? 0xFF808080 : current;
                    ConfigCellColor.show(getParentActivity(), getString(titleRes), start, start,
                            true, color -> {
                                sink.accept(color);
                                rebuild();
                            });
                });
        if (current != 0) {
            row.valueColor = current | 0xFF000000;
        }
        // As in the palette: holding a colour puts it back to the theme's own.
        row.onLongClick = () -> {
            sink.accept(0);
            rebuild();
        };
    }

    private void pickType(List<CustomProfileExtraRows.Block> blocks,
                          CustomProfileExtraRows.Block block) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < TYPES.length; i++) {
            names.add(typeName(TYPES[i]));
            if (TYPES[i] == block.type) {
                checked = i;
            }
        }
        PopupHelper.show(names, getString(R.string.CustomProfileExtraRowType), checked,
                getParentActivity(), picked -> {
                    block.type = TYPES[picked];
                    CustomProfileExtraRows.store(blocks);
                    rebuild();
                });
    }

    private void pickAction(List<CustomProfileExtraRows.Block> blocks,
                            CustomProfileExtraRows.Block block, boolean longPress) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        final int current = longPress ? block.longAction : block.action;
        int checked = 0;
        for (int i = 0; i < ACTIONS.length; i++) {
            names.add(actionName(ACTIONS[i]));
            if (ACTIONS[i] == current) {
                checked = i;
            }
        }
        PopupHelper.show(names, getString(longPress ? R.string.CustomProfileExtraRowLongAction
                : R.string.CustomProfileExtraRowAction), checked, getParentActivity(), picked -> {
            if (longPress) {
                block.longAction = ACTIONS[picked];
            } else {
                block.action = ACTIONS[picked];
            }
            CustomProfileExtraRows.store(blocks);
            rebuild();
        });
    }

    private void confirmDelete(List<CustomProfileExtraRows.Block> blocks) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileExtraRow))
                .setMessage(getString(R.string.CustomProfileExtraRowDeleteInfo))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    if (index < blocks.size()) {
                        blocks.remove(index);
                        CustomProfileExtraRows.store(blocks);
                    }
                    finishFragment();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static final int REQUEST_PICTURE = 1620;

    /**
     * Picks a picture for a row out of the gallery and hosts it.
     *
     * <p>The gallery hands back a {@code content://} that does not survive a restart, so the bytes
     * are copied into our own storage and uploaded; the row then carries the descriptor rather than
     * a path, which is the only way somebody else's phone can ever draw it.
     */
    private void pickPicture(int at) {
        if (getParentActivity() == null) {
            return;
        }
        pickingFor = at;
        final android.content.Intent intent =
                new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICTURE);
        } catch (Exception ignore) {
        }
    }

    private int pickingFor = -1;

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQUEST_PICTURE || resultCode != android.app.Activity.RESULT_OK
                || data == null || data.getData() == null || pickingFor < 0) {
            return;
        }
        final int at = pickingFor;
        pickingFor = -1;
        final byte[] bytes = CustomProfileHelper.readUri(data.getData());
        if (bytes == null || bytes.length == 0) {
            org.telegram.ui.Components.BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.UnknownError)).show();
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final String descriptor = CustomProfileMedia.publishLoose(bytes, null);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (descriptor == null) {
                    org.telegram.ui.Components.BulletinFactory.of(this)
                            .createErrorBulletin(getString(R.string.UnknownError)).show();
                    return;
                }
                final List<CustomProfileExtraRows.Block> blocks = CustomProfileExtraRows.stored();
                if (at >= blocks.size()) {
                    return;
                }
                final CustomProfileExtraRows.Block block = blocks.get(at);
                block.media = descriptor;
                // The picked picture is what this row draws now; the other two sources would win
                // over it and are no longer what the user asked for.
                block.mediaPath = "";
                block.url = "";
                CustomProfileExtraRows.store(blocks);
                rebuild();
            });
        });
    }

    /** A plain single-line text field; the longer fields simply allow more of it. */
    private void askText(CharSequence title, String current, int max,
                         java.util.function.Consumer<String> sink) {
        if (getParentActivity() == null) {
            return;
        }
        final org.telegram.ui.Components.EditTextBoldCursor input =
                new org.telegram.ui.Components.EditTextBoldCursor(getParentActivity());
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
                org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorColor(org.telegram.ui.ActionBar.Theme.getColor(
                org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        input.setBackgroundDrawable(null);
        input.setPadding(org.telegram.messenger.AndroidUtilities.dp(4), 0,
                org.telegram.messenger.AndroidUtilities.dp(4), 0);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        input.setText(current == null ? "" : current);
        input.setSelection(input.getText().length());

        final android.widget.FrameLayout wrapper = new android.widget.FrameLayout(getParentActivity());
        wrapper.addView(input, org.telegram.ui.Components.LayoutHelper.createFrame(
                org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,
                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 22, 8, 22, 8));

        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setView(wrapper)
                .setPositiveButton(getString(R.string.Done), (dialog, which) -> {
                    sink.accept(input.getText().toString());
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    // ---------------------------------------------------------------- labels

    private static String describe(CustomProfileExtraRows.Block block) {
        if (!TextUtils.isEmpty(block.title)) {
            return block.title;
        }
        if (!TextUtils.isEmpty(block.text)) {
            return block.text;
        }
        return typeName(block.type);
    }

    /** Whether an address anybody could fetch, which is what a row's picture has to be. */
    private static boolean travels(String value) {
        final String lower = value.trim().toLowerCase(java.util.Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static String preview(String value) {
        if (TextUtils.isEmpty(value)) {
            return getString(R.string.CustomProfileExtraRowEmpty);
        }
        return value.length() > 24 ? value.substring(0, 23) + "…" : value;
    }

    private static String typeName(int type) {
        return getString(switch (type) {
            case CustomProfileExtraRows.TYPE_TEXT -> R.string.CustomProfileExtraRowTypeText;
            case CustomProfileExtraRows.TYPE_HEADER -> R.string.CustomProfileExtraRowTypeHeader;
            case CustomProfileExtraRows.TYPE_DIVIDER -> R.string.CustomProfileExtraRowTypeDivider;
            case CustomProfileExtraRows.TYPE_NOTE -> R.string.CustomProfileExtraRowTypeNote;
            case CustomProfileExtraRows.TYPE_BUTTON -> R.string.CustomProfileExtraRowTypeButton;
            case CustomProfileExtraRows.TYPE_MEDIA -> R.string.CustomProfileExtraRowTypeMedia;
            default -> R.string.CustomProfileExtraRowTypeLink;
        });
    }

    private static String actionName(int action) {
        return getString(switch (action) {
            case CustomProfileExtraRows.ACTION_OPEN -> R.string.CustomProfileExtraRowActionOpen;
            case CustomProfileExtraRows.ACTION_COPY -> R.string.CustomProfileExtraRowActionCopy;
            case CustomProfileExtraRows.ACTION_SHARE -> R.string.CustomProfileExtraRowActionShare;
            default -> R.string.CustomProfileExtraRowActionNone;
        });
    }
}
