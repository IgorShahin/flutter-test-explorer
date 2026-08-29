package smoke;

import com.intellij.execution.*;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.*;
import dev.igorshahin.model.ExplorerNode;
import dev.igorshahin.settings.TestExplorerSettings;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;

/** Explicit safe fixture only. Edits real IDE documents and invokes the actual tree Run action. */
public final class EditRunSmoke implements StartupActivity.DumbAware {
    private final List<String> results = new ArrayList<>();
    private final StringBuffer output = new StringBuffer();
    private Path directory;
    private JTree tree;
    private Document first;
    private Document second;
    private DefaultMutableTreeNode untouched;
    private String hiddenId;
    private int oldOffset;
    private volatile int stage;
    private int ticks;
    private int starts;
    private int completed;

    @Override public void runActivity(Project project) {
        if (!project.getName().equals("test_explorer_edit_smoke")) return;
        directory = Path.of(project.getBasePath());
        write("edit-run-result.txt", "RUNNING\n");
        project.getService(TestExplorerSettings.class).setExcludedNodeIds(Collections.emptySet());
        project.getMessageBus().connect(project).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) {
                if (stage > 0 && stage < 6 && notification.getGroupId().equals("Flutter Test Explorer"))
                    finish("FAILED: " + notification.getContent());
            }
        });
        project.getMessageBus().connect(project).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarted(String executor, ExecutionEnvironment env, ProcessHandler handler) {
                if (stage == 0 || stage >= 6) return;
                try {
                    starts++;
                    require(starts <= 3, "no duplicate execution during automatic rediscovery");
                    handler.addProcessListener(new ProcessAdapter() {
                        @Override public void onTextAvailable(ProcessEvent event, Key type) { output.append(event.getText()); }
                    });
                    if (stage == 3 && starts == 2) ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            second = openDocument(project, "test/b_test.dart");
                            WriteCommandAction.runWriteCommandAction(project, () ->
                                second.setText(second.getText().replace("SECOND_OLD", "SECOND_NEW")));
                            require(FileDocumentManager.getInstance().isDocumentUnsaved(second), "queued file edit is unsaved");
                            results.add("EDIT_SECOND " + Instant.now());
                        } catch (Throwable failure) { finish("FAILED: " + failure); }
                    }, ModalityState.nonModal());
                } catch (Throwable failure) { finish("FAILED: " + failure); }
            }
            @Override public void processNotStarted(String executor, ExecutionEnvironment env) {
                if (stage > 0 && stage < 6) finish("FAILED: native run did not start");
            }
            @Override public void processTerminated(String executor, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
                if (stage == 0 || stage >= 6) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        require(exitCode == 0, "native execution exited successfully");
                        completed++;
                        if (stage == 1) stage = 2;
                        else if (stage == 3 && completed == 3) stage = 4;
                    } catch (Throwable failure) { finish("FAILED: " + failure); }
                }, ModalityState.nonModal());
            }
        });
        ApplicationManager.getApplication().invokeLater(() -> {
            javax.swing.Timer timer = new javax.swing.Timer(250, null);
            timer.addActionListener(event -> ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || stage >= 6) { timer.stop(); return; }
                try {
                    if (++ticks > 600) { timer.stop(); finish("FAILED: edit/discovery/run timeout"); return; }
                    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Tests");
                    if (window == null) return;
                    if (tree == null) { window.show(); tree = tree(window.getComponent()); }
                    if (tree == null) return;
                    if (stage == 0) {
                        if (ticks < 80) return; // let SDK/startup root synchronization settle first
                        DefaultMutableTreeNode edited = find(root(), "edited");
                        untouched = find(root(), "untouched");
                        DefaultMutableTreeNode hidden = find(root(), "hidden");
                        if (edited == null || untouched == null || hidden == null) return;
                        hiddenId = node(hidden).getId();
                        project.getService(TestExplorerSettings.class).setExcludedNodeIds(Set.of(hiddenId));
                        project.getService(TestExplorerSettings.class).setGlobalArguments("--timeout=30s");
                        oldOffset = node(edited).getLocation().getOffset();
                        tree.expandPath(new TreePath(untouched.getParent() instanceof DefaultMutableTreeNode parent ? parent.getPath() : untouched.getPath()));
                        tree.setSelectionPath(new TreePath(edited.getPath()));
                        first = openDocument(project, "test/a_test.dart");
                        results.add("EDIT_FIRST " + Instant.now());
                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            first.setText(first.getText().replace("FIRST_OLD", "FIRST_NEW"));
                            for (int i = 0; i < 40; i++) first.insertString(0, "// edit " + i + "\n");
                        });
                        require(FileDocumentManager.getInstance().isDocumentUnsaved(first), "immediate Run sees unsaved editor changes");
                        stage = 1;
                        runSelected(); // same EDT turn: no save, no commit, no Refresh, no delay
                        require(starts == 0, "Run asynchronously waits for current discovery");
                    } else if (stage == 2) {
                        DefaultMutableTreeNode edited = find(root(), "edited");
                        if (edited == null) return;
                        require(output.toString().contains("FIRST_NEW") && !output.toString().contains("FIRST_OLD"), "Run executed updated unsaved test body");
                        require(node(edited).getLocation().getOffset() > oldOffset, "tree uses rediscovered source location");
                        require(find(root(), "untouched") == untouched, "unaffected Swing branch retains identity");
                        require(tree.isExpanded(new TreePath(((DefaultMutableTreeNode) untouched.getParent()).getPath())), "unaffected expansion preserved");
                        require(project.getService(TestExplorerSettings.class).getExcludedNodeIds().equals(Set.of(hiddenId)), "persistent visibility preserved");
                        require(find(root(), "hidden") == null, "excluded test remains hidden after edits");
                        stage = 3;
                        tree.setSelectionPath(new TreePath(root().getPath()));
                        runSelected(); // Run All; b_test.dart will be edited while a_test.dart runs
                    } else if (stage == 4) {
                        require(starts == 3 && completed == 3, "queued-file refresh never reruns completed file");
                        require(output.toString().contains("SECOND_NEW") && !output.toString().contains("SECOND_OLD"), "queued file uses its latest unsaved body");
                        require(!output.toString().contains("HIDDEN_RAN"), "hidden test body never executed");
                        results.add("RENAME_WITHOUT_RUN " + Instant.now());
                        WriteCommandAction.runWriteCommandAction(project, () -> first.setText(first.getText().replace("'edited'", "'renamed'")));
                        stage = 5;
                    } else if (stage == 5 && find(root(), "renamed") != null && find(root(), "edited") == null) {
                        require(FileDocumentManager.getInstance().isDocumentUnsaved(first), "automatic rediscovery does not save editor document");
                        require(starts == 3, "ordinary editing does not start tests");
                        finish("SUCCESS");
                    }
                } catch (Throwable failure) { timer.stop(); finish("FAILED: " + failure); }
            }, ModalityState.nonModal()));
            timer.start();
        }, ModalityState.nonModal());
    }

    private Document openDocument(Project project, String path) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(directory.resolve(path).toString());
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
        return FileDocumentManager.getInstance().getDocument(file);
    }
    private void runSelected() {
        tree.getActionMap().get("run-selected-test").actionPerformed(new ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "smoke"));
    }
    private DefaultMutableTreeNode root() { return (DefaultMutableTreeNode) tree.getModel().getRoot(); }
    private static ExplorerNode node(DefaultMutableTreeNode node) { return (ExplorerNode) node.getUserObject(); }
    private static DefaultMutableTreeNode find(DefaultMutableTreeNode root, String label) {
        if (root.getUserObject() instanceof ExplorerNode data && data.getLabel().equals(label)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode found = find((DefaultMutableTreeNode) root.getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private static JTree tree(Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTree found = tree(child); if (found != null) return found;
        }
        return null;
    }
    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        results.add("PASS " + label);
        write("edit-run-progress.txt", String.join("\n", results) + "\n");
    }
    private void finish(String status) {
        stage = 6;
        results.add(status);
        write("edit-run-result.txt", String.join("\n", results) + "\n");
        write("edit-run-output.txt", output.toString());
    }
    private void write(String name, String text) {
        try { Files.writeString(directory.resolve(name), text); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
