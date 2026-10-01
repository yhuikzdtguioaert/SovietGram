package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.R;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Arrays;

import tw.nekomimi.nekogram.helpers.FrameProjects;
import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.frame.FramePackage;

import java.io.InputStream;
import java.io.OutputStream;

/** The reference studio's project shelf: named drafts, distinct from the worn frame. */
public class FrameProjectsActivity extends CustomProfileListActivity {
    private static final int REQUEST_IMPORT = 1617;
    private static final int REQUEST_EXPORT = 1618;
    private FrameProjects.Project exporting;
    private int fileAccount = -1;
    private long fileOwner;
    @Override
    protected String title() {
        return getString(R.string.CustomProfileFrameProjects);
    }

    @Override
    protected void buildRows() {
        header(getString(R.string.CustomProfileFrameProjects));
        for (FrameProjects.Project project : FrameProjects.list()) {
            setting(project.name, getString(R.string.CustomProfileFrameProjectOpen),
                    () -> menu(project));
        }
        shadow();
        setting(getString(R.string.CustomProfileFrameProjectNew), null,
                () -> name(null));
        setting(getString(R.string.CustomProfileFrameProjectImport), null, this::importFile);
        info(getString(R.string.CustomProfileFrameProjectsInfo));
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        FrameProjects.refresh(this::rebuild);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    private void menu(FrameProjects.Project project) {
        if (getParentActivity() == null) return;
        PopupHelper.show(new ArrayList<>(Arrays.asList(
                        getString(R.string.CustomProfileFrameProjectOpen),
                        getString(R.string.CustomProfileFrameProjectRename),
                        getString(R.string.CustomProfileFrameProjectDuplicate),
                        getString(R.string.CustomProfileFrameProjectExport),
                        getString(R.string.Delete))), project.name, -1, getParentActivity(), item -> {
                    if (item == 0) {
                        if (FrameProjects.open(project.id)) {
                            presentFragment(new FrameStudioActivity());
                        }
                    } else if (item == 1) {
                        name(project);
                    } else if (item == 2) {
                        if (FrameProjects.duplicate(project.id) == null) {
                            BulletinFactory.of(this).createErrorBulletin(
                                    getString(R.string.CustomProfileFrameProjectLimit)).show();
                        }
                        rebuild();
                    } else if (item == 3) {
                        exportFile(project);
                    } else if (item == 4) {
                        confirmDelete(project);
                    }
                });
    }

    private void importFile() {
        fileAccount = UserConfig.selectedAccount;
        fileOwner = UserConfig.getInstance(fileAccount).getClientUserId();
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void exportFile(FrameProjects.Project project) {
        fileAccount = UserConfig.selectedAccount;
        fileOwner = UserConfig.getInstance(fileAccount).getClientUserId();
        exporting = project;
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,
                project.name.replaceAll("[^\\p{L}\\p{N} _-]", "_") + ".frame");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                || getParentActivity() == null || isFinished || fileAccount != UserConfig.selectedAccount
                || fileOwner != UserConfig.getInstance(fileAccount).getClientUserId()) return;
        final int originalAccount = fileAccount;
        final long originalOwner = fileOwner;
        final Context context = getParentActivity();
        if (requestCode == REQUEST_IMPORT) {
            Utilities.globalQueue.postRunnable(() -> {
                try (InputStream input = context.getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new IllegalArgumentException("Cannot open frame file");
                    final FramePackage.Document document = FramePackage.read(context, input);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isFinished || originalAccount != UserConfig.selectedAccount
                                || originalOwner != UserConfig.getInstance(originalAccount).getClientUserId()) return;
                        if (FrameProjects.importDocument(document.name, document.graph,
                                document.spec) == null) {
                            showError(getString(R.string.CustomProfileFrameProjectLimit));
                        }
                        rebuild();
                    });
                } catch (Exception e) {
                    AndroidUtilities.runOnUIThread(() -> showError(e.getMessage()));
                }
            });
        } else if (requestCode == REQUEST_EXPORT && exporting != null) {
            final FrameProjects.Project project = exporting;
            exporting = null;
            Utilities.globalQueue.postRunnable(() -> {
                try (OutputStream output = context.getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IllegalArgumentException("Cannot create frame file");
                    FramePackage.write(context, project.name, project.graph, project.spec, output);
                } catch (Exception e) {
                    AndroidUtilities.runOnUIThread(() -> showError(e.getMessage()));
                }
            });
        }
    }

    private void showError(String message) {
        if (isFinished || getParentActivity() == null) return;
        BulletinFactory.of(this).createErrorBulletin(message == null
                ? getString(R.string.CustomProfileFrameFileError) : message).show();
    }

    private void name(FrameProjects.Project project) {
        if (getParentActivity() == null) return;
        final EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setText(project == null ? "" : project.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(project == null ? R.string.CustomProfileFrameProjectNew
                        : R.string.CustomProfileFrameProjectRename))
                .setView(input)
                .setPositiveButton(getString(R.string.Save), (dialog, which) -> {
                    final String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (project == null) {
                        if (FrameProjects.create(value) == null) {
                            BulletinFactory.of(this).createErrorBulletin(
                                    getString(R.string.CustomProfileFrameProjectLimit)).show();
                        }
                    } else {
                        FrameProjects.rename(project.id, value);
                    }
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void confirmDelete(FrameProjects.Project project) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.Delete))
                .setMessage(project.name)
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    FrameProjects.delete(project.id);
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
