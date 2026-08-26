package tw.nekomimi.nekogram.config.cell;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.Cells.TextSettingsCell;

import java.util.function.Supplier;

import tw.nekomimi.nekogram.config.CellGroup;

public class ConfigCellTextDynamic extends AbstractConfigCell implements WithOnClick {
    private final Supplier<String> titleSupplier;
    private final Supplier<String> valueSupplier;
    private final Runnable onClick;
    private boolean enabled = true;
    private TextSettingsCell cell;

    public ConfigCellTextDynamic(Supplier<String> titleSupplier, Supplier<String> valueSupplier, Runnable onClick) {
        this.titleSupplier = titleSupplier;
        this.valueSupplier = valueSupplier;
        this.onClick = onClick;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (this.cell != null) {
            this.cell.setEnabled(this.enabled);
        }
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        TextSettingsCell cell = (TextSettingsCell) holder.itemView;
        this.cell = cell;
        String title = titleSupplier == null ? "" : titleSupplier.get();
        String value = valueSupplier == null ? "" : valueSupplier.get();
        cell.setTextAndValue(title, value, cellGroup.needSetDivider(this));
        cell.setEnabled(enabled);
    }

    public void onClick() {
        if (!enabled) {
            return;
        }
        if (onClick != null) {
            try {
                onClick.run();
            } catch (Exception ignored) {
            }
        }
    }
}
