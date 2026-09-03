package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.TextSettingsCell;

import tw.nekomimi.nekogram.DatacenterActivity;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    private static final String SOURCE_CODE_URL = "https://github.com/fxck123/SovietGram";

    private int sovietGramChannelRow;
    private int nagramXChannelRow;
    private int sourceCodeRow;
    private int datacenterStatusRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        sovietGramChannelRow = addRow();
        nagramXChannelRow = addRow();
        sourceCodeRow = addRow();
        datacenterStatusRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == sovietGramChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("SovietUnionGram", NekoAboutActivity.this, 1);
        } else if (position == nagramXChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX", NekoAboutActivity.this, 1);
        } else if (position == sourceCodeRow) {
            // The repository is not a Telegram link, so hand it straight to the system browser
            // instead of the in-app one.
            Browser.openUrlInSystemBrowser(getParentActivity(), SOURCE_CODE_URL);
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            if (holder.getItemViewType() == TYPE_SETTINGS) {
                TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                if (position == sovietGramChannelRow) {
                    textCell.setTextAndValue(getString(R.string.SovietGramChannel), "@SovietUnionGram", true);
                } else if (position == nagramXChannelRow) {
                    textCell.setTextAndValue(getString(R.string.NagramXChannel), "@NagramX", true);
                } else if (position == sourceCodeRow) {
                    textCell.setTextAndValue(getString(R.string.SourceCode), "GitHub", true);
                } else if (position == datacenterStatusRow) {
                    textCell.setText(getString(R.string.DatacenterStatus), false);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return TYPE_SETTINGS;
        }
    }
}
