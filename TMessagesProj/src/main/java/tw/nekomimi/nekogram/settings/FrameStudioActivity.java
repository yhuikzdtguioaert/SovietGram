package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.config.cell.ConfigCellColor;
import tw.nekomimi.nekogram.helpers.CustomProfileFrame;
import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.frame.FrameBlanks;
import tw.nekomimi.nekogram.helpers.frame.FrameGraph;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphBuild;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphLayers;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphStore;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphType;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.ui.frame.FrameNodeCanvasView;
import tw.nekomimi.nekogram.ui.frame.FrameStudioPreviewView;

/**
 * The frame editor: a stack of decorations on one tab, the graph they really are on the other.
 *
 * <p>There is one document behind both tabs — the node graph — and the list is a view onto it, not a
 * model of its own. Adding a decoration adds a node; dragging a slider writes a knob. That is why a
 * frame drawn here can then be rewired by hand on the canvas without anything being converted, and
 * why a frame built on the canvas shows up in the list as long as it is shaped like a list.
 *
 * <p>Every edit recompiles the graph into the {@link FrameSpec} the profile draws, and both are saved
 * together. The spec is what travels to other users; the graph stays on this device.
 *
 * <p>The reference's studio can also publish to the workshop. That is deliberately not here — a frame
 * made here is worn, not sold.
 */
public class FrameStudioActivity extends CustomProfileListActivity {

    private static final int menu_reset = 1;
    private static final int menu_shape = 2;
    private static final int menu_canvas = 3;
    private static final int menu_drop = 4;

    private static final int TAB_LAYERS = 0;
    private static final int TAB_NODES = 1;

    /** The outlines the stand-in avatar can wear, so a frame can be tried on all of them. */
    private static final int[] SHAPE_NAMES = {
            R.string.CustomProfileShapeCircle,
            R.string.CustomProfileShapeRounded,
            R.string.CustomProfileShapeSquare,
            R.string.CustomProfileShapeHexagon,
            R.string.CustomProfileShapePentagon,
            R.string.CustomProfileShapeStar,
            R.string.CustomProfileShapeHeart,
            R.string.CustomProfileShapeFlower,
    };

    private FrameGraph graph = FrameGraph.empty();
    private FrameSpec spec = FrameSpec.EMPTY;

    /** The decoration whose settings are open, as its shape node's id. */
    private int selected;
    private int tab = TAB_LAYERS;

    private FrameStudioPreviewView preview;
    private FrameNodeCanvasView canvas;
    private LinearLayout tabStrip;

    @Override
    protected String title() {
        return getString(R.string.CustomProfileFrameStudio);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Whatever was still only in memory — the tail of a slider drag, a node dragged across the
        // board — is written out before this screen stops being the one in front.
        FrameGraphStore.save(graph);
    }

    @Override
    public boolean onFragmentCreate() {
        graph = FrameGraphStore.graph();
        recompile(false);
        return super.onFragmentCreate();
    }

    // ---------------------------------------------------------------- the document

    /**
     * Rebuilds the frame from the graph and, unless told otherwise, saves both.
     *
     * <p>Saved on every edit rather than behind a button: there is nothing here that is not already
     * visible on the profile behind this screen, and a frame half-saved is not a useful state.
     */
    private void recompile(boolean save) {
        spec = FrameGraphBuild.spec(graph);
        if (save) {
            FrameGraphStore.save(graph);
        }
        if (preview != null) {
            preview.setSpec(spec);
        }
        if (canvas != null) {
            canvas.setGraph(graph);
        }
    }

    private void edited() {
        recompile(true);
        rebuild();
    }

    // ---------------------------------------------------------------- the screen

    @Nullable
    @Override
    protected View createHeader(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        preview = new FrameStudioPreviewView(context);
        preview.setSpec(spec);
        column.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        tabStrip = new LinearLayout(context);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.addView(tabButton(context, TAB_LAYERS, R.string.CustomProfileFrameTabLayers),
                LayoutHelper.createLinear(0, 44, 1f));
        tabStrip.addView(tabButton(context, TAB_NODES, R.string.CustomProfileFrameTabNodes),
                LayoutHelper.createLinear(0, 44, 1f));
        column.addView(tabStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        return column;
    }

    private View tabButton(Context context, int which, int titleRes) {
        final org.telegram.ui.Cells.TextCell cell = new org.telegram.ui.Cells.TextCell(context);
        cell.setTextAndIcon(getString(titleRes), 0, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            if (tab == which) {
                return;
            }
            tab = which;
            updateTabs();
        });
        return cell;
    }

