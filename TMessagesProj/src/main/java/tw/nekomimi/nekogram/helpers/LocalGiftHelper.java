package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.AmountUtils;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * "Серверная отправка NFT/Stars/Gram/Premium" — despite the name this is entirely client side.
 * Every entry here fabricates a service message, writes it into the local database and pushes it
 * into the open chat. Nothing leaves the device: the peer on the other end sees nothing, and the
 * message disappears from other clients (and from this one after a cache wipe).
 * <p>
 * The flow is a port of the exteraGram plugin the feature was modelled on: pick a base gift from
 * the real star-gift catalogue, pull its upgrade preview to get genuine model/pattern/backdrop
 * attributes, then assemble a {@link TL_stars.TL_starGiftUnique} out of the chosen ones.
 */
public class LocalGiftHelper {

    /**
     * What one star costs in USD, used only to fill the "price" line the gift bubble prints.
     * Same figure the reference plugin used.
     */
    private static final double STAR_PRICE_USD = 0.0162;
    /** Store prices of a premium gift per duration, so the bubble shows a believable amount. */
    private static final int[] PREMIUM_MONTHS = {1, 3, 6, 12};
    private static final double[] PREMIUM_PRICES_USD = {4.99, 14.99, 28.99, 59.99};

    private static final int CATALOG_PAGE_SIZE = 30;
    private static final long NANOTONS_IN_TON = 1_000_000_000L;
    /** How many live listings one average is computed from. */
    private static final int MARKET_SAMPLE_SIZE = 30;
    /** A catalogue page is 30 rows, so the market lookups are metered instead of fired at once. */
    private static final int MARKET_MAX_IN_FLIGHT = 3;

    private LocalGiftHelper() {
    }

    // ===== entry point =====

