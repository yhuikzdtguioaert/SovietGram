package tw.nekomimi.nekogram.ui.frame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.CustomProfileFrame;
import tw.nekomimi.nekogram.helpers.frame.FrameCanvasSkin;
import tw.nekomimi.nekogram.helpers.frame.FrameCanvasThemes;
import tw.nekomimi.nekogram.helpers.frame.FrameContour;
import tw.nekomimi.nekogram.helpers.frame.FrameGraph;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphBuild;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphPanel;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphType;
import tw.nekomimi.nekogram.helpers.frame.FrameOutline;
import tw.nekomimi.nekogram.helpers.frame.FramePainter;
import tw.nekomimi.nekogram.helpers.frame.FrameShape;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.helpers.frame.FrameStage;

/**
 * The node canvas: the frame as the graph it really is.
 *
 * <p>Cards on a board, wires between them, and a knob on a card that can be dragged or wired. One
 * view rather than a layout of real views, because there can be a hundred cards on a board that pans
 * and zooms, and because a wire has to be drawn between two of them.
 *
 * <p><b>Units.</b> Everything inside is in the reference's own units — a card is 396 wide and a row
 * is 55 tall — and the whole board is scaled by the zoom <em>and the screen's density</em> before it
 * is drawn. Keeping the stored coordinates in those units is what lets a graph move between this app
 * and the reference unchanged; scaling by density is what stops a card being 5dp of text on a modern
 * phone.
 *
 * <p>Every gesture that starts here takes the whole gesture off the fragment underneath, or a pan
 * would turn into a swipe back on the first horizontal movement.
 */
public class FrameNodeCanvasView extends View {

    public interface Listener {
        /**
         * @param recompile whether what changed affects the frame, rather than only the layout.
         * @param settled   whether the gesture has finished. While a finger is still down the graph
         *                  is changing sixty times a second, and writing it out that often would
         *                  repaint every open profile with each frame of the drag.
         */
        void onGraphChanged(boolean recompile, boolean settled);

        void onKnobTapped(int node, int knob);

        void onAddNode(float x, float y);
    }

    // The reference's own measurements. Changing any of them moves the sockets away from the wires.
    static final float NODE_W = 396f;
    private static final float HEAD_H = 55f;
    private static final float ROW_H = 55f;
    private static final float PIN_ROW_H = 34f;
    private static final float CARD_R = 18f;
    private static final float GRID = 40f;
    private static final float SOCKET_W = 10f;
    private static final float SOCKET_H = 32f;
    private static final float SOCKET_OUT = 2f;
    private static final float LABEL_W = 114f;
    private static final float BAR_H = 6f;
    private static final float VIEW_SIDE = 200f;
    private static final float PANEL_SIDE = 145f;
    private static final float GRAB_X = 30f;
    private static final float GRAB_Y = 28f;
    private static final float NUMBER_PULL = 2.5f;
    private static final float DODGE_GAP = 14f;
    private static final float ZOOM_MIN = 0.25f;
    private static final float ZOOM_MAX = 2.5f;
    private static final long HOLD_MS = 320;

    private static final int IDLE = 0;
    private static final int PAN = 1;
    private static final int MOVE_NODE = 2;
    private static final int WIRE = 3;
    private static final int KNOB = 4;
    private static final int BOX = 5;
    private static final int PANEL = 6;

    public static final int LINE_CURVE = 0;
    public static final int LINE_STRAIGHT = 1;
    public static final int LINE_ELBOW = 2;

    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint socket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mannequin = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF box = new RectF();
    private final Path curve = new Path();
    private final List<Integer> chosen = new ArrayList<>();
    private final Handler hand = new Handler(Looper.getMainLooper());

    private final FramePainter painter = new FramePainter();
    private FrameContour previewContour = FrameContour.EMPTY;

    private FrameGraph graph = FrameGraph.empty();
    private FrameCanvasSkin skin = FrameCanvasSkin.DARK;
    @Nullable
    private Listener listener;

    private float camX;
    private float camY;
    private float zoom = 0.75f;

    private int state = IDLE;
    private int heldId;
    private int knobRow;
    private int knobBase;
    private float knobFrom;
    private float lastX;
    private float lastY;
    private float boxX;
    private float boxY;
    private float wireX;
    private float wireY;
    private int wirePin;
    private boolean moved;
    private boolean framed;

    private int wireLine = LINE_CURVE;
    private boolean dodge;

    private final ScaleGestureDetector zoomer;
    private final Runnable hold = this::onHold;

    public FrameNodeCanvasView(Context context) {
        super(context);
        setWillNotDraw(false);
        ink.setTextSize(19f);
        bold.setTextSize(20f);
        bold.setTypeface(AndroidUtilities.bold());
        hint.setTextSize(16f);
        line.setStrokeWidth(1f);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f);
        wire.setStyle(Paint.Style.STROKE);
        wire.setStrokeWidth(4f);
        wire.setStrokeCap(Paint.Cap.ROUND);
        applySkin();