    private void updateTabs() {
        if (tabStrip != null) {
            for (int i = 0; i < tabStrip.getChildCount(); i++) {
                final View child = tabStrip.getChildAt(i);
                child.setAlpha(i == tab ? 1f : 0.5f);
            }
        }
        if (listView != null) {
            listView.setVisibility(tab == TAB_LAYERS ? View.VISIBLE : View.GONE);
        }
        if (canvas != null) {
            canvas.setVisibility(tab == TAB_NODES ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public View createView(Context context) {
        final View root = super.createView(context);

        final ActionBarMenu menu = actionBar.createMenu();
        final ActionBarMenuItem more = menu.addItem(0, R.drawable.ic_ab_other);
        more.addSubItem(menu_shape, R.drawable.msg_photo_settings,
                getString(R.string.CustomProfileFrameShape));
        more.addSubItem(menu_canvas, R.drawable.msg_settings,
                getString(R.string.CustomProfileFrameCanvasSettings));
        more.addSubItem(menu_drop, R.drawable.msg_delete,
                getString(R.string.CustomProfileFrameDropChosen));
        more.addSubItem(menu_reset, R.drawable.msg_delete, getString(R.string.CustomProfileFrameClear));
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == menu_shape) {
                    pickShape();
                } else if (id == menu_canvas) {
                    FrameCanvasSettingsSheet.show(FrameStudioActivity.this, () -> {
                        if (canvas != null) {
                            canvas.applySkin();
                        }
                    });
                } else if (id == menu_drop) {
                    dropChosen();
                } else if (id == menu_reset) {
                    confirmClear();
                }
            }
        });

        // The canvas shares the space the list occupies rather than sitting beside it: they are two
        // views of the same document and only one is ever wanted at a time.
        if (listView != null && listView.getParent() instanceof LinearLayout column) {
            canvas = new FrameNodeCanvasView(context);
            canvas.setGraph(graph);
            canvas.setListener(new FrameNodeCanvasView.Listener() {
                @Override
                public void onGraphChanged(boolean recompile, boolean settled) {
                    if (recompile) {
                        recompile(settled);
                    } else if (settled) {
                        FrameGraphStore.save(graph);
                    }
                }

                @Override
                public void onKnobTapped(int node, int knob) {
                    // Tapping a knob on the canvas opens the same editor the layers tab uses; a
                    // colour or a picture cannot be dragged, and a number is easier typed exactly.
                    knobSheet(node, knob);
                }

                @Override
                public void onAddNode(float x, float y) {
                    FrameNodePickerSheet.show(FrameStudioActivity.this, type -> {
                        final int id = graph.add(type, Math.round(x), Math.round(y));
                        if (id <= 0) {
                            BulletinFactory.of(FrameStudioActivity.this)
                                    .createErrorBulletin(getString(R.string.CustomProfileFrameFull))
                                    .show();
                            return;
                        }
                        recompile(true);
                        canvas.invalidate();
                    });
                }
            });
            canvas.setVisibility(View.GONE);
            column.addView(canvas, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }
        updateTabs();
        return root;
    }

    private void pickShape() {
        if (getParentActivity() == null || preview == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        for (int name : SHAPE_NAMES) {
            names.add(getString(name));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileFrameShape), preview.getShape(),
                getParentActivity(), picked -> preview.setShape(picked));
    }

