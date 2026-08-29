package marketplace;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Builds Marketplace slides around the real component captured by the sandbox smoke test. */
public final class GenerateMarketplaceScreenshots {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static final Color TEXT = new Color(244, 246, 250);
    private static final Color MUTED = new Color(174, 182, 197);
    private static final Color BLUE = new Color(91, 140, 255);
    private static final Color GREEN = new Color(92, 218, 137);
    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 48);
    private static final Font HEADING = new Font("SansSerif", Font.BOLD, 30);
    private static final Font BODY = new Font("SansSerif", Font.PLAIN, 20);
    private static final Font SMALL = new Font("SansSerif", Font.BOLD, 15);

    private GenerateMarketplaceScreenshots() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: <sandbox tool-window.png> <output directory>");
        }
        BufferedImage source = sanitizeProjectName(ImageIO.read(Path.of(args[0]).toFile()));
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        write(overview(source), output.resolve("01-overview.png"));
        write(discovery(source), output.resolve("02-runnable-discovery.png"));
        write(execution(source), output.resolve("03-run-and-filter.png"));
    }

    private static BufferedImage overview(BufferedImage source) {
        BufferedImage image = canvas();
        Graphics2D g = graphics(image);
        brand(g, 72, 58);
        pill(g, "NATIVE INTELLIJ TESTS TOOL WINDOW", 72, 150, BLUE);
        lines(g, TITLE, TEXT, 72, 242, 58, "Your Dart & Flutter tests,", "ready to run");
        lines(g, BODY, MUTED, 72, 390, 31,
            "Discover, filter, and launch verified test targets", "without leaving your IDE workflow.");
        featureCard(g, 72, 520, 214, "Runnable only", "No helper noise", GREEN);
        featureCard(g, 304, 520, 214, "Always current", "Incremental refresh", BLUE);
        featureCard(g, 72, 636, 446, "Exact visible scope", "Run tests, groups, files, or directories", new Color(171, 121, 255));
        drawPanel(g, source, 820, 34, 383, 732, 0, 0, source.getWidth(), source.getHeight());
        g.dispose();
        return image;
    }

    private static BufferedImage discovery(BufferedImage source) {
        BufferedImage image = canvas();
        Graphics2D g = graphics(image);
        pill(g, "STRICT RUNNABLE DISCOVERY", 700, 70, GREEN);
        lines(g, TITLE, TEXT, 700, 170, 58, "See tests,", "not test-shaped noise");
        lines(g, BODY, MUTED, 700, 306, 31,
            "The explorer follows Dart and Flutter analyzer data", "and validates targets against IDE run infrastructure.");
        bullet(g, 700, 432, "Both test/ and integration_test/");
        bullet(g, 700, 486, "Nested directories and groups");
        bullet(g, 700, 540, "Helpers and empty branches are hidden");
        bullet(g, 700, 594, "Unsupported dynamic targets are omitted");
        drawPanel(g, source, 54, 55, 592, 690, 0, 32, 456, 564);
        label(g, 82, 686, "DYNAMIC PROJECT STRUCTURE", BLUE);
        g.dispose();
        return image;
    }

    private static BufferedImage execution(BufferedImage source) {
        BufferedImage image = canvas();
        Graphics2D g = graphics(image);
        pill(g, "PRECISE EXECUTION SCOPE", 70, 70, BLUE);
        lines(g, TITLE, TEXT, 70, 168, 58, "Run exactly", "what you need");
        lines(g, BODY, MUTED, 70, 304, 31,
            "Inline actions and persistent visibility work together", "with safe per-file execution planning.");
        bullet(g, 70, 432, "Run All or any runnable row");
        bullet(g, 70, 486, "Search with text and !exclusions");
        bullet(g, 70, 540, "Exact filters for partially visible files");
        bullet(g, 70, 594, "Unsaved edits rediscover automatically");
        drawPanel(g, source, 612, 112, 600, 560, 0, 0, 456, 426);
        focusBox(g, 620, 119, 184, 38, GREEN, 1);
        focusBox(g, 619, 165, 586, 38, BLUE, 2);
        focusBox(g, 751, 330, 30, 28, GREEN, 3);
        featureTag(g, 612, 700, 176, 1, "RUN ACTIONS", GREEN);
        featureTag(g, 800, 700, 216, 2, "SEARCH & EXCLUDE", BLUE);
        featureTag(g, 1028, 700, 184, 3, "INLINE RUN", GREEN);
        label(g, 884, 768, "NATIVE DART / FLUTTER RUN CONFIGURATIONS", BLUE);
        g.dispose();
        return image;
    }

    private static BufferedImage canvas() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(18, 20, 26), WIDTH, HEIGHT, new Color(25, 27, 42)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(76, 100, 190, 18));
        g.fillOval(870, -260, 720, 720);
        g.setColor(new Color(72, 197, 156, 12));
        g.fillOval(-280, 520, 650, 650);
        g.dispose();
        return image;
    }

    /** The capture comes from an internal fixture; publish it under a neutral demo-project label. */
    private static BufferedImage sanitizeProjectName(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(copy);
        g.drawImage(source, 0, 0, null);
        g.setColor(new Color(30, 31, 34));
        g.fillRect(47, 72, 190, 25);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(205, 207, 211));
        g.drawString("sample_project", 47, 91);
        g.dispose();
        return copy;
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    private static void brand(Graphics2D g, int x, int y) {
        g.setPaint(new GradientPaint(x, y, new Color(34, 184, 230), x + 64, y + 64, new Color(102, 86, 232)));
        g.fill(new RoundRectangle2D.Double(x, y, 64, 64, 16, 16));
        g.setColor(new Color(255, 255, 255, 48));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(x + 1, y + 1, 62, 62, 15, 15));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D flask = new Path2D.Double();
        flask.moveTo(x + 23, y + 13);
        flask.lineTo(x + 41, y + 13);
        flask.moveTo(x + 27, y + 13);
        flask.lineTo(x + 27, y + 27);
        flask.lineTo(x + 16, y + 47);
        flask.curveTo(x + 14, y + 51, x + 17, y + 55, x + 22, y + 55);
        flask.lineTo(x + 42, y + 55);
        flask.curveTo(x + 47, y + 55, x + 50, y + 51, x + 48, y + 47);
        flask.lineTo(x + 37, y + 27);
        flask.lineTo(x + 37, y + 13);
        g.draw(flask);
        Path2D play = new Path2D.Double();
        play.moveTo(x + 28, y + 36);
        play.lineTo(x + 28, y + 48);
        play.lineTo(x + 39, y + 42);
        play.closePath();
        g.setColor(new Color(138, 240, 168));
        g.fill(play);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.setColor(TEXT);
        g.drawString("Flutter Test Explorer", x + 84, y + 41);
    }

    private static void pill(Graphics2D g, String text, int x, int y, Color accent) {
        g.setFont(SMALL);
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth(text) + 34;
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28));
        g.fill(new RoundRectangle2D.Double(x, y, width, 36, 18, 18));
        g.setColor(accent);
        g.drawString(text, x + 17, y + 24);
    }

    private static void lines(Graphics2D g, Font font, Color color, int x, int y, int spacing, String... lines) {
        g.setFont(font);
        g.setColor(color);
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], x, y + i * spacing);
    }

    private static void featureCard(Graphics2D g, int x, int y, int width, String title, String subtitle, Color accent) {
        g.setColor(new Color(255, 255, 255, 11));
        g.fill(new RoundRectangle2D.Double(x, y, width, 94, 20, 20));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 190));
        g.fill(new RoundRectangle2D.Double(x + 16, y + 17, 6, 60, 3, 3));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(TEXT);
        g.drawString(title, x + 38, y + 39);
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(MUTED);
        g.drawString(subtitle, x + 38, y + 66);
    }

    private static void bullet(Graphics2D g, int x, int y, String text) {
        g.setColor(GREEN);
        g.fillOval(x, y - 13, 11, 11);
        g.setFont(BODY);
        g.setColor(TEXT);
        g.drawString(text, x + 27, y);
    }

    private static void drawPanel(Graphics2D g, BufferedImage source, int x, int y, int width, int height,
                                  int sx, int sy, int sw, int sh) {
        for (int i = 18; i >= 2; i -= 2) {
            g.setColor(new Color(0, 0, 0, Math.max(2, 24 - i)));
            g.fill(new RoundRectangle2D.Double(x - i / 3.0, y + i / 2.0, width + i * 0.7, height + i * 0.5, 24, 24));
        }
        g.setColor(new Color(255, 255, 255, 30));
        g.fill(new RoundRectangle2D.Double(x - 1, y - 1, width + 2, height + 2, 20, 20));
        Shape previous = g.getClip();
        g.clip(new RoundRectangle2D.Double(x, y, width, height, 18, 18));
        g.drawImage(source, x, y, x + width, y + height, sx, sy, sx + sw, sy + sh, null);
        g.setClip(previous);
    }

    private static void focusBox(Graphics2D g, int x, int y, int width, int height, Color color, int number) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 9, 9));
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(x, y, width, height, 9, 9));
        int badgeX = x + width - 9;
        int badgeY = y - 9;
        g.fillOval(badgeX, badgeY, 20, 20);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(20, 23, 29));
        String value = Integer.toString(number);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(value, badgeX + (20 - metrics.stringWidth(value)) / 2, badgeY + 15);
    }

    private static void featureTag(Graphics2D g, int x, int y, int width, int number, String text, Color color) {
        g.setColor(new Color(255, 255, 255, 12));
        g.fill(new RoundRectangle2D.Double(x, y, width, 42, 14, 14));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
        g.fill(new RoundRectangle2D.Double(x + 1, y + 1, width - 2, 40, 13, 13));
        g.setColor(color);
        g.fillOval(x + 12, y + 10, 22, 22);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(20, 23, 29));
        String value = Integer.toString(number);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(value, x + 12 + (22 - metrics.stringWidth(value)) / 2, y + 26);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(color);
        g.drawString(text, x + 45, y + 26);
    }

    private static void label(Graphics2D g, int x, int y, String text, Color color) {
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static void write(BufferedImage image, Path path) throws Exception {
        ImageIO.write(image, "png", path.toFile());
    }
}