    /**
     * Opens the root menu shown by the chat overflow item. Four entries, in the order the feature
     * was specified: NFT, Stars, Premium, TON.
     */
    public static void showSheet(BaseFragment fragment, long dialogId) {
        Context context = fragment == null ? null : fragment.getParentActivity();
        if (context == null) {
            return;
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.localGiftSender), true);
        builder.setItems(
                new CharSequence[]{
                        getString(R.string.LocalGiftSenderNFT),
                        getString(R.string.LocalGiftSenderStars),
                        getString(R.string.LocalGiftSenderPremium),
                        getString(R.string.LocalGiftSenderTon)
                },
                new int[]{
                        R.drawable.menu_feature_unique,
                        R.drawable.baseline_stars_24,
                        R.drawable.menu_premium_main,
                        R.drawable.menu_gram_24
                },
                (dialog, which) -> {
                    switch (which) {
                        case 0:
                            openCatalog(fragment, dialogId);
                            break;
                        case 1:
                            showSimpleForm(fragment, dialogId, Kind.STARS);
                            break;
                        case 2:
                            showSimpleForm(fragment, dialogId, Kind.PREMIUM);
                            break;
                        case 3:
                            showSimpleForm(fragment, dialogId, Kind.TON);
                            break;
                    }
                }
        );
        fragment.showDialog(builder.create());
    }

    // ===== NFT: catalogue -> attributes -> constructor =====

    /**
     * Only gifts the server marks as upgradable have a set of unique attributes behind them, so
     * anything else would give an empty constructor.
     */
    private static ArrayList<TL_stars.StarGift> upgradableGifts(int account) {
        StarsController controller = StarsController.getInstance(account);
        controller.loadStarGifts();
        ArrayList<TL_stars.StarGift> result = new ArrayList<>();
        for (TL_stars.StarGift gift : controller.gifts) {
            if (gift != null && (gift.upgrade_stars > 0 || gift instanceof TL_stars.TL_starGiftUnique)) {
                result.add(gift);
            }
        }
        return result;
    }

    private static void openCatalog(BaseFragment fragment, long dialogId) {
        final int account = fragment.getCurrentAccount();
        ArrayList<TL_stars.StarGift> gifts = upgradableGifts(account);
        if (!gifts.isEmpty()) {
            showCatalog(fragment, dialogId, gifts);
            return;
        }
        // The catalogue was not in cache; loadStarGifts() above kicked off the request, give it a
        // moment and retry once rather than making the user tap again.
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.LocalGiftSenderLoadingCatalog)).show();
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<TL_stars.StarGift> retried = upgradableGifts(account);
            if (retried.isEmpty()) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.LocalGiftSenderCatalogEmpty)).show();
            } else {
                showCatalog(fragment, dialogId, retried);
            }
        }, 2000);
    }

    private static void showCatalog(BaseFragment fragment, long dialogId, ArrayList<TL_stars.StarGift> gifts) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(40), 0, dp(16));
        root.setClipToPadding(false);
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);
        scrollView.addView(root);

        root.addView(title(context, resourcesProvider, getString(R.string.LocalGiftSenderPickBase)), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 12));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView showMore = new TextView(context);
        showMore.setText(getString(R.string.LocalGiftSenderShowMore));
        showMore.setGravity(Gravity.CENTER);
        showMore.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        showMore.setTypeface(AndroidUtilities.bold());
        showMore.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        showMore.setPadding(0, dp(14), 0, dp(14));
        root.addView(showMore, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final int[] shown = {0};
        Runnable loadPage = new Runnable() {
            @Override
            public void run() {
                int end = Math.min(shown[0] + CATALOG_PAGE_SIZE, gifts.size());
                for (int i = shown[0]; i < end; i++) {
                    TL_stars.StarGift gift = gifts.get(i);
                    list.addView(catalogRow(context, resourcesProvider, fragment.getCurrentAccount(), gift, () -> {
                        sheet.dismiss();
                        loadAttributes(fragment, dialogId, gift);
                    }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
                shown[0] = end;
                showMore.setVisibility(shown[0] >= gifts.size() ? View.GONE : View.VISIBLE);
            }
        };
        showMore.setOnClickListener(v -> loadPage.run());
        loadPage.run();

        sheet.setCustomView(scrollView);
        fragment.showDialog(sheet);
    }

    private static View catalogRow(Context context, Theme.ResourcesProvider resourcesProvider, int account, TL_stars.StarGift gift, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(Theme.getSelectorDrawable(false, resourcesProvider));

        BackupImageView imageView = new BackupImageView(context);
        TLRPC.Document document = gift.getDocument();
        if (document != null) {
            imageView.setImage(ImageLocation.getForDocument(document), "50_50", null, null, document);
        }
        row.addView(imageView, LayoutHelper.createLinear(48, 48, 0, 0, 16, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(context);
        name.setText(giftLabel(gift));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView stars = new TextView(context);
        stars.setText(priceLabel(gift));
        stars.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        stars.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        texts.addView(stars, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // The floor above is what the catalogue carries; the real average replaces it as soon as the
        // market listings arrive, which is a request per gift and therefore cannot be waited on.
        MarketPrices.request(account, gift, average -> stars.setText(average.formatAsDecimalSpaced()));

        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /**
     * The catalog is priced in stars server side, but what people actually quote is the TON figure,
     * so show that: the resale floor when the gift is on the market, otherwise the star price run
     * through the live star→USD→TON rates the app already keeps. If those rates are missing we fall
     * back to stars rather than printing a nonsense number.
     * <p>
     * This is only what is shown until {@link MarketPrices} answers with the real average.
     */
    private static String priceLabel(TL_stars.StarGift gift) {
        AmountUtils.Amount resell = gift.getResellAmount(AmountUtils.Currency.TON);
        if (resell != null && !resell.isZero()) {
            return resell.formatAsDecimalSpaced();
        }
        if (gift.stars > 0 && tonUsdRate() > 0) {
            AmountUtils.Amount converted = AmountUtils.Amount
                    .fromDecimal(gift.stars, AmountUtils.Currency.STARS)
                    .convertTo(AmountUtils.Currency.TON);
            if (converted != null && !converted.isZero()) {
                return converted.formatAsDecimalSpaced();
            }
        }
        return "⭐ " + gift.stars;
    }

    /**
     * The average asking price of a gift on the resale market.
     * <p>
     * {@code StarGift.resell_min_stars} — what the catalogue carries and what the row used to show —
     * is the <i>floor</i>, the cheapest copy anyone is asking for. That is the one number people
     * never quote, because a single underpriced listing drags it far below what the gift really
     * changes hands for. There is no average anywhere in the TL layer, so it is computed here from
     * the listings themselves: {@code getResaleStarGifts} returns the actual asks, and the mean of
     * the cheapest listings on the book is what a buyer would realistically pay.
     * <p>
     * Only the public market is read — this asks the same question the resale tab asks when it is
     * opened, and nothing is sent anywhere.
     */
    private static final class MarketPrices {

        /** Averages hold for a session; the market does not move fast enough to warrant re-asking. */
        private static final HashMap<Long, AmountUtils.Amount> cache = new HashMap<>();
        private static final ArrayList<Runnable> queue = new ArrayList<>();
        private static int inFlight;

        private MarketPrices() {
        }

        /**
         * Reports the average to {@code onResult} on the UI thread, once, if there is one. Gifts
         * with nothing on sale never call back, which leaves the row showing its mint price.
         */
        static void request(int account, TL_stars.StarGift gift, Utilities.Callback<AmountUtils.Amount> onResult) {
            if (gift == null || gift.availability_resale <= 0) {
                return;
            }
            final long giftId = gift.id;
            final AmountUtils.Amount cached = cache.get(giftId);
            if (cached != null) {
                onResult.run(cached);
                return;
            }
            enqueue(() -> load(account, giftId, onResult));
        }

        /**
         * A page of the catalogue is 30 rows and each one wants its own market lookup, so they are
         * run a few at a time. Firing all of them at once would stall the connection the sheet's
         * stickers are still loading over.
         */
        private static void enqueue(Runnable request) {
            if (inFlight < MARKET_MAX_IN_FLIGHT) {
                inFlight++;
                request.run();
            } else {
                queue.add(request);
            }
        }

        private static void onFinished() {
            inFlight--;
            while (inFlight < MARKET_MAX_IN_FLIGHT && !queue.isEmpty()) {
                inFlight++;
                queue.remove(0).run();
            }
        }

        private static void load(int account, long giftId, Utilities.Callback<AmountUtils.Amount> onResult) {
            final TL_stars.getResaleStarGifts req = new TL_stars.getResaleStarGifts();
            req.gift_id = giftId;
            req.offset = "";
            req.limit = MARKET_SAMPLE_SIZE;
            // Cheapest first, so the sample is the part of the book a buyer would actually clear,
            // and attributes_hash 0 asks for the whole market rather than a filtered slice.
            req.sort_by_price = true;
            req.flags |= 1;
            req.attributes_hash = 0;
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                onFinished();
                if (!(res instanceof TL_stars.resaleStarGifts)) {
                    return;
                }
                final AmountUtils.Amount average = average(((TL_stars.resaleStarGifts) res).gifts);
                if (average == null) {
                    return;
                }
                cache.put(giftId, average);
                onResult.run(average);
            }));
        }

        /**
         * Listings can be priced in either currency, so everything is converted to TON before the
         * mean is taken — averaging the two side by side would be meaningless.
         */
        @Nullable
        private static AmountUtils.Amount average(ArrayList<TL_stars.StarGift> listings) {
            if (listings == null || listings.isEmpty()) {
                return null;
            }
            long totalNanotons = 0;
            int counted = 0;
            for (TL_stars.StarGift listing : listings) {
                final AmountUtils.Amount price = priceOf(listing);
                if (price == null || price.isZero()) {
                    continue;
                }
                totalNanotons += price.asNano();
                counted++;
            }
            if (counted == 0) {
                return null;
            }
            // Two decimals: the market quotes TON that way, and it keeps the row from printing a
            // long tail of digits the average would otherwise carry.
            return AmountUtils.Amount.fromNano(totalNanotons / counted, AmountUtils.Currency.TON).round(2);
        }

        @Nullable
        private static AmountUtils.Amount priceOf(TL_stars.StarGift listing) {
            if (listing == null) {
                return null;
            }
            final AmountUtils.Amount ton = listing.getResellAmount(AmountUtils.Currency.TON);
            if (ton != null && !ton.isZero()) {
                return ton;
            }
            final AmountUtils.Amount stars = listing.getResellAmount(AmountUtils.Currency.STARS);
            if (stars == null || stars.isZero() || tonUsdRate() <= 0) {
                return null;
            }
            return stars.convertTo(AmountUtils.Currency.TON);
        }

        /** The average of the gift the constructor was opened from, if one was ever fetched. */
        @Nullable
        static AmountUtils.Amount cached(long giftId) {
            return cache.get(giftId);
        }
    }

    private static double tonUsdRate() {
        try {
            return MessagesController.getInstance(UserConfig.selectedAccount).config.tonUsdRate.get();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String giftLabel(TL_stars.StarGift gift) {
        if (gift.title != null && !gift.title.isEmpty()) {
            return gift.title;
        }
        return "#" + gift.id;
    }

    /**
     * The attributes are fetched from the real upgrade preview, which is what makes the fake NFT
     * look right — the sticker, the pattern and the palette are genuine assets. If the request
     * fails we still open the constructor, just without selectors.
     */
    private static void loadAttributes(BaseFragment fragment, long dialogId, TL_stars.StarGift gift) {
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.LocalGiftSenderLoadingAttributes)).show();
        StarsController.getInstance(fragment.getCurrentAccount())
                .getStarGiftPreview(gift.id, preview -> showConstructor(fragment, dialogId, gift, preview));
    }

    private static void showConstructor(BaseFragment fragment, long dialogId, TL_stars.StarGift gift, @Nullable TL_stars.starGiftUpgradePreview preview) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        ArrayList<TL_stars.StarGiftAttribute> models = new ArrayList<>();
        ArrayList<TL_stars.StarGiftAttribute> patterns = new ArrayList<>();
        ArrayList<TL_stars.StarGiftAttribute> backdrops = new ArrayList<>();
        if (preview != null) {
            for (TL_stars.StarGiftAttribute attribute : preview.sample_attributes) {
                if (attribute instanceof TL_stars.starGiftAttributeModel) {
                    models.add(attribute);
                } else if (attribute instanceof TL_stars.starGiftAttributePattern) {
                    patterns.add(attribute);
                } else if (attribute instanceof TL_stars.starGiftAttributeBackdrop) {
                    backdrops.add(attribute);
                }
            }
        }

        // needFocus has to be true here: with false BottomSheet ORs FLAG_ALT_FOCUSABLE_IM into the
        // window flags, which stops the window from ever taking IME focus, so tapping any of the
        // fields below would move the cursor but never raise the keyboard. true also switches the
        // window to SOFT_INPUT_ADJUST_RESIZE so the ScrollView shrinks around the keyboard.
        BottomSheet sheet = new BottomSheet(context, true, resourcesProvider);
        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        // Top has to clear the sheet's drag handle — a 16 dp gap gets the title's ascent clipped in
        // half and the first caption drawn under the handle. 40 dp leaves room for both.
        root.setPadding(0, dp(40), 0, dp(16));
        root.setClipToPadding(false);
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);
        scrollView.addView(root);

        root.addView(title(context, resourcesProvider, getString(R.string.LocalGiftSenderConstructor)), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));

        final int[] selected = {0, 0, 0};
        addSelector(context, resourcesProvider, root, getString(R.string.LocalGiftSenderModel), models, selected, 0);
        addSelector(context, resourcesProvider, root, getString(R.string.LocalGiftSenderPattern), patterns, selected, 1);
        addSelector(context, resourcesProvider, root, getString(R.string.LocalGiftSenderBackdrop), backdrops, selected, 2);

        EditTextBoldCursor titleField = addInput(context, resourcesProvider, root, getString(R.string.LocalGiftSenderName),
                giftLabel(gift), InputType.TYPE_CLASS_TEXT);
        EditTextBoldCursor slugField = addInput(context, resourcesProvider, root, getString(R.string.LocalGiftSenderSlug),
                gift.slug != null && !gift.slug.isEmpty() ? gift.slug : "nft", InputType.TYPE_CLASS_TEXT);
        EditTextBoldCursor numField = addInput(context, resourcesProvider, root, getString(R.string.LocalGiftSenderNum),
                "1", InputType.TYPE_CLASS_NUMBER);
        EditTextBoldCursor totalField = addInput(context, resourcesProvider, root, getString(R.string.LocalGiftSenderTotal),
                String.valueOf(gift.availability_total > 0 ? gift.availability_total : 10000), InputType.TYPE_CLASS_NUMBER);

        root.addView(actionButton(context, resourcesProvider, getString(R.string.LocalGiftSenderCreate), v -> {
            ArrayList<TL_stars.StarGiftAttribute> chosen = new ArrayList<>();
            if (!models.isEmpty()) chosen.add(models.get(selected[0]));
            if (!patterns.isEmpty()) chosen.add(patterns.get(selected[1]));
            if (!backdrops.isEmpty()) chosen.add(backdrops.get(selected[2]));
            sendNft(fragment, dialogId, gift, chosen,
                    titleField.getText().toString(),
                    slugField.getText().toString(),
                    parseInt(numField.getText().toString(), 1),
                    parseInt(totalField.getText().toString(), 10000));
            sheet.dismiss();
        }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 0));

        sheet.setCustomView(scrollView);
        fragment.showDialog(sheet);
    }

    private static void addSelector(Context context, Theme.ResourcesProvider resourcesProvider,
                                    LinearLayout root, String label, ArrayList<TL_stars.StarGiftAttribute> items,
                                    int[] selected, int index) {
        if (items.isEmpty()) {
            return;
        }
        TextSettingsCell cell = new TextSettingsCell(context, resourcesProvider);
        cell.setTextAndValue(label, attributeLabel(items.get(selected[index])), true);
        cell.setOnClickListener(v -> {
            ArrayList<String> names = new ArrayList<>();
            for (TL_stars.StarGiftAttribute attribute : items) {
                names.add(attributeLabel(attribute));
            }
            PopupHelper.show(names, label, selected[index], context, which -> {
                selected[index] = which;
                cell.setTextAndValue(label, attributeLabel(items.get(which)), true);
            }, resourcesProvider);
        });
        root.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** Rarity is what makes one variant interesting over another, so show it next to the name. */
    private static String attributeLabel(TL_stars.StarGiftAttribute attribute) {
        String name = attribute.name == null ? "" : attribute.name;
        int permille = attribute.getRarityPermille();
        if (permille <= 0) {
            return name;
        }
        return String.format(Locale.US, "%s (%.1f%%)", name, permille / 10f);
    }

    // ===== Stars / Premium / TON =====

    private enum Kind {STARS, PREMIUM, TON}

    private static void showSimpleForm(BaseFragment fragment, long dialogId, Kind kind) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(6), 0, dp(6), 0);

        final String label;
        final String defaultValue;
        final int inputType;
        switch (kind) {
            case PREMIUM:
                label = getString(R.string.LocalGiftSenderMonths);
                defaultValue = "3";
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case TON:
                label = getString(R.string.LocalGiftSenderTonAmount);
                defaultValue = "10";
                inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                break;
            case STARS:
            default:
                label = getString(R.string.LocalGiftSenderStarsAmount);
                defaultValue = "500";
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
        }
        EditTextBoldCursor field = addInput(context, resourcesProvider, layout, label, defaultValue, inputType);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.LocalGiftSenderSetup));
        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.LocalGiftSenderExecute), (dialog, which) -> {
            String value = field.getText().toString();
            switch (kind) {
                case PREMIUM:
                    sendPremium(fragment, dialogId, parseInt(value, 3));
                    break;
                case TON:
                    sendTon(fragment, dialogId, parseDouble(value, 10));
                    break;
                case STARS:
                default:
                    sendStars(fragment, dialogId, parseInt(value, 500));
                    break;
            }
        });
        fragment.showDialog(builder.create());
    }

    // ===== message builders =====

    private static void sendNft(BaseFragment fragment, long dialogId, TL_stars.StarGift base,
                                ArrayList<TL_stars.StarGiftAttribute> attributes, String title, String slug,
                                int num, int total) {
        TL_stars.TL_starGiftUnique gift = new TL_stars.TL_starGiftUnique();
        // A local id that cannot collide with a real gift; nothing ever looks it up server side.
        gift.id = -Math.abs(System.currentTimeMillis());
        gift.gift_id = base.id;
        gift.title = title == null || title.isEmpty() ? giftLabel(base) : title;
        gift.slug = slug == null || slug.isEmpty() ? "nft" : slug;
        gift.num = Math.max(1, num);
        gift.limited = true;
        gift.availability_issued = Math.max(1, num);
        gift.availability_total = Math.max(gift.availability_issued, total);
        gift.attributes = attributes;
        gift.sticker = documentOf(attributes, base);
        applyValue(gift, base);

        TLRPC.TL_messageActionStarGiftUnique action = new TLRPC.TL_messageActionStarGiftUnique();
        action.gift = gift;
        action.upgrade = false;
        action.saved = false;
        action.refunded = false;
        // Everything here is sent by me to the person I am talking to, so it is a plain send and
        // never the "someone handed this to you" variant.
        action.transferred = false;

        deliver(fragment, dialogId, action);
    }

    private static void sendStars(BaseFragment fragment, long dialogId, int stars) {
        TLRPC.TL_messageActionGiftStars action = new TLRPC.TL_messageActionGiftStars();
        action.stars = Math.max(0, stars);
        action.currency = "USD";
        action.amount = Math.round(action.stars * STAR_PRICE_USD * 100);
        deliver(fragment, dialogId, action);
    }

    private static void sendPremium(BaseFragment fragment, long dialogId, int months) {
        TLRPC.TL_messageActionGiftPremium action = new TLRPC.TL_messageActionGiftPremium();
        action.months = Math.max(1, months);
        // The current constructor serialises days and derives months back from it, so both have to
        // agree or the message reads as a different duration once it comes back out of the cache.
        action.days = action.months * 30;
        action.currency = "USD";
        action.amount = Math.round(premiumPriceUsd(action.months) * 100);
        deliver(fragment, dialogId, action);
    }

    private static void sendTon(BaseFragment fragment, long dialogId, double tons) {
        TLRPC.TL_messageActionGiftTon action = new TLRPC.TL_messageActionGiftTon();
        action.currency = "USD";
        // No exchange rate is available offline; the bubble shows the TON figure first anyway.
        action.amount = Math.round(Math.max(0, tons) * 100);
        action.cryptoCurrency = "TON";
        action.cryptoAmount = (long) (Math.max(0, tons) * NANOTONS_IN_TON);
        // Flag 0 is what makes the bubble print the crypto amount instead of the fiat one; it also
        // gates transaction_id, which therefore has to be present.
        action.transaction_id = "";
        action.flags |= 1;
        deliver(fragment, dialogId, action);
    }

    /**
     * The gift card prints a "Value" row, and one that says nothing gives the NFT away at a glance.
     * Fill it from the market average the catalogue quoted for the base gift, falling back to the
     * mint price when the gift has no market to average.
     */
    private static void applyValue(TL_stars.TL_starGiftUnique gift, TL_stars.StarGift base) {
        double usd = 0;
        final AmountUtils.Amount average = MarketPrices.cached(base.id);
        if (average != null && !average.isZero()) {
            usd = average.convertToUsd();
        }
        if (usd <= 0 && base.stars > 0) {
            usd = base.stars * STAR_PRICE_USD;
        }
        if (usd <= 0) {
            return;
        }
        gift.value_currency = "USD";
        // The card formats this through BillingController, which works in the currency's minor unit.
        gift.value_amount = Math.round(usd * 100);
        gift.value_usd_amount = gift.value_amount;
        // Flag 8 both gates the three fields when the message is serialised into the local cache and
        // is what the card checks before drawing the row at all.
        gift.flags |= 256;
    }

    private static double premiumPriceUsd(int months) {
        for (int i = 0; i < PREMIUM_MONTHS.length; i++) {
            if (PREMIUM_MONTHS[i] == months) {
                return PREMIUM_PRICES_USD[i];
            }
        }
        return PREMIUM_PRICES_USD[1];
    }

    private static TLRPC.Document documentOf(ArrayList<TL_stars.StarGiftAttribute> attributes, TL_stars.StarGift base) {
        for (TL_stars.StarGiftAttribute attribute : attributes) {
            if (attribute instanceof TL_stars.starGiftAttributeModel) {
                TLRPC.Document document = ((TL_stars.starGiftAttributeModel) attribute).document;
                if (document != null) {
                    return document;
                }
            }
        }
        return base.getDocument();
    }

    /**
     * Writes the fabricated service message to storage and shows it in the chat. This is the only
     * place that touches persistence, so everything the sheet produces is local by construction.
     * The message is always outgoing — from me to the person I am talking to.
     */
    private static void deliver(BaseFragment fragment, long dialogId, TLRPC.MessageAction action) {
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);
        final UserConfig userConfig = UserConfig.getInstance(account);

        TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.local_id = message.id = userConfig.getNewMessageId();
        message.dialog_id = dialogId;
        message.peer_id = controller.getPeer(dialogId);
        message.date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.action = action;
        message.unread = false;
        // TL_messageService derives the out flag from this boolean when it serialises, so setting
        // the bit by hand would only get overwritten.
        message.out = true;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        TLRPC.TL_peerUser self = new TLRPC.TL_peerUser();
        self.user_id = userConfig.getClientUserId();
        message.from_id = self;
        userConfig.saveConfig(false);

        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        messages.add(message);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(new MessageObject(account, message, true, true));

        MessagesStorage.getInstance(account).putMessages(messages, true, true, false, 0, 0, 0);
        controller.updateInterfaceWithMessages(dialogId, objects, 0);
        // updateInterfaceWithMessages moves the dialog and updates its cached top message, but the
        // dialogsNeedReload notification is posted by the callers in the real update pipeline, not
        // by it. Without this the chat list keeps drawing the preview it had before the gift.
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);

        // SovietGram sync: this gift is local-only by default. Mirror it to the backend so the
        // recipient's client can rebuild it as an incoming message, and so it shows on the sender's
        // public gift showcase. Sent under the account that wrote the gift, since the server takes the
        // sender's identity from that account's token. No-op for group/channel dialogs and when that
        // account has no token yet.
        SovietGramGiftSync.pushGift(account, dialogId, action);

        AndroidUtilities.runOnUIThread(() -> {
            if (LaunchActivity.instance != null && LaunchActivity.instance.getFireworksOverlay() != null) {
                LaunchActivity.instance.getFireworksOverlay().start(true);
            }
        }, 500);
    }

    // ===== small view builders =====

    private static TextView title(Context context, Theme.ResourcesProvider resourcesProvider, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        return textView;
    }

    private static EditTextBoldCursor addInput(Context context, Theme.ResourcesProvider resourcesProvider,
                                               LinearLayout root, String label, String value, int inputType) {
        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        caption.setPadding(0, dp(10), 0, dp(4));
        root.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor, resourcesProvider));
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setPadding(0, 0, 0, dp(6));
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setText(value);
        root.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));
        return editText;
    }

    private static TextView actionButton(Context context, Theme.ResourcesProvider resourcesProvider, String text, View.OnClickListener listener) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        button.setBackground(Theme.AdaptiveRipple.filledRect(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), 8));
        button.setOnClickListener(listener);
        return button;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (Exception e) {
            return fallback;
        }
    }
}