    /**
     * Removes whatever is picked out on the canvas. Long-pressing a card picks it out; there is no
     * other way to delete a node, and a node that has been deleted by mistake is one undo the graph
     * does not have, so it says how many are going.
     */
    private void dropChosen() {
        if (canvas == null || getParentActivity() == null) {
            return;
        }
        final int count = canvas.chosenCount();
        if (count == 0) {
            BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.CustomProfileFrameNothingChosen)).show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileFrameDropChosen))
                .setMessage(org.telegram.messenger.LocaleController.formatString(
                        R.string.CustomProfileFrameDropChosenInfo, count))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    canvas.dropChosen();
                    selected = 0;
                    selectedEffect = 0;
                    recompile(true);
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileFrameClear))
                .setMessage(getString(R.string.CustomProfileFrameClearInfo))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    FrameGraphStore.clear();
                    graph = FrameGraphStore.graph();
                    selected = 0;
                    recompile(false);
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    // ---------------------------------------------------------------- the layers tab

    @Override
    protected void buildRows() {
        final List<Integer> shapes = FrameGraphLayers.shapes(graph);
        if (shapes.isEmpty()) {
            buildEmpty();
            return;
        }
        if (selected == 0 || !shapes.contains(selected)) {
            selected = shapes.get(0);
        }

        header(getString(R.string.CustomProfileFrameLayers));
        for (int i = 0; i < shapes.size(); i++) {
            final int id = shapes.get(i);
            final int at = i;
            final Row row = setting(layerName(id), pictureName(id), () -> {
                selected = id;
                rebuild();
            });
            if (id == selected) {
                row.valueColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            }
            row.onLongClick = () -> layerMenu(id, at, shapes.size());
        }
        setting(getString(R.string.CustomProfileFrameAddLayer), null, this::addLayer);
        shadow();

        buildLayer(selected);
    }

    /** The four presets the reference offers when there is nothing to edit yet. */
    private void buildEmpty() {
        header(getString(R.string.CustomProfileFrameStart));
        setting(getString(R.string.CustomProfileFrameStartRim), null,
                () -> start(FrameGraphType.RIM, FrameBlanks.RIM));
        setting(getString(R.string.CustomProfileFrameStartPattern), null,
                () -> start(FrameGraphType.PATTERN, FrameBlanks.DOT));
        setting(getString(R.string.CustomProfileFrameStartMark), null,
                () -> start(FrameGraphType.MARK, FrameBlanks.STAR));
        setting(getString(R.string.CustomProfileFrameStartSparks), null,
                () -> start(FrameGraphType.PARTICLES, FrameBlanks.SPARK));
        info(getString(R.string.CustomProfileFrameStartInfo));
    }

    private void start(int type, String picture) {
        selected = FrameGraphLayers.add(graph, type, picture);
        edited();
    }

    private void addLayer() {
        if (getParentActivity() == null) {
            return;
        }
        final int[] kinds = {FrameGraphType.RIM, FrameGraphType.PATTERN, FrameGraphType.MARK,
                FrameGraphType.STICKER, FrameGraphType.PARTICLES};
        final ArrayList<String> names = new ArrayList<>();
        for (int kind : kinds) {
            names.add(nodeName(kind));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileFrameAddLayer), -1,
                getParentActivity(), picked -> {
                    final int id = FrameGraphLayers.add(graph, kinds[picked], FrameBlanks.RIM);
                    if (id <= 0) {
                        BulletinFactory.of(this)
                                .createErrorBulletin(getString(R.string.CustomProfileFrameFull)).show();
                        return;
                    }
                    selected = id;
                    edited();
                });
    }

    private void layerMenu(int id, int at, int count) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (at > 0) {
            names.add(getString(R.string.CustomProfileRowUp));
            actions.add(() -> FrameGraphLayers.move(graph, id, true));
        }
        if (at + 1 < count) {
            names.add(getString(R.string.CustomProfileRowDown));
            actions.add(() -> FrameGraphLayers.move(graph, id, false));
        }
        names.add(getString(R.string.Delete));
        actions.add(() -> {
            FrameGraphLayers.remove(graph, id);
            selected = 0;
        });
        PopupHelper.show(names, layerName(id), -1, getParentActivity(), picked -> {
            actions.get(picked).run();
            edited();
        });
    }

    /** One decoration's own knobs, and the effects stacked on it. */
    private void buildLayer(int id) {
        final FrameGraph.Node node = graph.node(id);
        final FrameGraphType.Kind kind = node == null ? null : FrameGraphType.of(node.type);
        if (node == null || kind == null) {
            return;
        }
        header(nodeName(node.type));
        setting(getString(R.string.CustomProfileFramePicture), pictureName(id),
                () -> FramePictureSheet.show(this, spec, picked -> {
                    FrameGraphLayers.picture(graph, id, picked);
                    edited();
                }));
        knobRows(node, kind);
        shadow();

        header(getString(R.string.CustomProfileFrameEffects));
        final List<Integer> edits = FrameGraphLayers.edits(graph, id);
        for (int editId : edits) {
            final FrameGraph.Node effect = graph.node(editId);
            if (effect == null) {
                continue;
            }
            final Row row = setting(nodeName(effect.type),
                    getString(editId == selectedEffect
                            ? R.string.CustomProfileFrameEffectOpen
                            : R.string.CustomProfileFrameEffectClosed),
                    () -> effectSheet(editId));
            row.onLongClick = () -> {
                FrameGraphLayers.detach(graph, editId);
                if (selectedEffect == editId) {
                    selectedEffect = 0;
                }
                edited();
            };
            if (editId == selectedEffect) {
                final FrameGraphType.Kind effectKind = FrameGraphType.of(effect.type);
                if (effectKind != null) {
                    knobRows(effect, effectKind);
                }
            }
        }
        setting(getString(R.string.CustomProfileFrameAddEffect), null, () -> addEffect(id));
        info(getString(R.string.CustomProfileFrameEffectsInfo));
    }

    /**
     * One knob, edited off the canvas: a colour opens the picker, a picture opens the picture sheet,
     * a choice opens a menu and a number is typed.
     */
    private void knobSheet(int id, int index) {
        final FrameGraph.Node node = graph.node(id);
        final FrameGraphType.Kind kind = node == null ? null : FrameGraphType.of(node.type);
        if (node == null || kind == null || index < 0 || index >= kind.knobs()
                || getParentActivity() == null) {
            return;
        }
        final FrameGraphType.Knob knob = kind.knobs[index];
        final String label = knobName(kind, index);
        final int value = node.value(index);
        switch (knob.kind) {
            case FrameGraphType.KNOB_COLOR -> ConfigCellColor.show(getParentActivity(), label,
                    value, value, false, color -> {
                        graph.set(id, index, color);
                        recompile(true);
                        if (canvas != null) {
                            canvas.invalidate();
                        }
                    });
            case FrameGraphType.KNOB_IMAGE -> FramePictureSheet.show(this, spec, picked -> {
                graph.set(id, index, picked);
                recompile(true);
                if (canvas != null) {
                    canvas.invalidate();
                }
            });
            case FrameGraphType.KNOB_CHOICE -> {
                final ArrayList<String> names = new ArrayList<>();
                for (int option = 0; option < knob.options; option++) {
                    names.add(choiceName(kind, index, option));
                }
                PopupHelper.show(names, label, value, getParentActivity(), picked -> {
                    graph.set(id, index, picked);
                    recompile(true);
                    if (canvas != null) {
                        canvas.invalidate();
                    }
                });
            }
            default -> askNumber(label, value,
                    knob.kind == FrameGraphType.KNOB_NUMBER ? -720 : knob.min,
                    knob.kind == FrameGraphType.KNOB_NUMBER ? 720 : knob.max, picked -> {
                        graph.set(id, index, picked);
                        recompile(true);
                        if (canvas != null) {
                            canvas.invalidate();
                        }
                    });
        }
    }

    /** An effect has at most two knobs, so it opens in place rather than on a screen of its own. */
    private void effectSheet(int id) {
        selectedEffect = selectedEffect == id ? 0 : id;
        rebuild();
    }

    private int selectedEffect;

    private void addEffect(int shape) {
        if (getParentActivity() == null) {
            return;
        }
        final FrameGraph.Node node = graph.node(shape);
        final boolean particles = node != null && node.type == FrameGraphType.PARTICLES;
        final List<Integer> kinds = new ArrayList<>();
        // The particle effects are meaningless on anything that is not a cloud, so they are only
        // offered where they do something.
        if (particles) {
            kinds.add(FrameGraphType.FLOW);
            kinds.add(FrameGraphType.SCATTER);
            kinds.add(FrameGraphType.TURBULENCE);
            kinds.add(FrameGraphType.GRAVITY);
            kinds.add(FrameGraphType.SPIN);
            kinds.add(FrameGraphType.TWINKLE);
            kinds.add(FrameGraphType.JITTER);
        }
        kinds.add(FrameGraphType.TINT);
        kinds.add(FrameGraphType.FADE);
        kinds.add(FrameGraphType.PLACE);
        kinds.add(FrameGraphType.TURN);
        kinds.add(FrameGraphType.SIZE);
        kinds.add(FrameGraphType.TRANSFORM);
        final ArrayList<String> names = new ArrayList<>();
        for (int kind : kinds) {
            names.add(nodeName(kind));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileFrameAddEffect), -1,
                getParentActivity(), picked -> {
                    final int id = FrameGraphLayers.attach(graph, shape, kinds.get(picked));
                    if (id > 0) {
                        selectedEffect = id;
                    }
                    edited();
                });
    }

    /** One row per knob: a bar for a range, a chooser for a choice, a sheet for a colour. */
    private void knobRows(FrameGraph.Node node, FrameGraphType.Kind kind) {
        for (int i = 0; i < kind.knobs(); i++) {
            final FrameGraphType.Knob knob = kind.knobs[i];
            final int index = i;
            final int value = node.value(i);
            final String label = knobName(kind, i);
            switch (knob.kind) {
                case FrameGraphType.KNOB_COLOR -> {
                    final Row row = setting(label,
                            String.format(java.util.Locale.US, "#%06X", value & 0xFFFFFF), () -> {
                                if (getParentActivity() == null) {
                                    return;
                                }
                                ConfigCellColor.show(getParentActivity(), label, value, value, false,
                                        color -> {
                                            graph.set(node.id, index, color);
                                            edited();
                                        });
                            });
                    row.valueColor = value | 0xFF000000;
                }
                case FrameGraphType.KNOB_CHOICE -> setting(label,
                        choiceName(kind, index, value), () -> {
                            if (getParentActivity() == null) {
                                return;
                            }
                            final ArrayList<String> names = new ArrayList<>();
                            for (int option = 0; option < knob.options; option++) {
                                names.add(choiceName(kind, index, option));
                            }
                            PopupHelper.show(names, label, value, getParentActivity(), picked -> {
                                graph.set(node.id, index, picked);
                                edited();
                            });
                        });
                case FrameGraphType.KNOB_IMAGE -> {
                    // The picture is edited by the row above; a second one would only confuse.
                }
                case FrameGraphType.KNOB_NUMBER -> setting(label, String.valueOf(value),
                        () -> askNumber(label, value, -720, 720, picked -> {
                            graph.set(node.id, index, picked);
                            recompile(true);
                        }));
                // Recompiled but neither rebuilt nor saved while the finger is down: the list must
                // not jump under it, and writing the graph out sixty times a second would repaint
                // every open profile with it.
                default -> slider(label, value, knob.min, knob.max, null, picked -> {
                    graph.set(node.id, index, picked);
                    recompile(false);
                }, () -> recompile(true));
            }
        }
    }

    // ---------------------------------------------------------------- labels

    private String layerName(int id) {
        final FrameGraph.Node node = graph.node(id);
        return node == null ? "" : nodeName(node.type);
    }

    private String pictureName(int id) {
        final String src = FrameGraphLayers.picture(graph, id);
        if (src == null || src.isEmpty()) {
            return getString(R.string.CustomProfileExtraRowEmpty);
        }
        if (FrameBlanks.is(src)) {
            return getString("CustomProfileFrameBlank_" + FrameBlanks.name(src));
        }
        final int slash = src.lastIndexOf('/');
        final String name = slash < 0 ? src : src.substring(slash + 1);
        return name.length() > 20 ? name.substring(0, 19) + "…" : name;
    }

    static String nodeName(int type) {
        return getString("FrameNode" + FrameGraphType.slug(type));
    }

    static String nodeHint(int type) {
        return getString("FrameNode" + FrameGraphType.slug(type) + "Info");
    }

    static String knobName(FrameGraphType.Kind kind, int knob) {
        return getString("FrameKnob" + kind.slug + kind.knobs[knob].slug);
    }

    static String choiceName(FrameGraphType.Kind kind, int knob, int option) {
        return getString("FrameKnob" + kind.slug + kind.knobs[knob].slug + "Option" + option);
    }
}
