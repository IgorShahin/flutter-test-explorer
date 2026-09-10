package smoke;

import com.intellij.execution.*;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.*;
import dev.igorshahin.execution.*;
import dev.igorshahin.filter.TestVisibility;
import dev.igorshahin.model.ExplorerNode;
import dev.igorshahin.settings.TestArgument;
import dev.igorshahin.settings.TestExplorerSettings;
import io.flutter.run.test.TestConfig;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

/** Opt-in safe fixture only: actual production Run All service, two sequential native files. */
public final class MultiFileExecutionSmoke implements StartupActivity.DumbAware {
    private final List<String> results = new ArrayList<>();
    private final StringBuffer output = new StringBuffer();
    private final Map<Path, byte[]> sources = new LinkedHashMap<>();
    private Path directory;
    private volatile int stage;
    private int starts;
    private int completed;
    private int ticks;

    @Override public void runActivity(Project project) {
        if (!project.getName().equals("test_explorer_multifile_smoke")) return;
        directory = Path.of(project.getBasePath());
        write("multifile-result.txt", "RUNNING\n");
        project.getService(TestExplorerSettings.class).setExcludedNodeIds(Collections.emptySet());
        project.getMessageBus().connect(project).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) {
                if (stage == 1 && notification.getGroupId().equals("Flutter Test Explorer"))
                    finish("FAILED: " + notification.getContent());
            }
        });
        project.getMessageBus().connect(project).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarted(String executor, ExecutionEnvironment env, ProcessHandler handler) {
                synchronized (MultiFileExecutionSmoke.this) {
                    if (stage == 0 || stage == 3) return;
                    try {
                        require(starts == completed, "previous file terminated before next file starts");
                        starts++;
                        results.add("INFO native settings match selected settings: " +
                            (env.getRunnerAndConfigurationSettings() == RunManager.getInstance(project).getSelectedConfiguration()));
                        require(starts <= 2, "only included files launch");
                        TestConfig config = (TestConfig) env.getRunProfile();
                        String file = Path.of(config.getFields().getTestFile()).getFileName().toString();
                        String args = config.getFields().getAdditionalArgs();
                        require(args.contains("--dart-define=BATCH_SCOPE=ok"), "global arguments retained for " + file);
                        require(!args.contains("DISABLED_SCOPE"), "disabled argument absent from native configuration for " + file);
                        require(config.getFields().getTestName() == null, "file target, not cross-file group target");
                        require(file.equals(starts == 1 ? "file_a_test.dart" : "file_b_test.dart"), "deterministic file order: " + file);
                        require(args.contains("--name=") == (starts == 1), "PARTIAL filtered / FULL unfiltered: " + file);
                        handler.addProcessListener(new ProcessAdapter() {
                            @Override public void onTextAvailable(ProcessEvent event, Key type) { output.append(event.getText()); }
                            @Override public void processWillTerminate(ProcessEvent event, boolean willBeDestroyed) {
                                synchronized (MultiFileExecutionSmoke.this) {
                                    results.add("INFO native termination: willBeDestroyed=" + willBeDestroyed +
                                        ", user termination requested=" + handler.getUserData(ProcessHandler.TERMINATION_REQUESTED));
                                    write("multifile-progress.txt", String.join("\n", results) + "\n");
                                }
                            }
                        });
                    } catch (Throwable failure) { finish("FAILED: " + failure); }
                }
            }
            @Override public void processNotStarted(String executor, ExecutionEnvironment env) {
                synchronized (MultiFileExecutionSmoke.this) { if (stage == 1) finish("FAILED: native process not started"); }
            }
            @Override public void processTerminated(String executor, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
                synchronized (MultiFileExecutionSmoke.this) {
                    if (stage != 1) return;
                    try {
                        require(exitCode == 0, "native file exit code 0");
                        completed++;
                        if (completed == 2) stage = 2;
                    } catch (Throwable failure) { finish("FAILED: " + failure); }
                }
            }
        });
        ApplicationManager.getApplication().invokeLater(() -> {
            javax.swing.Timer timer = new javax.swing.Timer(1000, null);
            timer.addActionListener(event -> {
                if (project.isDisposed() || stage == 3) { timer.stop(); return; }
                try {
                    if (++ticks > 180) { timer.stop(); finish("FAILED: discovery/run timeout"); return; }
                    if (stage == 2) {
                        require(starts == 2 && completed == 2, "exactly two sequential native executions completed");
                        require(output.toString().contains("EXECUTED_1 ENV=ok") &&
                            output.toString().contains("EXECUTED_3 ENV=ok") && output.toString().contains("EXECUTED_4 ENV=ok"),
                            "included tests in both files actually executed with global defines");
                        require(output.toString().contains("DISABLED=absent") && !output.toString().contains("DISABLED=leaked"),
                            "disabled argument physically absent from every Flutter argv");
                        require(!output.toString().contains("EXECUTED_HIDDEN"), "excluded test and excluded file never executed");
                        for (var source : sources.entrySet())
                            require(Arrays.equals(source.getValue(), Files.readAllBytes(source.getKey())), "source unchanged: " + source.getKey().getFileName());
                        finish("SUCCESS");
                        timer.stop();
                        return;
                    }
                    if (stage != 0) return;
                    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Tests");
                    if (window == null) return;
                    window.show();
                    JTree tree = tree(window.getComponent());
                    if (tree == null) return;
                    Object value = ((DefaultMutableTreeNode) tree.getModel().getRoot()).getUserObject();
                    if (!(value instanceof ExplorerNode complete)) return;
                    if (TestVisibility.INSTANCE.leaves(complete).size() != 5) return;
                    Set<String> excluded = Set.of(find(complete, "test 2").getId(), find(complete, "file_c_test.dart").getId());
                    var settings = project.getService(TestExplorerSettings.class);
                    settings.setExcludedNodeIds(excluded);
                    settings.setTestArguments(List.of(
                        new TestArgument("--dart-define=BATCH_SCOPE=ok", true),
                        new TestArgument("--timeout=30s", true),
                        new TestArgument("--dart-define=DISABLED_SCOPE=leaked", false)
                    ));
                    var planner = new TestExecutionPlanner();
                    var plan = planner.plan(complete, complete.getId(), settings.getExcludedNodeIds());
                    require(plan.getError() == null && plan.getTargets().size() == 2, "Run All plans two included files");
                    require(plan.getTargets().equals(planner.plan(complete, find(complete, "scenarios").getId(), excluded).getTargets()),
                        "directory uses the same per-file planner as root");
                    for (String file : List.of("file_a_test.dart", "file_b_test.dart", "file_c_test.dart")) {
                        Path path = directory.resolve("test/scenarios/" + file);
                        sources.put(path, Files.readAllBytes(path));
                    }
                    stage = 1;
                    new TestExecutionService(project, project).run(complete, complete.getId());
                } catch (Throwable failure) { timer.stop(); finish("FAILED: " + failure); }
            });
            timer.start();
        }, ModalityState.any());
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        results.add("PASS " + label);
        write("multifile-progress.txt", String.join("\n", results) + "\n");
    }
    private void finish(String status) {
        stage = 3;
        results.add(status);
        write("multifile-output.txt", output.toString());
        write("multifile-result.txt", String.join("\n", results) + "\n");
    }
    private void write(String name, String text) {
        try { Files.writeString(directory.resolve(name), text); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static ExplorerNode find(ExplorerNode node, String label) {
        if (node.getLabel().equals(label)) return node;
        for (ExplorerNode child : node.getChildren()) { ExplorerNode found = find(child, label); if (found != null) return found; }
        return null;
    }
    private static JTree tree(Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTree found = tree(child); if (found != null) return found;
        }
        return null;
    }
}
