package tw.nekomimi.nekogram.config.cell;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayDeque;

import tw.nekomimi.nekogram.config.CellGroup;

/** UI-thread cell transactions, independent of the settings fragment subclass. */
final class ConfigCellListUpdate {
    private final ArrayDeque<Update> pending = new ArrayDeque<>();
    private RecyclerListView postedTo;
    private boolean draining;

    void run(CellGroup group, Runnable action) {
        Update update = new Update(group, action);
        // Nested refreshes in a transaction must precede its settings callback.
        if (draining && update.isCurrent() && !update.isBusy()) {
            action.run();
            return;
        }
        pending.add(update);
        drain();
    }

    private void drain() {
        if (draining) {
            return;
        }
        draining = true;
        try {
            while (!pending.isEmpty()) {
                Update update = pending.peek();
                if (!update.isCurrent()) {
                    pending.remove();
                    continue;
                }
                if (update.isBusy()) {
                    final RecyclerListView target = update.view;
                    if (postedTo != target) {
                        postedTo = target;
                        target.post(() -> {
                            if (postedTo == target) {
                                postedTo = null;
                            }
                            drain();
                        });
                    }
                    return;
                }
                pending.remove().action.run();
            }
        } finally {
            draining = false;
        }
    }

    private static final class Update {
        final CellGroup group;
        final RecyclerListView view;
        final RecyclerListView.SelectionAdapter adapter;
        final BaseFragment fragment;
        final Runnable action;

        Update(CellGroup group, Runnable action) {
            this.group = group;
            this.view = group.listView;
            this.adapter = group.getListAdapter();
            this.fragment = group.thisFragment;
            this.action = action;
        }

        boolean isCurrent() {
            return group.listView == view && group.getListAdapter() == adapter
                    && group.thisFragment == fragment && (fragment == null || !fragment.isFinished)
                    && (view == null || view.getAdapter() == adapter);
        }

        boolean isBusy() {
            return view != null && view.isComputingLayout();
        }
    }
}
