package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.function.Consumer;

import tw.nekomimi.nekogram.helpers.CustomProfilePresets;
import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.SovietGramAccountScope;
import tw.nekomimi.nekogram.helpers.WorkshopHelper;

/** One-tap appearance switching; long-press a saved slot to replace or clear it. */
public class CustomProfilePresetsActivity extends CustomProfileListActivity {
    private boolean busy;

    @Override
    protected String title() {
        return getString(R.string.CustomProfilePresets);
    }

    @Override
    protected void buildRows() {
        header(getString(R.string.CustomProfilePresetSlots));
        boolean live = SovietGramAccountScope.isLive(currentAccount);
        for (int slot = 0; slot < CustomProfilePresets.SLOT_COUNT; slot++) {
            final int index = slot;
            boolean saved = live && CustomProfilePresets.has(currentAccount, slot);
            Row row = setting(slotTitle(slot), getString(busy ? R.string.CustomProfilePresetBusy
                            : saved ? R.string.CustomProfilePresetSaved : R.string.CustomProfilePresetEmpty),
                    () -> {
                        if (busy || !SovietGramAccountScope.isLive(currentAccount)) return;
                        if (CustomProfilePresets.has(currentAccount, index)) apply(index);
                        else save(index);
                    });
            row.onLongClick = () -> menu(index);
        }
        shadow();
        setting(getString(R.string.CustomProfileWorkshop), null, () -> {
            if (busy) return;
            presentFragment(new WorkshopActivity(WorkshopHelper.KIND_PROFILE));
        });
        info(getString(R.string.CustomProfilePresetInfo));
    }

    private String slotTitle(int slot) {
        return LocaleController.formatString("CustomProfilePresetSlot", R.string.CustomProfilePresetSlot, slot + 1);
    }

    private void menu(int slot) {
        if (busy || getParentActivity() == null || !SovietGramAccountScope.isLive(currentAccount)) return;
        boolean saved = CustomProfilePresets.has(currentAccount, slot);
        ArrayList<String> options = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (saved) {
            options.add(getString(R.string.CustomProfilePresetApply));
            actions.add(() -> apply(slot));
        }
        options.add(getString(R.string.CustomProfilePresetSave));
        actions.add(() -> save(slot));
        if (saved) {
            options.add(getString(R.string.CustomProfilePresetClear));
            actions.add(() -> confirm(getString(R.string.CustomProfilePresetClear),
                    getString(R.string.CustomProfilePresetClearInfo), () -> runOperation(
                            done -> CustomProfilePresets.clearAsync(currentAccount, slot, done),
                            R.string.CustomProfilePresetCleared)));
        }
        PopupHelper.show(options, slotTitle(slot), -1, getParentActivity(), index -> {
            if (index >= 0 && index < actions.size()) actions.get(index).run();
        });
    }

    private void save(int slot) {
        if (busy || !SovietGramAccountScope.isLive(currentAccount)) return;
        Runnable operation = () -> runOperation(
                done -> CustomProfilePresets.saveAsync(currentAccount, slot, done), R.string.CustomProfilePresetStored);
        if (CustomProfilePresets.has(currentAccount, slot)) {
            confirm(getString(R.string.CustomProfilePresetSave),
                    getString(R.string.CustomProfilePresetOverwriteInfo), operation);
        } else {
            operation.run();
        }
    }

    private void apply(int slot) {
        runOperation(done -> CustomProfilePresets.applyAsync(currentAccount, slot, done),
                R.string.CustomProfilePresetApplied);
    }

    private void confirm(String title, String message, Runnable operation) {
        if (busy || getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(title).setMessage(message)
                .setPositiveButton(getString(R.string.OK), (dialog, which) -> operation.run())
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    private void runOperation(Consumer<Consumer<Boolean>> operation, int successMessage) {
        if (busy || !SovietGramAccountScope.isLive(currentAccount)) return;
        busy = true;
        rebuild();
        operation.accept(success -> {
            busy = false;
            if (isFinished || getParentActivity() == null) return;
            rebuild();
            if (success) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(successMessage)).show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.CustomProfilePresetFailed)).show();
            }
        });
    }
}
