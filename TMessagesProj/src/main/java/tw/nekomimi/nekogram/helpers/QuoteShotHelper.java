package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the selected messages into a single picture, the way the "shot" feature of other clients
 * does. Reachable from the message action mode only; there is no setting behind it.
 *
 * <p>The bubbles are not redrawn by hand: real {@link ChatMessageCell}s are built off-screen, given
 * the same neighbour flags the chat list would give them, and asked to draw into a bitmap. That is
 * the only way the result stays identical to what the chat shows, reactions and albums included.
 */
public final class QuoteShotHelper {

    /**
     * Past this a quote costs more memory than it is worth — 30 bubbles already make an image a few
     * thousand pixels tall — so the user is asked whether to keep the first ones.
     */
    public static final int MAX_MESSAGES = 30;

    /** Two neighbouring bubbles sit this far apart in the chat list. */
    private static final int GAP = 2;
    private static final int PADDING = 16;
    /** Messages from one sender less than this many seconds apart share a bubble run. */
    private static final int SAME_RUN_SECONDS = 300;

    private QuoteShotHelper() {
    }

    public static void makeQuote(ChatActivity fragment, ArrayList<MessageObject> messages) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (messages.size() <= MAX_MESSAGES) {
            start(fragment, messages);
            return;
        }
        final ArrayList<MessageObject> capped = new ArrayList<>(messages.subList(0, MAX_MESSAGES));
        new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(getString(R.string.QuoteShotTooManyTitle))
                .setMessage(LocaleController.formatString(R.string.QuoteShotTooMany, messages.size(), MAX_MESSAGES))
                .setPositiveButton(getString(R.string.Continue), (dialog, which) -> start(fragment, capped))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void start(ChatActivity fragment, List<MessageObject> messages) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, fragment.getResourceProvider());
        progress.setCanCancel(false);
        progress.show();
        // Cells can only be measured and drawn here, so the render blocks the main thread; let the
        // spinner get one frame in first so it is on screen while that happens.
        AndroidUtilities.runOnUIThread(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = render(fragment, messages);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            progress.dismiss();
            if (bitmap == null) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.QuoteShotFailed)).show();
                return;
            }
            preview(fragment, bitmap);
        }, 80);
    }

    private static void preview(ChatActivity fragment, Bitmap bitmap) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final ImageView imageView = new ImageView(activity);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        imageView.setImageBitmap(bitmap);
        imageView.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle(getString(R.string.QuoteShot))
                .setView(imageView, Math.min(dp(420), (int) (AndroidUtilities.displaySize.y * 0.55f)))
                .setPositiveButton(getString(R.string.Send), (dialog, which) -> save(fragment, bitmap))
                .setNegativeButton(getString(R.string.Cancel), null)
                // The bitmap is a few megabytes; let go of it as soon as the sheet is gone.
                .setOnDismissListener(dialog -> imageView.setImageDrawable(null))
                .show();
    }

    private static void save(ChatActivity fragment, Bitmap bitmap) {
        Utilities.globalQueue.postRunnable(() -> {
            File file = null;
            try {
                file = new File(AndroidUtilities.getCacheDir(), "quote_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                file = null;
            }
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.QuoteShotFailed)).show();
                    return;
                }
                SendMessagesHelper.prepareSendingPhoto(
                        fragment.getAccountInstance(), result.getAbsolutePath(), null, fragment.getDialogId(),
                        null, fragment.getThreadMessage(), null, null, null, null, null, 0, null,
                        true, 0, fragment.getChatMode(), fragment.quickReplyShortcut, fragment.getQuickReplyId());
            });
        });
    }

    // One message together with the neighbour flags and the album slot the chat list would give it.
    private static final class Item {
        MessageObject message;
        boolean pinnedBottom;
        boolean pinnedTop;
        MessageObject.GroupedMessages group;
        MessageObject.GroupedMessagePosition position;
        int row;
        int x;
        int width;
    }

    // One finished strip of the picture: a bubble, or a whole album.
    private static final class Piece {
        Bitmap bitmap;
        int left;
        int right;
        int height;
        int topInset;
    }

    private static Bitmap render(ChatActivity fragment, List<MessageObject> messages) {
        final int width = Math.max(dp(320), AndroidUtilities.displaySize.x);
        final int screenHeight = Math.max(dp(480), AndroidUtilities.displaySize.y);
        final List<List<Item>> units = plan(messages, width);
        if (units.isEmpty()) {
            return null;
        }
        final ArrayList<Piece> pieces = new ArrayList<>();
        final Cells cells = new Cells(fragment);
        try {
            for (List<Item> unit : units) {
                final Piece piece = renderUnit(cells, unit, width, screenHeight);
                if (piece != null) {
                    pieces.add(piece);
                }
            }
        } finally {
            cells.release();
        }
        if (pieces.isEmpty()) {
            return null;
        }
        return compose(pieces);
    }

    /**
     * Splits the selection into units — a plain message is one unit, an album is one unit made of
     * several cells — and works out the neighbour flags. Runs of messages from one sender within
     * {@link #SAME_RUN_SECONDS} get flattened bubble corners, exactly as in the chat list.
     */
    private static List<List<Item>> plan(List<MessageObject> messages, int width) {
        final int count = messages.size();
        final ArrayList<Item> items = new ArrayList<>(count);
        for (int a = 0; a < count; a++) {
            final MessageObject message = messages.get(a);
            if (message == null) {
                continue;
            }
            final Item item = new Item();
            item.message = message;
            item.pinnedBottom = a < count - 1 && sameRun(message, messages.get(a + 1));
            item.pinnedTop = a > 0 && sameRun(message, messages.get(a - 1));
            item.x = 0;
            item.width = width;
            items.add(item);
        }
        final ArrayList<List<Item>> units = new ArrayList<>();
        final int total = items.size();
        int index = 0;
        while (index < total) {
            final long groupId = groupIdOf(items.get(index).message);
            int end = index + 1;
            if (groupId != 0) {
                while (end < total && groupIdOf(items.get(end).message) == groupId) {
                    end++;
                }
            }
            MessageObject.GroupedMessages group = end - index > 1 ? makeGroup(items, index, end) : null;
            int[][] geometry = group != null ? albumGeometry(group, width) : null;
            if (geometry != null && geometry.length != end - index) {
                group = null;
            }
            if (group == null) {
                for (int a = index; a < end; a++) {
                    units.add(new ArrayList<>(items.subList(a, a + 1)));
                }
            } else {
                final boolean groupTop = items.get(index).pinnedTop;
                final boolean groupBottom = items.get(end - 1).pinnedBottom;
                final ArrayList<Item> unit = new ArrayList<>(end - index);
                for (int a = index; a < end; a++) {
                    final Item item = items.get(a);
                    item.group = group;
                    item.position = group.posArray.get(a - index);
                    // Inside an album only the outer edges keep the run flags; every inner edge is
                    // always "near" so the corners between tiles stay square.
                    item.pinnedTop = (item.position.flags & MessageObject.POSITION_FLAG_TOP) != 0 ? groupTop : true;
                    item.pinnedBottom = (item.position.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0 ? groupBottom : true;
                    item.row = geometry[a - index][0];
                    item.x = geometry[a - index][1];
                    item.width = geometry[a - index][2];
                    unit.add(item);
                }
                units.add(unit);
            }
            index = end;
        }
        return units;
    }

    private static boolean sameRun(MessageObject one, MessageObject two) {
        if (one == null || two == null || isAction(one) || isAction(two)) {
            return false;
        }
        return one.getSenderId() == two.getSenderId()
                && Math.abs(one.messageOwner.date - two.messageOwner.date) < SAME_RUN_SECONDS;
    }

    private static boolean isAction(MessageObject message) {
        return message.isDateObject || message.contentType == 1;
    }

    private static long groupIdOf(MessageObject message) {
        return message.hasValidGroupId() ? message.getGroupId() : 0;
    }

    /** Rebuilds the album layout for the picked messages alone, so a partial selection still tiles. */
    private static MessageObject.GroupedMessages makeGroup(List<Item> items, int from, int to) {
        try {
            final MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
            group.groupId = items.get(from).message.getGroupId();
            for (int a = from; a < to; a++) {
                group.messages.add(items.get(a).message);
            }
            group.calculate();
            if (group.posArray.size() != to - from) {
                return null;
            }
            for (int a = from; a < to; a++) {
                if (group.getPosition(items.get(a).message) == null) {
                    return null;
                }
            }
            return group;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Turns the 1000-unit span sizes the album layout works in into {row, x, width} in pixels,
     * starting a new row whenever the spans would overflow.
     */
    private static int[][] albumGeometry(MessageObject.GroupedMessages group, int width) {
        final int count = group.posArray.size();
        final int[][] result = new int[count][3];
        int span = 0;
        int row = 0;
        for (int a = 0; a < count; a++) {
            int size = group.posArray.get(a).spanSize;
            if (size <= 0) {
                size = 1000;
            }
            if (span > 0 && span + size > 1000) {
                row++;
                span = 0;
            }
            final int end = Math.min(1000, span + size);
            final int left = (int) Math.ceil(span * width / 1000.0);
            final int right = (int) Math.ceil(end * width / 1000.0);
            result[a][0] = row;
            result[a][1] = left;
            result[a][2] = Math.max(1, right - left);
            span += size;
        }
        return result;
    }

    /** Keeps the two reusable cells alive for the whole render; building them per message is slow. */
    private static final class Cells {
        private final ChatActivity fragment;
        private ChatMessageCell message;
        private ChatActionCell action;

        Cells(ChatActivity fragment) {
            this.fragment = fragment;
        }

        ChatMessageCell message() {
            if (message == null) {
                message = new ChatMessageCell(fragment.getParentActivity(), fragment.getCurrentAccount(),
                        true, fragment.sharedResources, fragment.themeDelegate);
                message.onAttachedToWindow();
                // Every method of the delegate has a default, so an empty one is enough: nothing
                // off-screen can be tapped, and the cell only asks it for optional extras.
                message.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                });
                message.isChat = fragment.getCurrentChat() != null;
                message.isBot = fragment.getCurrentUser() != null && fragment.getCurrentUser().bot;
                message.isMegagroup = ChatObject.isChannel(fragment.getCurrentChat()) && fragment.getCurrentChat().megagroup;
                message.isForum = ChatObject.isForum(fragment.getCurrentChat());
                message.drawingToBitmap = true;
                message.setFullyDraw(true);
            }
            return message;
        }

        ChatActionCell action() {
            if (action == null) {
                action = new ChatActionCell(fragment.getParentActivity(), false, fragment.themeDelegate);
                action.onAttachedToWindow();
            }
            return action;
        }

        void release() {
            if (message != null) {
                message.onDetachedFromWindow();
            }
            if (action != null) {
                action.onDetachedFromWindow();
            }
        }
    }

    private static Piece renderUnit(Cells cells, List<Item> unit, int width, int screenHeight) {
        try {
            if (unit.size() == 1 && unit.get(0).position == null) {
                final Item item = unit.get(0);
                return isAction(item.message)
                        ? renderAction(cells.action(), item.message, width, screenHeight)
                        : renderBubble(cells.message(), item, width, screenHeight);
            }
            return renderAlbum(cells, unit, width, screenHeight);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Piece renderBubble(ChatMessageCell cell, Item item, int width, int screenHeight) {
        final MessageObject message = item.message;
        final boolean out = message.isOutOwner();
        final int cellWidth = Math.max(1, item.width);

        cell.setParentViewSize(width, screenHeight);
        cell.setMessageObject(message, item.group, item.pinnedBottom, item.pinnedTop, false);
        cell.measure(View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int height = cell.getMeasuredHeight();
        if (height <= 0) {
            return null;
        }
        cell.layout(0, 0, cellWidth, height);
        cell.setParentViewSize(width, screenHeight);
        try {
            cell.setVisiblePart(0, height, screenHeight, 0f, 0f, width, screenHeight, 0, 0, 0);
        } catch (Throwable ignore) {
        }
        // Off-screen receivers do not fetch on their own, so kick every one of them and let the
        // already-cached bitmap land synchronously; anything still missing simply draws as a
        // placeholder, which is what the chat would show at that moment too.
        loadImages(cell);

        // A cell taller than the avatar leaves it inside the bubble; a short one hangs it below the
        // bottom edge, so the strip needs a few pixels of headroom at the top to hold it.
        final boolean drawAvatar = cell.isAvatarVisible && !out && (!item.pinnedBottom || message.forceAvatar);
        int topInset = 0;
        if (drawAvatar) {
            final int avatarTop = height - dp(42) - dp(4);
            if (avatarTop < 0) {
                topInset = -avatarTop;
            }
        }

        final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, cellWidth), height + topInset, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        // Gradient outgoing bubbles paint their background through the parent, which does not exist
        // here — draw it manually first or those bubbles come out empty.
        if (cell.drawBackgroundInParent()) {
            canvas.save();
            canvas.translate(0, topInset + cell.getPaddingTop());
            cell.drawBackgroundInternal(canvas, true);
            canvas.restore();
        }
        canvas.save();
        canvas.translate(0, topInset);
        cell.draw(canvas);
        if (drawAvatar && !item.pinnedBottom) {
            final ImageReceiver avatar = cell.getAvatarImage();
            if (avatar != null) {
                final int size = dp(42);
                final float wasX = avatar.getImageX(), wasY = avatar.getImageY();
                final float wasW = avatar.getImageWidth(), wasH = avatar.getImageHeight();
                avatar.setImageCoords(dp(6), height - size - dp(4), size, size);
                avatar.setVisible(true, false);
                avatar.draw(canvas);
                avatar.setImageCoords(wasX, wasY, wasW, wasH);
            }
        }
        canvas.restore();

        final Piece piece = new Piece();
        piece.bitmap = bitmap;
        piece.height = height;
        piece.topInset = topInset;
        applyBounds(piece, cell, message, cellWidth, out);
        return piece;
    }

    /**
     * The cell reports the bubble box only; the parts drawn outside it — the avatar on the left, the
     * share button and the reply preview on the right — have to be allowed for by hand or the crop
     * cuts them off.
     */
    private static void applyBounds(Piece piece, ChatMessageCell cell, MessageObject message, int width, boolean out) {
        int left = 0;
        int right = width;
        try {
            final int boundsLeft = cell.getBoundsLeft();
            final int boundsRight = cell.getBoundsRight();
            if (boundsRight > boundsLeft) {
                left = Math.max(0, boundsLeft);
                right = Math.min(width, Math.max(left + 1, boundsRight));
            }
        } catch (Throwable ignore) {
        }
        int extraRight = out ? dp(64) : 0;
        if (out && (message.getReplyMsgId() != 0 || message.getReplyTopMsgId() != 0 || message.messageOwner.reply_to != null)) {
            extraRight += dp(170);
        }
        if (message.messageOwner.fwd_from != null) {
            extraRight += dp(70);
        }
        piece.left = Math.max(0, left - dp(14));
        piece.right = Math.min(width, Math.max(piece.left + 1, right + extraRight));
    }

    private static void loadImages(ChatMessageCell cell) {
        final ImageReceiver[] receivers = {
                cell.getPhotoImage(), cell.getBlurredPhotoImage(), cell.getAvatarImage(), cell.replyImageReceiver
        };
        for (ImageReceiver receiver : receivers) {
            if (receiver == null) {
                continue;
            }
            try {
                receiver.setAllowLoadingOnAttachedOnly(false);
                ImageLoader.getInstance().loadImageForImageReceiver(receiver);
            } catch (Throwable ignore) {
            }
        }
    }

    private static Piece renderAction(ChatActionCell cell, MessageObject message, int width, int screenHeight) {
        try {
            cell.setMessageObject(message, false);
            final ImageReceiver photo = cell.getPhotoImage();
            if (photo != null) {
                photo.setAllowLoadingOnAttachedOnly(false);
                ImageLoader.getInstance().loadImageForImageReceiver(photo);
            }
            cell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int height = cell.getMeasuredHeight();
            if (height <= 0) {
                return null;
            }
            cell.layout(0, 0, width, height);
            try {
                cell.setVisiblePart(0f, screenHeight);
            } catch (Throwable ignore) {
            }
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            cell.draw(new Canvas(bitmap));

            final Piece piece = new Piece();
            piece.bitmap = bitmap;
            piece.height = height;
            piece.topInset = 0;
            piece.left = 0;
            piece.right = width;
            return piece;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Renders all cells of an album into one piece that looks like a single media bubble. */
    private static Piece renderAlbum(Cells cells, List<Item> unit, int width, int screenHeight) {
        final ArrayList<SubBitmap> subs = new ArrayList<>(unit.size());
        for (Item item : unit) {
            final SubBitmap sub = renderAlbumCell(cells.message(), item, width, screenHeight);
            if (sub != null) {
                subs.add(sub);
            }
        }
        if (subs.isEmpty()) {
            return null;
        }
        // Build a row-by-row height map, then stitch all cells into one strip.
        final java.util.TreeMap<Integer, Integer> rowHeights = new java.util.TreeMap<>();
        for (SubBitmap sub : subs) {
            rowHeights.merge(sub.row, sub.logicalH, Math::max);
        }
        final java.util.TreeMap<Integer, Integer> rowTops = new java.util.TreeMap<>();
        int accumulated = 0;
        for (int row : rowHeights.keySet()) {
            rowTops.put(row, accumulated);
            accumulated += rowHeights.get(row);
        }
        // Cells sometimes hang above their logical row when the avatar extends past the top edge.
        int groupInset = 0;
        for (SubBitmap sub : subs) {
            final int drawTop = rowTops.getOrDefault(sub.row, 0) - sub.topInset;
            if (-drawTop > groupInset) {
                groupInset = -drawTop;
            }
        }
        int totalHeight = groupInset + accumulated;
        for (SubBitmap sub : subs) {
            final int bottom = groupInset + rowTops.getOrDefault(sub.row, 0) - sub.topInset + sub.bitmap.getHeight();
            if (bottom > totalHeight) {
                totalHeight = bottom;
            }
        }

        final Bitmap out = Bitmap.createBitmap(Math.max(1, width), Math.max(1, totalHeight), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(out);
        int albumLeft = width;
        int albumRight = 0;
        for (SubBitmap sub : subs) {
            final int y = groupInset + rowTops.getOrDefault(sub.row, 0) - sub.topInset;
            canvas.drawBitmap(sub.bitmap, sub.x, y, null);
            sub.bitmap.recycle();
            albumLeft = Math.min(albumLeft, Math.max(0, sub.contentLeft + sub.x));
            albumRight = Math.max(albumRight, Math.min(width, sub.contentRight + sub.x));
        }

        final Piece piece = new Piece();
        piece.bitmap = out;
        piece.height = accumulated;
        piece.topInset = groupInset;
        piece.left = albumLeft < albumRight ? albumLeft : 0;
        piece.right = albumLeft < albumRight ? albumRight : width;
        return piece;
    }

    private static final class SubBitmap {
        Bitmap bitmap;
        int x;
        int row;
        int topInset;
        int logicalH;
        int contentLeft;
        int contentRight;
    }

    private static SubBitmap renderAlbumCell(ChatMessageCell cell, Item item, int width, int screenHeight) {
        final int cellWidth = Math.max(1, item.width);
        cell.setParentViewSize(width, screenHeight);
        cell.setMessageObject(item.message, item.group, item.pinnedBottom, item.pinnedTop, false);
        cell.measure(View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int height = cell.getMeasuredHeight();
        if (height <= 0) {
            return null;
        }
        cell.layout(0, 0, cellWidth, height);
        cell.setParentViewSize(width, screenHeight);
        try {
            cell.setVisiblePart(0, height, screenHeight, 0f, 0f, width, screenHeight, 0, 0, 0);
        } catch (Throwable ignore) {
        }
        loadImages(cell);

        final int avatarTop = height - dp(42) - dp(4);
        final int topInset = (cell.isAvatarVisible && avatarTop < 0) ? -avatarTop : 0;

        final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, cellWidth), height + topInset, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        if (cell.drawBackgroundInParent()) {
            canvas.save();
            canvas.translate(0, topInset + cell.getPaddingTop());
            cell.drawBackgroundInternal(canvas, true);
            canvas.restore();
        }
        canvas.save();
        canvas.translate(0, topInset);
        cell.draw(canvas);
        canvas.restore();

        final SubBitmap sub = new SubBitmap();
        sub.bitmap = bitmap;
        sub.x = item.x;
        sub.row = item.row;
        sub.topInset = topInset;
        sub.logicalH = height;
        int left = 0, right = cellWidth;
        try {
            final int bl = cell.getBoundsLeft(), br = cell.getBoundsRight();
            if (br > bl) {
                left = Math.max(0, bl);
                right = Math.min(cellWidth, Math.max(left + 1, br));
            }
        } catch (Throwable ignore) {
        }
        sub.contentLeft = left;
        sub.contentRight = right;
        return sub;
    }

    /** Vertically stacks all the pieces with a small gap and crops horizontally to content. */
    private static Bitmap compose(List<Piece> pieces) {
        int globalLeft = Integer.MAX_VALUE;
        int globalRight = 0;
        long totalH = 0;
        final int gap = dp(GAP);
        final int pad = dp(PADDING);

        // Positions in logical-height space (excludes topInsets already accounted for in bitmap).
        // We need logical Y so that piece.topInset is subtracted when placing the bitmap.
        final int[] logicalY = new int[pieces.size()];
        int cursor = 0;
        int minDrawY = 0;
        int maxDrawY = 0;
        for (int a = 0; a < pieces.size(); a++) {
            final Piece p = pieces.get(a);
            logicalY[a] = cursor;
            final int drawTop = cursor - p.topInset;
            final int drawBottom = drawTop + p.bitmap.getHeight();
            if (drawTop < minDrawY) {
                minDrawY = drawTop;
            }
            if (drawBottom > maxDrawY) {
                maxDrawY = drawBottom;
            }
            globalLeft = Math.min(globalLeft, p.left);
            globalRight = Math.max(globalRight, p.right);
            cursor += p.height + (a < pieces.size() - 1 ? gap : 0);
        }

        if (globalLeft >= globalRight) {
            globalLeft = 0;
            int maxW = 0;
            for (Piece p : pieces) {
                maxW = Math.max(maxW, p.bitmap.getWidth());
            }
            globalRight = maxW;
        }
        final int contentW = Math.max(1, globalRight - globalLeft);
        final int contentH = Math.max(1, maxDrawY - minDrawY);

        final Bitmap out = Bitmap.createBitmap(contentW + pad * 2, contentH + pad * 2, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(out);
        // Fill with the chat wallpaper (dominant colour) or fall back to opaque black so the PNG
        // never has a transparent background that looks wrong when shared to non-Telegram apps.
        final Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaper != null) {
            wallpaper.setBounds(0, 0, out.getWidth(), out.getHeight());
            wallpaper.draw(canvas);
        } else {
            canvas.drawColor(0xFF1A1A1A);
        }

        for (int a = 0; a < pieces.size(); a++) {
            final Piece p = pieces.get(a);
            final float dx = pad - globalLeft;
            final float dy = pad + logicalY[a] - p.topInset - minDrawY;
            canvas.drawBitmap(p.bitmap, dx, dy, null);
            p.bitmap.recycle();
        }
        return out;
    }
}
