package net.sf.jabref.gui.importer.actions;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.swing.Action;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import net.sf.jabref.Globals;
import net.sf.jabref.JabRefExecutorService;
import net.sf.jabref.gui.BasePanel;
import net.sf.jabref.gui.FileDialog;
import net.sf.jabref.gui.IconTheme;
import net.sf.jabref.gui.JabRefFrame;
import net.sf.jabref.gui.actions.MnemonicAwareAction;
import net.sf.jabref.gui.importer.ParserResultWarningDialog;
import net.sf.jabref.gui.keyboard.KeyBinding;
import net.sf.jabref.logic.importer.OpenDatabase;
import net.sf.jabref.logic.importer.ParserResult;
import net.sf.jabref.logic.l10n.Localization;
import net.sf.jabref.logic.util.FileExtensions;
import net.sf.jabref.logic.util.io.AutoSaveUtil;
import net.sf.jabref.logic.util.io.FileBasedLock;
import net.sf.jabref.logic.util.strings.StringUtil;
import net.sf.jabref.migrations.FileLinksUpgradeWarning;
import net.sf.jabref.model.Defaults;
import net.sf.jabref.model.database.BibDatabase;
import net.sf.jabref.model.database.BibDatabaseContext;
import net.sf.jabref.model.database.BibDatabaseMode;
import net.sf.jabref.model.metadata.MetaData;
import net.sf.jabref.preferences.JabRefPreferences;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

// The action concerned with opening an existing database.

public class OpenDatabaseAction extends MnemonicAwareAction {
    public static final Log LOGGER = LogFactory.getLog(OpenDatabaseAction.class);

    private final boolean showDialog;
    private final JabRefFrame frame;

    // List of actions that may need to be called after opening the file. Such as
    // upgrade actions etc. that may depend on the JabRef version that wrote the file:
    private static final List<PostOpenAction> POST_OPEN_ACTIONS = new ArrayList<>();

    static {
        // Add the action for checking for new custom entry types loaded from the BIB file:
        POST_OPEN_ACTIONS.add(new CheckForNewEntryTypesAction());
        // Add the action for converting legacy entries in ExplicitGroup
        POST_OPEN_ACTIONS.add(new ConvertLegacyExplicitGroups());
        // Add the action for the new external file handling system in version 2.3:
        POST_OPEN_ACTIONS.add(new FileLinksUpgradeWarning());
        // Add the action for warning about and handling duplicate BibTeX keys:
        POST_OPEN_ACTIONS.add(new HandleDuplicateWarnings());
    }