        zoomer = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                final float before = zoom;
                zoom = clamp(zoom * detector.getScaleFactor(), ZOOM_MIN, ZOOM_MAX);
                // Zoom about the fingers rather than the corner, or the board slides away.
                final float focusX = detector.getFocusX() / density();
                final float focusY = detector.getFocusY() / density();
                camX += focusX / before - focusX / zoom;
                camY += focusY / before - focusY / zoom;
                invalidate();
                return true;
            }
        });
    }

    public void setListener(@Nullable Listener value) {
        listener = value;
    }

    public void setGraph(@Nullable FrameGraph value) {
        graph = value == null ? FrameGraph.empty() : value;
        framed = false;
        invalidate();
    }

    public FrameGraph getGraph() {
        return graph;
    }

    /** Re-reads the canvas colours and the wire style from the settings. */
    public void applySkin() {
        final int mode = NekoConfig.customProfileFrameCanvasSkin.Int();
        FrameCanvasSkin custom = null;
        if (mode == FrameCanvasSkin.MODE_CUSTOM) {
            custom = FrameCanvasSkin.parse(NekoConfig.customProfileFrameCanvasCustom.String());
        } else if (mode == FrameCanvasSkin.MODE_SERVER) {
            final FrameCanvasThemes.Theme theme =
                    FrameCanvasThemes.byId(NekoConfig.customProfileFrameCanvasTheme.Int());
            custom = theme == null ? null : theme.skin;
        }
        final boolean appIsDark = FrameCanvasSkin.isDark(
                Theme.getColor(Theme.key_windowBackgroundWhite));
        skin = FrameCanvasSkin.of(
                mode == FrameCanvasSkin.MODE_SERVER ? FrameCanvasSkin.MODE_CUSTOM : mode,
                custom, appIsDark);
        wireLine = NekoConfig.customProfileFrameWireLine.Int();
        dodge = NekoConfig.customProfileFrameWireDodge.Bool();
        invalidate();
    }

    private static float density() {
        return AndroidUtilities.density;
    }

    private float scale() {
        return zoom * density();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(skin.back);
        if (!framed && getWidth() > 0) {
            framed = true;
            frameAll();
        }
        final int save = canvas.save();
        try {
            canvas.scale(scale(), scale());
            canvas.translate(-camX, -camY);
            grid(canvas);
            wires(canvas);
            for (FrameGraph.Node node : graph.nodes()) {
                node(canvas, node);
            }
            if (state == WIRE) {
                dragged(canvas);
            }
            if (state == BOX) {
                selection(canvas);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void grid(Canvas canvas) {
        dots.setColor(skin.grid);
        final float left = camX;
        final float top = camY;
        final float right = camX + getWidth() / scale();
        final float bottom = camY + getHeight() / scale();
        final float step = GRID;
        for (float x = (float) (Math.floor(left / step) * step); x < right; x += step) {
            for (float y = (float) (Math.floor(top / step) * step); y < bottom; y += step) {
                canvas.drawCircle(x, y, 1.5f, dots);
            }
        }
    }

    private void wires(Canvas canvas) {
        for (FrameGraph.Wire w : graph.wires()) {
            final FrameGraph.Node from = graph.node(w.from);
            final FrameGraph.Node to = graph.node(w.to);
            if (from == null || to == null) {
                continue;
            }
            wire.setColor(FrameGraphType.isKnobPin(w.pin) ? skin.faint : skin.padEdge);
            stroke(canvas, outX(from), outY(from), inX(to), inY(to, w.pin));
        }
    }

    private void dragged(Canvas canvas) {
        final FrameGraph.Node from = graph.node(heldId);
        if (from == null) {
            return;
        }
        wire.setColor(FrameCanvasSkin.ACCENT);
        stroke(canvas, outX(from), outY(from), wireX, wireY);
    }

    /**
     * One wire, in whichever of the three styles is set.
     *
     * <p>The dodge is deliberately modest: it finds the one card the wire would pass through and
     * takes the wire round the nearer of its two long edges. Routing properly round a whole board of
     * obstacles is a graph search of its own, and on a frame graph — a handful of cards laid out left
     * to right — it would almost never draw anything different from this.
     */
    private void stroke(Canvas canvas, float x1, float y1, float x2, float y2) {
        float midY = (y1 + y2) / 2f;
        if (dodge) {
            final FrameGraph.Node blocking = crossed(x1, y1, x2, y2);
            if (blocking != null) {
                final float top = blocking.y - DODGE_GAP;
                final float bottom = blocking.y + height(blocking) + DODGE_GAP;
                midY = Math.abs(midY - top) < Math.abs(midY - bottom) ? top : bottom;
            }
        }
        if (wireLine == LINE_ELBOW) {
            final float midX = (x1 + x2) / 2f;
            curve.rewind();
            curve.moveTo(x1, y1);
            curve.lineTo(midX, y1);
            curve.lineTo(midX, midY);
            curve.lineTo(midX, y2);
            curve.lineTo(x2, y2);
            canvas.drawPath(curve, wire);
            return;
        }
        if (wireLine == LINE_STRAIGHT) {
            if (!dodge || midY == (y1 + y2) / 2f) {
                canvas.drawLine(x1, y1, x2, y2, wire);
                return;
            }
            curve.rewind();
            curve.moveTo(x1, y1);
            curve.lineTo((x1 + x2) / 2f, midY);
            curve.lineTo(x2, y2);
            canvas.drawPath(curve, wire);
            return;
        }
        final float pull = Math.max(40f, Math.abs(x2 - x1) * 0.3f);
        curve.rewind();
        curve.moveTo(x1, y1);
        if (dodge && midY != (y1 + y2) / 2f) {
            // Pulled through the clear point rather than straight across the card.
            final float midX = (x1 + x2) / 2f;
            curve.cubicTo(x1 + pull, y1, midX - pull / 2f, midY, midX, midY);
            curve.cubicTo(midX + pull / 2f, midY, x2 - pull, y2, x2, y2);
        } else {
            curve.cubicTo(x1 + pull, y1, x2 - pull, y2, x2, y2);
        }
        canvas.drawPath(curve, wire);
    }

    /** The first card a straight run between two sockets would pass through, if any. */
    @Nullable
    private FrameGraph.Node crossed(float x1, float y1, float x2, float y2) {
        final float left = Math.min(x1, x2);
        final float right = Math.max(x1, x2);
        for (FrameGraph.Node node : graph.nodes()) {
            final float nodeRight = node.x + NODE_W;
            final float nodeBottom = node.y + height(node);
            if (nodeRight <= left || node.x >= right) {
                continue;
            }
            // Where the straight run is as it passes the card's two vertical edges.
            final float span = x2 - x1;
            if (Math.abs(span) < 1f) {
                continue;
            }
            final float atLeft = y1 + (y2 - y1) * (Math.max(node.x, left) - x1) / span;
            final float atRight = y1 + (y2 - y1) * (Math.min(nodeRight, right) - x1) / span;
            final float low = Math.min(atLeft, atRight);
            final float high = Math.max(atLeft, atRight);
            if (high >= node.y && low <= nodeBottom) {
                return node;
            }
        }
        return null;
    }

    private void selection(Canvas canvas) {
        edge.setColor(FrameCanvasSkin.ACCENT);
        box.set(Math.min(boxX, wireX), Math.min(boxY, wireY),
                Math.max(boxX, wireX), Math.max(boxY, wireY));
        canvas.drawRoundRect(box, 6f, 6f, edge);
    }

    private void node(Canvas canvas, FrameGraph.Node node) {
        final FrameGraphType.Kind kind = FrameGraphType.of(node.type);
        if (kind == null) {
            return;
        }
        final float height = height(node);
        final boolean picked = chosen.contains(node.id);
        box.set(node.x, node.y, node.x + NODE_W, node.y + height);

        card.setColor(skin.shadow);
        canvas.drawRoundRect(node.x, node.y + 3f, node.x + NODE_W, node.y + height + 3f,
                CARD_R, CARD_R, card);
        card.setColor(skin.card);
        canvas.drawRoundRect(box, CARD_R, CARD_R, card);
        if (picked) {
            edge.setColor(FrameCanvasSkin.ACCENT);
            canvas.drawRoundRect(box, CARD_R, CARD_R, edge);
        }

        // A strip in the category's colour, so the kinds are told apart before the name is read.
        fill.setColor(categoryColor(kind.category));
        canvas.drawRoundRect(node.x + 14f, node.y + 12f, node.x + 14f + BAR_H, node.y + 42f,
                BAR_H / 2f, BAR_H / 2f, fill);

        bold.setColor(skin.title);
        canvas.drawText(name(kind), node.x + 30f, middle(node.y, bold, HEAD_H), bold);
        hint.setColor(skin.note);
        final String fold = node.open ? "–" : "+";
        canvas.drawText(fold, node.x + NODE_W - 30f, middle(node.y, hint, HEAD_H), hint);

        final int pins = graph.pins(node.id);
        if (node.open) {
            for (int pin = 0; pin < pins; pin++) {
                pinRow(canvas, node, kind, pin, shelfY(node.y, pin) - PIN_ROW_H / 2f);
            }
            if (FrameGraphType.fillsCard(node.type)) {
                fillCard(canvas, node, node.y + HEAD_H + band(pins));
            } else {
                for (int knob = 0; knob < kind.knobs(); knob++) {
                    knobRow(canvas, node, kind, knob,
                            node.y + HEAD_H + band(pins) + knob * ROW_H);
                }
            }
        }

        // Sockets are drawn folded or not: a folded card still has to be wireable.
        for (int pin = 0; pin < pins; pin++) {
            socket.setColor(graph.input(node.id, pin) > 0 ? skin.padEdge : skin.pad);
            final float y = inY(node, pin);
            canvas.drawRoundRect(node.x - SOCKET_W - SOCKET_OUT, y - SOCKET_H / 2f,
                    node.x + SOCKET_OUT, y + SOCKET_H / 2f, SOCKET_W / 2f, SOCKET_W / 2f, socket);
        }
        if (node.open && !FrameGraphType.fillsCard(node.type)) {
            for (int knob = 0; knob < kind.knobs(); knob++) {
                if (!FrameGraphType.numericKnob(kind, knob)) {
                    continue;
                }
                final int pin = FrameGraphType.knobPin(knob);
                socket.setColor(graph.input(node.id, pin) > 0 ? skin.padEdge : skin.padOff);
                final float y = inY(node, pin);
                canvas.drawRoundRect(node.x - SOCKET_W, y - SOCKET_H / 3f,
                        node.x, y + SOCKET_H / 3f, SOCKET_W / 2f, SOCKET_W / 2f, socket);
            }
        }
        if (kind.gives != FrameGraphType.PIN_NONE) {
            socket.setColor(skin.padEdge);
            final float y = outY(node);
            canvas.drawRoundRect(node.x + NODE_W - SOCKET_OUT, y - SOCKET_H / 2f,
                    node.x + NODE_W + SOCKET_W + SOCKET_OUT, y + SOCKET_H / 2f,
                    SOCKET_W / 2f, SOCKET_W / 2f, socket);
        }
    }

    private void pinRow(Canvas canvas, FrameGraph.Node node, FrameGraphType.Kind kind,
                        int pin, float top) {
        hint.setColor(graph.input(node.id, pin) > 0 ? skin.text : skin.noteOff);
        canvas.drawText(pinName(kind, pin), node.x + 19f, middle(top, hint, PIN_ROW_H), hint);
    }

    private void knobRow(Canvas canvas, FrameGraph.Node node, FrameGraphType.Kind kind,
                         int index, float top) {
        final FrameGraphType.Knob knob = kind.knobs[index];
        final boolean wired = graph.input(node.id, FrameGraphType.knobPin(index)) > 0;
        fill.setColor(skin.row);
        canvas.drawRoundRect(node.x + 14f, top + 6f, node.x + NODE_W - 14f, top + ROW_H - 6f,
                10f, 10f, fill);

        ink.setColor(wired ? skin.dim : skin.text);
        canvas.drawText(knobName(kind, index), node.x + 28f, middle(top, ink, ROW_H), ink);

        final String value = wired ? "•" : valueOf(kind, node, index);
        ink.setColor(wired ? skin.faint : skin.text);
        canvas.drawText(value, node.x + NODE_W - 28f - ink.measureText(value),
                middle(top, ink, ROW_H), ink);

        if (knob.kind == FrameGraphType.KNOB_RANGE && !wired) {
            // A hairline under the label showing where in its range the value sits.
            final float left = node.x + 28f;
            final float right = node.x + NODE_W - 28f - LABEL_W;
            final float y = top + ROW_H - 12f;
            track.setColor(skin.bar);
            canvas.drawRoundRect(left, y, right, y + 3f, 1.5f, 1.5f, track);
            final float span = Math.max(1, knob.max - knob.min);
            final float part = clamp((node.value(index) - knob.min) / span, 0f, 1f);
            fillBar.setColor(skin.barFill);
            canvas.drawRoundRect(left, y, left + (right - left) * part, y + 3f, 1.5f, 1.5f, fillBar);
        }
        if (knob.kind == FrameGraphType.KNOB_COLOR && !wired) {
            fill.setColor(node.value(index) | 0xFF000000);
            canvas.drawCircle(node.x + NODE_W - 40f, top + ROW_H / 2f, 11f, fill);
        }
    }

    /** The two cards that hold something live: a preview of a branch, and a joystick. */
    private void fillCard(Canvas canvas, FrameGraph.Node node, float top) {
        if (node.type == FrameGraphType.VIEW) {
            preview(canvas, node, top);
            return;
        }
        final float left = node.x + (NODE_W - PANEL_SIDE) / 2f;
        final int shape = FrameGraphPanel.shape(graph, node.id);
        fill.setColor(skin.pad);
        canvas.drawRoundRect(left, top, left + PANEL_SIDE, top + PANEL_SIDE, 16f, 16f, fill);
        edge.setColor(skin.padEdge);
        canvas.drawRoundRect(left, top, left + PANEL_SIDE, top + PANEL_SIDE, 16f, 16f, edge);
        line.setColor(skin.padCross);
        final float cx = left + PANEL_SIDE / 2f;
        final float cy = top + PANEL_SIDE / 2f;
        if (shape == FrameGraphPanel.SHAPE_RING) {
            edge.setColor(skin.padCross);
            canvas.drawCircle(cx, cy, PANEL_SIDE / 2f - 18f, edge);
        } else {
            if (shape != FrameGraphPanel.SHAPE_DOWN) {
                canvas.drawLine(left + 14f, cy, left + PANEL_SIDE - 14f, cy, line);
            }
            if (shape != FrameGraphPanel.SHAPE_ACROSS) {
                canvas.drawLine(cx, top + 14f, cx, top + PANEL_SIDE - 14f, line);
            }
        }
        fill.setColor(FrameCanvasSkin.ACCENT);
        canvas.drawCircle(cx, cy, 9f, fill);
    }

    private void preview(Canvas canvas, FrameGraph.Node node, float top) {
        final float left = node.x + (NODE_W - VIEW_SIDE) / 2f;
        final FrameSpec spec = FrameGraphBuild.branch(graph, node.id);
        final float side = FrameStage.box(VIEW_SIDE, spec, FrameStage.avatarSide());
        final float x = left + (VIEW_SIDE - side) / 2f;
        final float y = top + (VIEW_SIDE - side) / 2f;
        if (previewContour.isEmpty()) {
            previewContour = FrameOutline.of(FrameShape.of(0, 0, 0, null, side),
                    FrameOutline.SAMPLES);
            painter.contour(previewContour);
        }
        mannequin.setColor(skin.skin);
        canvas.drawCircle(x + side / 2f, y + side / 2f, side / 2f, mannequin);
        painter.draw(canvas, spec, x, y, side, side, FramePainter.toSpace(), 0f, 0xFF,
                CustomProfileFrame.sources());
        if (FramePainter.moving(spec)) {
            postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- geometry

    static float band(int pins) {
        return pins * PIN_ROW_H;
    }

    private float band(FrameGraph.Node node) {
        return band(graph.pins(node.id));
    }

    static float shelfY(float top, int pin) {
        return top + HEAD_H + pin * PIN_ROW_H + PIN_ROW_H / 2f;
    }

    static float knobY(float top, int pins, int knob) {
        return top + HEAD_H + band(pins) + knob * ROW_H + ROW_H / 2f;
    }

    private static float middle(float top, Paint paint, float height) {
        final Paint.FontMetrics metrics = paint.getFontMetrics();
        return top + height / 2f - (metrics.ascent + metrics.descent) / 2f;
    }

    private float height(FrameGraph.Node node) {
        return height(node, node.open ? 1f : 0f);
    }

    private float height(FrameGraph.Node node, float open) {
        final FrameGraphType.Kind kind = FrameGraphType.of(node.type);
        if (kind == null) {
            return HEAD_H;
        }
        final float content = band(node)
                + (FrameGraphType.fillsCard(node.type)
                ? (node.type == FrameGraphType.VIEW ? VIEW_SIDE : PANEL_SIDE + 20f)
                : kind.knobs() * ROW_H + 8f);
        // A folded card still has to be tall enough for its own sockets to sit apart.
        return Math.max(HEAD_H + open * content,
                Math.max(0, graph.pins(node.id) - 1) * CARD_R + HEAD_H);
    }

    private float inX(FrameGraph.Node node) {
        return node.x;
    }

    private float inY(FrameGraph.Node node, int pin) {
        final float open = node.open ? 1f : 0f;
        if (FrameGraphType.isKnobPin(pin)) {
            return knobPinY(node, FrameGraphType.knobOfPin(pin), open);
        }
        final float folded = foldedInY(node, pin, height(node, 0f));
        if (open <= 0f) {
            return folded;
        }
        return folded + (shelfY(node.y, pin) - folded) * open;
    }

    private float knobPinY(FrameGraph.Node node, int knob, float open) {
        final float folded = node.y + height(node, 0f) / 2f;
        if (open <= 0f) {
            return folded;
        }
        return folded + (knobY(node.y, graph.pins(node.id), knob) - folded) * open;
    }

    /** Where a folded card's sockets sit: fanned down its left edge rather than stacked. */
    private float foldedInY(FrameGraph.Node node, int pin, float height) {
        final int pins = Math.max(1, graph.pins(node.id));
        if (pins == 1) {
            return node.y + height / 2f;
        }
        return node.y + height - 12f - pin * Math.min(GRAB_X, (height - 20f) / pins);
    }

    private float outX(FrameGraph.Node node) {
        return node.x + NODE_W;
    }

    private float outY(FrameGraph.Node node) {
        return node.y + height(node) / 2f;
    }

    // ---------------------------------------------------------------- gestures

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        zoomer.onTouchEvent(event);
        if (zoomer.isInProgress()) {
            state = IDLE;
            return true;
        }
        final float x = event.getX() / scale() + camX;
        final float y = event.getY() / scale() + camY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> down(x, y);
            case MotionEvent.ACTION_MOVE -> move(x, y);
            case MotionEvent.ACTION_UP -> up(x, y);
            case MotionEvent.ACTION_CANCEL -> {
                hand.removeCallbacks(hold);
                state = IDLE;
                invalidate();
            }
            default -> {
            }
        }
        return true;
    }

    private void down(float x, float y) {
        // The board pans and the fragment swipes back on the same gesture; the board wins.
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        lastX = x;
        lastY = y;
        boxX = x;
        boxY = y;
        wireX = x;
        wireY = y;
        moved = false;
        heldId = 0;
        state = PAN;

        final FrameGraph.Node node = nodeAt(x, y);
        if (node == null) {
            hand.postDelayed(hold, HOLD_MS);
            return;
        }
        heldId = node.id;
        final FrameGraphType.Kind kind = FrameGraphType.of(node.type);

        // An output socket starts a wire; an input socket that already holds one picks it back up.
        if (kind != null && kind.gives != FrameGraphType.PIN_NONE
                && x > node.x + NODE_W - SOCKET_W - 8f) {
            state = WIRE;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        final int pin = pinAt(node, x, y);
        if (pin >= 0) {
            final int source = graph.input(node.id, pin);
            graph.cut(node.id, pin);
            changed(true);
            if (source > 0) {
                heldId = source;
                state = WIRE;
            } else {
                state = IDLE;
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        if (node.open && y < node.y + HEAD_H && x > node.x + NODE_W - 60f) {
            graph.toggle(node.id);
            changed(false);
            state = IDLE;
            invalidate();
            return;
        }
        if (!node.open && y < node.y + HEAD_H && x > node.x + NODE_W - 60f) {
            graph.toggle(node.id);
            changed(false);
            state = IDLE;
            invalidate();
            return;
        }
        final int knob = knobAt(node, x, y);
        if (knob >= 0) {
            state = KNOB;
            knobRow = knob;
            knobBase = node.value(knob);
            knobFrom = x;
            return;
        }
        if (node.type == FrameGraphType.PANEL && node.open && insidePanel(node, x, y)) {
            state = PANEL;
            return;
        }
        state = MOVE_NODE;
        hand.postDelayed(hold, HOLD_MS);
    }

    private void move(float x, float y) {
        final float dx = x - lastX;
        final float dy = y - lastY;
        if (Math.abs(x - boxX) > 6f || Math.abs(y - boxY) > 6f) {
            moved = true;
            hand.removeCallbacks(hold);
        }
        switch (state) {
            case PAN -> {
                camX -= dx;
                camY -= dy;
            }
            case MOVE_NODE -> {
                final FrameGraph.Node node = graph.node(heldId);
                if (node != null) {
                    graph.move(node.id, Math.round(node.x + dx), Math.round(node.y + dy));
                    // Everything picked up moves together, which is what a box selection is for.
                    for (int id : chosen) {
                        if (id == node.id) {
                            continue;
                        }
                        final FrameGraph.Node other = graph.node(id);
                        if (other != null) {
                            graph.move(id, Math.round(other.x + dx), Math.round(other.y + dy));
                        }
                    }
                }
            }
            case WIRE, BOX -> {
                wireX = x;
                wireY = y;
            }
            case KNOB -> {
                final FrameGraph.Node node = graph.node(heldId);
                final FrameGraphType.Kind kind = node == null ? null : FrameGraphType.of(node.type);
                if (node != null && kind != null && knobRow < kind.knobs()) {
                    final FrameGraphType.Knob knob = kind.knobs[knobRow];
                    final int value = knob.kind == FrameGraphType.KNOB_RANGE
                            ? knobBase + Math.round((x - knobFrom) / NUMBER_PULL
                            * Math.max(1, knob.max - knob.min) / 200f)
                            : knobBase + Math.round((x - knobFrom) / NUMBER_PULL);
                    graph.set(node.id, knobRow, value);
                    changed(true, false);
                }
            }
            case PANEL -> {
                final FrameGraph.Node node = graph.node(heldId);
                if (node != null) {
                    steer(node, dx, dy);
                    changed(true, false);
                }
            }
            default -> {
            }
        }
        lastX = x;
        lastY = y;
        invalidate();
    }

    private void up(float x, float y) {
        hand.removeCallbacks(hold);
        if (state == WIRE) {
            drop(x, y);
        } else if (state == BOX) {
            pick();
        } else if (state == MOVE_NODE && !moved) {
            final FrameGraph.Node node = graph.node(heldId);
            if (node != null) {
                final int knob = knobAt(node, x, y);
                if (knob >= 0 && listener != null) {
                    listener.onKnobTapped(node.id, knob);
                } else if (y < node.y + HEAD_H) {
                    graph.toggle(node.id);
                    changed(false);
                }
            }
        } else if (state == MOVE_NODE) {
            changed(false);
        } else if (state == KNOB || state == PANEL) {
            changed(true);
        }
        state = IDLE;
        invalidate();
    }

    /** A long press on empty board offers a new node; on a card it picks the card out. */
    private void onHold() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (heldId == 0) {
            if (listener != null) {
                listener.onAddNode(boxX, boxY);
            }
            state = IDLE;
        } else {
            if (chosen.contains(heldId)) {
                chosen.remove((Integer) heldId);
            } else {
                chosen.add(heldId);
            }
        }
        invalidate();
    }

    private void drop(float x, float y) {
        final FrameGraph.Node target = nodeAt(x, y);
        if (target == null || target.id == heldId) {
            return;
        }
        final int pin = nearestPin(target, y);
        if (pin >= 0 && graph.link(heldId, target.id, pin)) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            changed(true);
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
    }

    private void pick() {
        chosen.clear();
        final RectF area = new RectF(Math.min(boxX, wireX), Math.min(boxY, wireY),
                Math.max(boxX, wireX), Math.max(boxY, wireY));
        for (FrameGraph.Node node : graph.nodes()) {
            if (area.intersects(node.x, node.y, node.x + NODE_W, node.y + height(node))) {
                chosen.add(node.id);
            }
        }
    }

    /** Drags a joystick: one axis per knob it drives, or the angle when it drives only turns. */
    private void steer(FrameGraph.Node node, float dx, float dy) {
        final int shape = FrameGraphPanel.shape(graph, node.id);
        if (shape == FrameGraphPanel.SHAPE_RING) {
            final float top = node.y + HEAD_H + band(node);
            final float cx = node.x + NODE_W / 2f;
            final float cy = top + PANEL_SIDE / 2f;
            final float swept = FrameGraphPanel.swept(lastX, lastY, lastX + dx, lastY + dy, cx, cy);
            graph.set(node.id, FrameGraphPanel.KNOB_ACROSS,
                    FrameGraphPanel.turned(node.value(FrameGraphPanel.KNOB_ACROSS), swept));
            return;
        }
        if (shape != FrameGraphPanel.SHAPE_DOWN) {
            graph.set(node.id, FrameGraphPanel.KNOB_ACROSS,
                    FrameGraphPanel.pulled(node.value(FrameGraphPanel.KNOB_ACROSS), dx));
        }
        if (shape != FrameGraphPanel.SHAPE_ACROSS) {
            graph.set(node.id, FrameGraphPanel.KNOB_DOWN,
                    FrameGraphPanel.pulled(node.value(FrameGraphPanel.KNOB_DOWN), dy));
        }
    }

    // ---------------------------------------------------------------- hit tests

    @Nullable
    private FrameGraph.Node nodeAt(float x, float y) {
        // Backwards: the last drawn card is the one on top.
        final List<FrameGraph.Node> nodes = graph.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            final FrameGraph.Node node = nodes.get(i);
            if (x >= node.x - SOCKET_W - SOCKET_OUT && x <= node.x + NODE_W + SOCKET_W + SOCKET_OUT
                    && y >= node.y && y <= node.y + height(node)) {
                return node;
            }
        }
        return null;
    }

    private int pinAt(FrameGraph.Node node, float x, float y) {
        if (x > node.x + SOCKET_OUT) {
            return -1;
        }
        return nearestPin(node, y);
    }

    private int nearestPin(FrameGraph.Node node, float y) {
        final FrameGraphType.Kind kind = FrameGraphType.of(node.type);
        if (kind == null) {
            return -1;
        }
        int best = -1;
        float bestGap = GRAB_Y;
        final int pins = graph.pins(node.id);
        for (int pin = 0; pin < pins; pin++) {
            final float gap = Math.abs(inY(node, pin) - y);
            if (gap < bestGap) {
                bestGap = gap;
                best = pin;
            }
        }
        if (node.open && !FrameGraphType.fillsCard(node.type)) {
            for (int knob = 0; knob < kind.knobs(); knob++) {
                if (!FrameGraphType.numericKnob(kind, knob)) {
                    continue;
                }
                final int pin = FrameGraphType.knobPin(knob);
                final float gap = Math.abs(inY(node, pin) - y);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = pin;
                }
            }
        }
        return best;
    }

    private int knobAt(FrameGraph.Node node, float x, float y) {
        final FrameGraphType.Kind kind = FrameGraphType.of(node.type);
        if (kind == null || !node.open || FrameGraphType.fillsCard(node.type)) {
            return -1;
        }
        if (x < node.x + 14f || x > node.x + NODE_W - 14f) {
            return -1;
        }
        final float top = node.y + HEAD_H + band(node);
        final int index = (int) Math.floor((y - top) / ROW_H);
        return (index < 0 || index >= kind.knobs()) ? -1 : index;
    }

    private boolean insidePanel(FrameGraph.Node node, float x, float y) {
        final float top = node.y + HEAD_H + band(node);
        final float left = node.x + (NODE_W - PANEL_SIDE) / 2f;
        return x >= left && x <= left + PANEL_SIDE && y >= top && y <= top + PANEL_SIDE;
    }

    // ---------------------------------------------------------------- the board

    /** Fits every card on screen, which is the only sensible first view of a graph. */
    public void frameAll() {
        if (graph.count() == 0 || getWidth() <= 0) {
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (FrameGraph.Node node : graph.nodes()) {
            minX = Math.min(minX, node.x - SOCKET_W);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + NODE_W + SOCKET_W);
            maxY = Math.max(maxY, node.y + height(node));
        }
        final float width = Math.max(1f, maxX - minX) + GRID * 2;
        final float height = Math.max(1f, maxY - minY) + GRID * 2;
        zoom = clamp(Math.min(getWidth() / density() / width, getHeight() / density() / height),
                ZOOM_MIN, ZOOM_MAX);
        camX = minX - GRID - (getWidth() / scale() - (maxX - minX)) / 2f;
        camY = minY - GRID - (getHeight() / scale() - (maxY - minY)) / 2f;
        invalidate();
    }

    /** Removes whatever is picked out; the studio's context menu calls it. */
    public void dropChosen() {
        if (chosen.isEmpty()) {
            return;
        }
        for (int id : chosen) {
            graph.drop(id);
        }
        chosen.clear();
        changed(true);
        invalidate();
    }

    public int chosenCount() {
        return chosen.size();
    }

    private void changed(boolean recompile) {
        changed(recompile, true);
    }

    private void changed(boolean recompile, boolean settled) {
        if (listener != null) {
            listener.onGraphChanged(recompile, settled);
        }
    }

    // ---------------------------------------------------------------- labels

    private static int categoryColor(int category) {
        return switch (category) {
            case FrameGraphType.CAT_INPUT -> 0xFF8AB4E3;
            case FrameGraphType.CAT_SHAPE -> 0xFF6FCF97;
            case FrameGraphType.CAT_PARTICLE -> 0xFFC49BE0;
            case FrameGraphType.CAT_COLOR -> 0xFFE0A15C;
            case FrameGraphType.CAT_TRANSFORM -> 0xFF5CC8E0;
            case FrameGraphType.CAT_LAYOUT -> 0xFFE0C95C;
            default -> 0xFFE07A7A;
        };
    }

    private static String name(FrameGraphType.Kind kind) {
        return org.telegram.messenger.LocaleController.getString("FrameNode" + kind.slug);
    }

    private static String pinName(FrameGraphType.Kind kind, int pin) {
        final String slug = FrameGraphType.pinSlug(kind, pin);
        return slug.isEmpty() ? "" : org.telegram.messenger.LocaleController.getString("FramePin" + slug);
    }

    private static String knobName(FrameGraphType.Kind kind, int knob) {
        return org.telegram.messenger.LocaleController.getString(
                "FrameKnob" + kind.slug + kind.knobs[knob].slug);
    }

    private String valueOf(FrameGraphType.Kind kind, FrameGraph.Node node, int index) {
        final FrameGraphType.Knob knob = kind.knobs[index];
        if (knob.kind == FrameGraphType.KNOB_IMAGE) {
            final String text = node.text(index);
            if (text.isEmpty()) {
                return "—";
            }
            final int slash = text.lastIndexOf('/');
            final String name = slash < 0 ? text : text.substring(slash + 1);
            return name.length() > 14 ? name.substring(0, 13) + "…" : name;
        }
        if (knob.kind == FrameGraphType.KNOB_CHOICE) {
            return org.telegram.messenger.LocaleController.getString(
                    "FrameKnob" + kind.slug + knob.slug + "Option" + node.value(index));
        }
        if (knob.kind == FrameGraphType.KNOB_COLOR) {
            return "";
        }
        return String.valueOf(node.value(index));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
