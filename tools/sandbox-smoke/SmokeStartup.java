package smoke;

import com.intellij.ide.actions.ToolWindowsGroup;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl;
import com.intellij.toolWindow.ToolWindowEventSource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.*;

/** Separate development-only plugin: not a source set or dependency of the distributable. */
public final class SmokeStartup implements StartupActivity.DumbAware {
    private final List<String> results = new ArrayList<>();
    private int ticks;
    private boolean initialized;
    private Path output;

    @Override public void runActivity(Project project) {
        if (!project.getName().equals("test_explorer_smoke")) return;
        output = Path.of(project.getBasePath());
        ApplicationManager.getApplication().invokeLater(() -> {
            Timer timer = new Timer(1000, null);
            timer.addActionListener(e -> {
                try {
                    if (project.isDisposed()) { timer.stop(); return; }
                    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Tests");
                    if (window == null) { if (++ticks > 120) throw new AssertionError("Tests not registered"); return; }
                    if (!initialized) {
                        verifyWindow(project, window);
                        initialized = true;
                        Files.write(output.resolve("smoke-result.txt"), results);
                    }
                    JTree tree = findTree(window.getComponent());
                    List<String> labels = new ArrayList<>();
                    if (tree != null) collect((TreeNode) tree.getModel().getRoot(), labels);
                    if (labels.containsAll(List.of("test", "integration_test", "protocol", "messages", "decode", "connect", "checks.dart"))) {
                        require(labels.stream().noneMatch(s -> List.of("helper.dart", "noise", "empty", "unused").contains(s)), "no empty/helper branches");
                        results.add("PASS dynamic roots, arbitrary directories, nonstandard filename, nested groups: " + labels);
                        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
                        TreePath firstPath = tree.getPathForRow(0);
                        Component rowComponent = tree.getCellRenderer().getTreeCellRendererComponent(
                            tree, firstPath.getLastPathComponent(), false, true, false, 0, false);
                        require(tree.getPathBounds(firstPath).width == rowComponent.getPreferredSize().width,
                            "native TreeUI uses the actual inline renderer, not ExplorerNode.toString");
                        render(window.getComponent(), "tool-window.png");
                        results.add("PASS actual sandbox component rendered: tool-window.png");
                        finish(timer, "SUCCESS");
                    } else if (++ticks > 120) {
                        throw new AssertionError("Discovery timeout: " + labels);
                    }
                } catch (Throwable failure) {
                    results.add(failure.toString());
                    finish(timer, "FAILED");
                }
            });
            timer.start();
        });
    }

    private void verifyWindow(Project project, ToolWindow window) throws Exception {
        require(window.isAvailable(), "native Tests is available");
        require(window.getIcon() != null, "native Tests has an icon");
        Icon icon = window.getIcon();
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        icon.paintIcon(window.getComponent(), graphics, 0, 0);
        graphics.dispose();
        boolean visible = false;
        for (int x=0; x<image.getWidth(); x++) for (int y=0; y<image.getHeight(); y++) visible |= (image.getRGB(x,y) >>> 24) != 0;
        require(visible, "real native TestGroup icon renders nontransparent pixels");
        ImageIO.write(image, "png", output.resolve("tool-window-icon.png").toFile());
        window.hide();
        require(!window.isVisible(), "native hide");
        // New UI ignores setShowStripeButton(false); use its actual Remove Stripe Button path.
        ((ToolWindowManagerImpl) ToolWindowManager.getInstance(project)).hideToolWindow(
            "Tests", false, false, true, ToolWindowEventSource.RemoveStripeButtonAction);
        // This is the very list ShowMoreToolWindowsAction.createPopup uses in IDEA 253.
        var actions = ToolWindowsGroup.getToolWindowActions(project, true);
        AnAction restore = actions.stream().filter(a -> "Tests".equals(a.getToolWindowId())).findFirst().orElseThrow();
        results.add("PASS native More Tool Windows contains Tests when removed from stripe");
        DataContext context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build();
        restore.actionPerformed(AnActionEvent.createFromAnAction(restore, null, ActionPlaces.UNKNOWN, context));
        require(window.isVisible(), "native More Tool Windows action restores Tests");
        ToolWindowAnchor anchor = window.getAnchor();
        window.setAnchor(ToolWindowAnchor.RIGHT, null);
        require(window.getAnchor() == ToolWindowAnchor.RIGHT, "native move right");
        window.setAnchor(anchor, null);
        boolean autoHide = window.isAutoHide();
        window.setAutoHide(!autoHide);
        require(window.isAutoHide() != autoHide, "native pin/unpin (auto-hide)");
        window.setAutoHide(autoHide);
        window.show();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        results.add("PASS " + message);
    }
    private void finish(Timer timer, String status) {
        timer.stop();
        results.add(status);
        try { Files.write(output.resolve("smoke-result.txt"), results); }
        catch (Exception failure) { failure.printStackTrace(); }
    }
    private static JTree findTree(Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTree tree = findTree(child); if (tree != null) return tree;
        }
        return null;
    }
    private static void collect(TreeNode node, List<String> labels) throws ReflectiveOperationException {
        Object value = ((DefaultMutableTreeNode) node).getUserObject();
        labels.add((String) value.getClass().getMethod("getLabel").invoke(value));
        for (int i=0; i<node.getChildCount(); i++) collect(node.getChildAt(i), labels);
    }
    private void render(JComponent component, String filename) throws Exception {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", output.resolve(filename).toFile());
    }
}