    public OpenDatabaseAction(JabRefFrame frame, boolean showDialog) {
        super(IconTheme.JabRefIcon.OPEN.getIcon());
        this.frame = frame;
        this.showDialog = showDialog;
        putValue(Action.NAME, Localization.menuTitle("Open database"));
        putValue(Action.ACCELERATOR_KEY, Globals.getKeyPrefs().getKey(KeyBinding.OPEN_DATABASE));
        putValue(Action.SHORT_DESCRIPTION, Localization.lang("Open BibTeX database"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<File> filesToOpen = new ArrayList<>();

        if (showDialog) {
            FileDialog dialog = new FileDialog(frame).withExtension(FileExtensions.BIBTEX_DB);
            dialog.setDefaultExtension(FileExtensions.BIBTEX_DB);
            List<String> chosenStrings = dialog.showDialogAndGetMultipleFiles();
            filesToOpen.addAll(chosenStrings.stream().map(File::new).collect(Collectors.toList()));
        } else {
            LOGGER.info(Action.NAME + " " + e.getActionCommand());
            filesToOpen.add(new File(StringUtil.getCorrectFileName(e.getActionCommand(), "bib")));
        }

        openFiles(filesToOpen, true);
    }

    /**
     * Opens the given file. If null or 404, nothing happens
     *
     * @param file the file, may be null or not existing
     */
    public void openFile(File file, boolean raisePanel) {
        List<File> filesToOpen = new ArrayList<>();
        filesToOpen.add(file);
        openFiles(filesToOpen, raisePanel);
    }

    public void openFilesAsStringList(List<String> fileNamesToOpen, boolean raisePanel) {
        List<File> filesToOpen = new ArrayList<>();
        for (String fileName : fileNamesToOpen) {
            filesToOpen.add(new File(fileName));
        }
        openFiles(filesToOpen, raisePanel);
    }

    /**
     * Opens the given files. If one of it is null or 404, nothing happens
     *
     * @param filesToOpen the filesToOpen, may be null or not existing
     */
    public void openFiles(List<File> filesToOpen, boolean raisePanel) {
        BasePanel toRaise = null;
        int initialCount = filesToOpen.size();
        int removed = 0;

        // Check if any of the files are already open:
        for (Iterator<File> iterator = filesToOpen.iterator(); iterator.hasNext();) {
            File file = iterator.next();
            for (int i = 0; i < frame.getTabbedPane().getTabCount(); i++) {
                BasePanel basePanel = frame.getBasePanelAt(i);
                if ((basePanel.getBibDatabaseContext().getDatabaseFile().isPresent())
                        && basePanel.getBibDatabaseContext().getDatabaseFile().get().equals(file)) {
                    iterator.remove();
                    removed++;
                    // See if we removed the final one. If so, we must perhaps
                    // raise the BasePanel in question:
                    if (removed == initialCount) {
                        toRaise = basePanel;
                    }
                    // no more bps to check, we found a matching one
                    break;
                }
            }
        }

        // Run the actual open in a thread to prevent the program
        // locking until the file is loaded.
        if (!filesToOpen.isEmpty()) {
            final List<File> theFiles = Collections.unmodifiableList(filesToOpen);
            JabRefExecutorService.INSTANCE.execute(() -> {
                for (File theFile : theFiles) {
                    openTheFile(theFile, raisePanel);
                }
            });
            for (File theFile : theFiles) {
                frame.getFileHistory().newFile(theFile.getPath());
            }
        }
        // If no files are remaining to open, this could mean that a file was
        // already open. If so, we may have to raise the correct tab:
        else if (toRaise != null) {
            frame.output(Localization.lang("File '%0' is already open.",
                    toRaise.getBibDatabaseContext().getDatabaseFile().get().getPath()));
            frame.getTabbedPane().setSelectedComponent(toRaise);
        }

        frame.output(Localization.lang("Files opened") + ": " + (filesToOpen.size()));
    }

    /**
     * @param file the file, may be null or not existing
     */
    private void openTheFile(File file, boolean raisePanel) {
        if ((file != null) && file.exists()) {
            File fileToLoad = file;
            frame.output(Localization.lang("Opening") + ": '" + file.getPath() + "'");
            boolean tryingAutosave = false;
            boolean autoSaveFound = AutoSaveUtil.newerAutoSaveExists(file);
            if (autoSaveFound && !Globals.prefs.getBoolean(JabRefPreferences.PROMPT_BEFORE_USING_AUTOSAVE)) {
                // We have found a newer autosave, and the preferences say we should load
                // it without prompting, so we replace the fileToLoad:
                fileToLoad = AutoSaveUtil.getAutoSaveFile(file);
                tryingAutosave = true;
            } else if (autoSaveFound) {
                // We have found a newer autosave, but we are not allowed to use it without
                // prompting.
                int answer = JOptionPane
                        .showConfirmDialog(
                                null, "<html>"
                                        + Localization
                                                .lang("An autosave file was found for this database. This could indicate "
                                                        + "that JabRef did not shut down cleanly last time the file was used.")
                                        + "<br>"
                                        + Localization
                                                .lang("Do you want to recover the database from the autosave file?")
                                        + "</html>",
                                Localization.lang("Recover from autosave"), JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) {
                    fileToLoad = AutoSaveUtil.getAutoSaveFile(file);
                    tryingAutosave = true;
                }
            }

            boolean done = false;
            while (!done) {
                String fileName = file.getPath();
                Globals.prefs.put(JabRefPreferences.WORKING_DIRECTORY, file.getParent());
                // Should this be done _after_ we know it was successfully opened?

                if (FileBasedLock.hasLockFile(file.toPath())) {
                    Optional<FileTime> modificationTime = FileBasedLock.getLockFileTimeStamp(file.toPath());
                    if ((modificationTime.isPresent()) && ((System.currentTimeMillis()
                            - modificationTime.get().toMillis()) > FileBasedLock.LOCKFILE_CRITICAL_AGE)) {
                        // The lock file is fairly old, so we can offer to "steal" the file:
                        int answer = JOptionPane.showConfirmDialog(null,
                                "<html>" + Localization.lang("Error opening file") + " '" + fileName + "'. "
                                        + Localization.lang("File is locked by another JabRef instance.") + "<p>"
                                        + Localization.lang("Do you want to override the file lock?"),
                                Localization.lang("File locked"), JOptionPane.YES_NO_OPTION);
                        if (answer == JOptionPane.YES_OPTION) {
                            FileBasedLock.deleteLockFile(file.toPath());
                        } else {
                            return;
                        }
                    } else if (!FileBasedLock.waitForFileLock(file.toPath())) {
                        JOptionPane.showMessageDialog(null,
                                Localization.lang("Error opening file") + " '" + fileName + "'. "
                                        + Localization.lang("File is locked by another JabRef instance."),
                                Localization.lang("Error"), JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                }

                ParserResult result;
                String errorMessage = null;
                try {
                    result = OpenDatabase.loadDatabase(fileToLoad,
                            Globals.prefs.getImportFormatPreferences());
                } catch (IOException ex) {
                    LOGGER.error("Error loading database " + fileToLoad, ex);
                    result = ParserResult.getNullResult();
                }
                if (result.isNullResult()) {
                    JOptionPane.showMessageDialog(null, Localization.lang("Error opening file") + " '" + fileName + "'",
                            Localization.lang("Error"), JOptionPane.ERROR_MESSAGE);

                    String message = "<html>" + errorMessage + "<p>"
                            + (tryingAutosave ? Localization.lang(
                                    "Error opening autosave of '%0'. Trying to load '%0' instead.",
                                    file.getName()) : ""/*Globals.lang("Error opening file '%0'.", file.getName())*/)
                            + "</html>";
                    JOptionPane.showMessageDialog(null, message, Localization.lang("Error opening file"),
                            JOptionPane.ERROR_MESSAGE);

                    if (tryingAutosave) {
                        tryingAutosave = false;
                        fileToLoad = file;
                    } else {
                        done = true;
                    }
                    continue;
                } else {
                    done = true;
                }

                final BasePanel panel = addNewDatabase(result, file, raisePanel);
                if (tryingAutosave) {
                    panel.markNonUndoableBaseChanged();
                }

                // After adding the database, go through our list and see if
                // any post open actions need to be done. For instance, checking
                // if we found new entry types that can be imported, or checking
                // if the database contents should be modified due to new features
                // in this version of JabRef:
                final ParserResult finalReferenceToResult = result;
                SwingUtilities.invokeLater(
                        () -> OpenDatabaseAction.performPostOpenActions(panel, finalReferenceToResult, true));
            }

        }
    }

    /**
     * Go through the list of post open actions, and perform those that need to be performed.
     *
     * @param panel  The BasePanel where the database is shown.
     * @param result The result of the BIB file parse operation.
     */
    public static void performPostOpenActions(BasePanel panel, ParserResult result, boolean mustRaisePanel) {
        for (PostOpenAction action : OpenDatabaseAction.POST_OPEN_ACTIONS) {
            if (action.isActionNecessary(result)) {
                if (mustRaisePanel) {
                    panel.frame().getTabbedPane().setSelectedComponent(panel);
                }
                action.performAction(panel, result);
            }
        }
    }

    private BasePanel addNewDatabase(ParserResult result, final File file, boolean raisePanel) {

        String fileName = file.getPath();
        BibDatabase database = result.getDatabase();
        MetaData meta = result.getMetaData();

        if (result.hasWarnings()) {
            JabRefExecutorService.INSTANCE
                    .execute(() -> ParserResultWarningDialog.showParserResultWarningDialog(result, frame));
        }

        Defaults defaults = new Defaults(
                BibDatabaseMode.fromPreference(Globals.prefs.getBoolean(JabRefPreferences.BIBLATEX_DEFAULT_MODE)));
        BasePanel basePanel = new BasePanel(frame, new BibDatabaseContext(database, meta, file, defaults));

        // file is set to null inside the EventDispatcherThread
        SwingUtilities.invokeLater(() -> frame.addTab(basePanel, raisePanel));

        frame.output(Localization.lang("Opened database") + " '" + fileName + "' " + Localization.lang("with") + " "
                + database.getEntryCount() + " " + Localization.lang("entries") + ".");

        return basePanel;
    }

}
