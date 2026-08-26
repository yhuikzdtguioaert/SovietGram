package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.CustomProfileRows;
import tw.nekomimi.nekogram.helpers.PopupHelper;

/**
 * The order of the profile's own rows, and which of them the look hides.
 *
 * <p>These are Telegram's rows — the phone number, the bio, the username — not rows the look invents;
 * those are {@link CustomProfileBlocksActivity}. A look names them by the reference's own ids, so an
 * order set here installs identically on both apps.
 *
 * <p>A row the profile is not showing at all still appears here: a look is written once and worn on
 * whatever profile it lands on, so hiding the phone number is worth setting even on a profile that
 * has none.
 */
public class CustomProfileRowsActivity extends CustomProfileListActivity {

    @Override
    protected String title() {
        return getString(R.string.CustomProfileRows);
    }

    @Override
    protected void buildRows() {
        header(getString(R.string.CustomProfileRowsOrder));
        final List<String> order = CustomProfileRows.editableOrder();
        for (int i = 0; i < order.size(); i++) {
            final String id = order.get(i);
            final boolean hidden = CustomProfileRows.hiddenStored(id);
            final int at = i;
            setting(label(id),
                    getString(hidden ? R.string.CustomProfileRowHidden : R.string.CustomProfileRowShown),
                    () -> menu(order, at, id, hidden));
        }
        shadow();
        setting(getString(R.string.CustomProfileRowsReset), null, this::confirmReset);
        info(getString(R.string.CustomProfileRowsInfo));
    }

    private void menu(List<String> order, int at, String id, boolean hidden) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> options = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        options.add(getString(hidden ? R.string.CustomProfileRowShow : R.string.CustomProfileRowHide));
        actions.add(() -> CustomProfileRows.setHidden(id, !hidden));
        if (at > 0) {
            options.add(getString(R.string.CustomProfileRowUp));
            actions.add(() -> move(order, at, at - 1));
        }
        if (at + 1 < order.size()) {
            options.add(getString(R.string.CustomProfileRowDown));
            actions.add(() -> move(order, at, at + 1));
        }
        PopupHelper.show(options, label(id), -1, getParentActivity(), index -> {
            if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
                rebuild();
            }
        });
    }

    private static void move(List<String> order, int from, int to) {
        final List<String> next = new ArrayList<>(order);
        next.add(to, next.remove(from));
        CustomProfileRows.setOrder(next);
    }

    private void confirmReset() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileRowsReset))
                .setMessage(getString(R.string.CustomProfileRowsResetInfo))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    CustomProfileRows.reset();
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** Each row's own label, named after the id the look uses for it. */
    private static String label(String id) {
        return getString("CustomProfileRow_" + id);
    }
}
