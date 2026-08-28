package smoke;

import com.intellij.execution.*;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.igorshahin.execution.*;
import dev.igorshahin.filter.TestVisibility;
import dev.igorshahin.model.ExplorerNode;
import dev.igorshahin.settings.TestExplorerSettings;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

/** Explicit opt-in synthetic project only. Starts real native Flutter and Dart runs. */
public final class FilteredExecutionSmoke implements StartupActivity.DumbAware {
    private final List<String> results = new ArrayList<>();
    private final StringBuffer output = new StringBuffer();
    private Path directory;
    private byte[] originalSource;
    private int stage;
    private int starts;
    private int ticks;
    private PlannedTestExecution planned;

    @Override public void runActivity(Project project) {
        if (!project.getName().equals("test_explorer_filtered_smoke")) return;
        directory = Path.of(project.getBasePath());
        try {
            Files.writeString(directory.resolve("filtered-execution-result.txt"), "RUNNING\n");
            Files.writeString(directory.resolve("filtered-progress.txt"), "Waiting for native discovery\n");
        }
        catch (Exception error) { throw new RuntimeException(error); }
        project.getService(TestExplorerSettings.class).setExcludedNodeIds(Collections.emptySet());
        project.getMessageBus().connect(project).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarted(String executor, ExecutionEnvironment env, ProcessHandler handler) {
                if (stage == 0 || stage > 2) return;
                starts++;
                handler.addProcessListener(new ProcessAdapter() {
                    @Override public void onTextAvailable(ProcessEvent event, Key type) { output.append(event.getText()); }
                });
            }
            @Override public void processNotStarted(String executor, ExecutionEnvironment env) {
                finish("FAILED: native process not started");
            }
            @Override public void processTerminated(String executor, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
                if (stage == 0 || stage > 2) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        Files.writeString(directory.resolve(stage == 1 ? "flutter-output.txt" : "dart-output.txt"), output.toString());
                        require(exitCode == 0, "native exit code " + exitCode);
                        require(output.toString().contains("EXECUTED_A") && output.toString().contains("EXECUTED_B"), "A and B actually executed");
                        require(!output.toString().contains("EXECUTED_C") && !output.toString().contains("EXECUTED_D"), "C and D never executed");
                        if (stage == 1) {
                            require(output.toString().contains("ENV=ok"), "Global --dart-define reached the Flutter test body");
                            require(starts == 1, "PARTIAL Flutter group used ONE native execution");
                            stage = 2;
                            output.setLength(0);
                            runDart(project);
                        } else {
                            require(starts == 2, "PARTIAL Dart group used ONE native execution");
                            require(Arrays.equals(originalSource, Files.readAllBytes(directory.resolve("test/filtered_test.dart"))), "Dart source was unchanged");
                            finish("SUCCESS");
                        }
                    } catch (Throwable failure) { finish("FAILED: " + failure); }
                });
            }
        });
        ApplicationManager.getApplication().invokeLater(() -> {
            javax.swing.Timer timer = new javax.swing.Timer(1000, null);
            timer.addActionListener(event -> {
                if (project.isDisposed()) { timer.stop(); return; }
                try {
                    if (++ticks > 180) { timer.stop(); finish("FAILED: discovery/run timeout"); return; }
                    if (stage != 0) { if (stage > 2) timer.stop(); return; }
                    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Tests");
                    if (window == null) return;
                    window.show();
                    JTree tree = tree(window.getComponent());
                    if (tree == null) return;
                    Object value = ((DefaultMutableTreeNode) tree.getModel().getRoot()).getUserObject();
                    if (!(value instanceof ExplorerNode complete)) return;
                    List<ExplorerNode> leaves = TestVisibility.INSTANCE.leaves(complete);
                    if (ticks % 10 == 0) Files.writeString(directory.resolve("filtered-progress.txt"),
                        "ticks=" + ticks + " leaves=" + leaves.stream().map(ExplorerNode::getLabel).toList() +
                        " exclusions=" + project.getService(TestExplorerSettings.class).getExcludedNodeIds() +
                        " tree=" + complete);
                    if (leaves.size() != 4) return;
                    Set<String> excluded = new HashSet<>();
                    leaves.stream().filter(node -> node.getLabel().equals("C") || node.getLabel().equals("D"))
                        .forEach(node -> excluded.add(node.getId()));
                    ExplorerNode group = find(complete, "Filtered suite");
                    TestExplorerSettings settings = project.getService(TestExplorerSettings.class);
                    settings.setExcludedNodeIds(excluded);
                    settings.setGlobalArguments("--dart-define=FILTER_SCOPE=ok --timeout=30s");
                    TestExecutionPlan plan = new TestExecutionPlanner().plan(complete, group.getId(), settings.getExcludedNodeIds());
                    require(plan.getError() == null && plan.getTargets().size() == 1, "persistent Visibility resolves one filtered target");
                    planned = plan.getTargets().get(0);
                    require(planned.getNameFilter() != null, "PARTIAL has an exact-name filter");
                    originalSource = Files.readAllBytes(directory.resolve("test/filtered_test.dart"));
                    stage = 1;
                    new TestExecutionService(project, project).run(complete, group.getId());
                } catch (Throwable failure) { timer.stop(); finish("FAILED: " + failure); }
            });
            timer.start();
        }, ModalityState.any());
    }

    private void runDart(Project project) {
        ReadAction.nonBlocking(() -> {
            var config = new TestConfigurationFactory(project).create(planned.getTarget(), "Dart filtered smoke", false,
                "--timeout=30s", planned.getNameFilter());
            config.getConfiguration().checkConfiguration();
            return config;
        }).inSmartMode(project).finishOnUiThread(ModalityState.any(), config ->
            ProgramRunnerUtil.executeConfiguration(config, DefaultRunExecutor.getRunExecutorInstance()))
            .submit(AppExecutorUtil.getAppExecutorService()).onError(error -> finish("FAILED: " + error));
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        results.add("PASS " + label);
    }
    private void finish(String result) {
        stage = 3;
        results.add(result);
        try {
            Files.write(directory.resolve("filtered-execution-result.txt"), results);
            Files.writeString(directory.resolve("filtered-progress.txt"), result + "\n");
        }
        catch (Exception error) { error.printStackTrace(); }
    }
    private static ExplorerNode find(ExplorerNode root, String label) {
        if (root.getLabel().equals(label)) return root;
        for (ExplorerNode child : root.getChildren()) { ExplorerNode found = find(child, label); if (found != null) return found; }
        return null;
    }
    private static JTree tree(Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTree tree = tree(child); if (tree != null) return tree;
        }
        return null;
    }
}
