// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.lag;

import gg.catalyst.util.Branding;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Draws the hotspot report as a stacked bar chart with the JDK's own 2D graphics - no charting
 * library to ship in a plugin whose point is reducing overhead. Headless-safe: BufferedImage and
 * Graphics2D need no display, which servers rarely have.
 */
public final class ChartRenderer {
    private static final int WIDTH = 900;
    private static final int ROW_HEIGHT = 28;
    private static final int TOP_PAD = 70;
    private static final int BOTTOM_PAD = 50;
    private static final int LABEL_WIDTH = 240;
    private static final int RIGHT_PAD = 30;

    private static final Color BACKGROUND = new Color(0x1E1E22);
    private static final Color TEXT = new Color(0xE8E8EC);
    private static final Color SUBTLE = new Color(0x8A8A95);
    private static final Color GRID = new Color(0x33333A);

    private static final Color PHYSICS = new Color(0x4FC3F7);
    private static final Color FLUID = new Color(0x81C784);
    private static final Color REDSTONE = new Color(0xE57373);

    private ChartRenderer() {}

    /** Renders the top hotspots to a PNG and returns the file written. */
    public static File render(List<Hotspot> hotspots, int limit, long seconds, File destination)
            throws IOException {

        final List<Hotspot> top = hotspots.subList(0, Math.min(limit, hotspots.size()));
        final int height = TOP_PAD + BOTTOM_PAD + Math.max(1, top.size()) * ROW_HEIGHT;

        final BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, height);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.setColor(TEXT);
            g.drawString(Branding.name() + " - chunk activity", 24, 32);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(SUBTLE);
            g.drawString("events recorded over " + seconds + "s, busiest chunk first", 24, 50);

            if (top.isEmpty()) {
                g.setColor(SUBTLE);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString("No activity recorded.", 24, TOP_PAD + 20);
                writePng(image, destination);
                return destination;
            }

            final long max = top.get(0).total();
            final int barArea = WIDTH - LABEL_WIDTH - RIGHT_PAD;

            for (int i = 0; i < top.size(); i++) {
                final Hotspot spot = top.get(i);
                final int y = TOP_PAD + i * ROW_HEIGHT;

                g.setColor(GRID);
                g.setStroke(new BasicStroke(1f));
                g.drawLine(LABEL_WIDTH, y + ROW_HEIGHT - 4, WIDTH - RIGHT_PAD, y + ROW_HEIGHT - 4);

                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.setColor(TEXT);
                g.drawString("chunk " + spot.chunkX() + ", " + spot.chunkZ(), 24, y + 15);
                g.setColor(SUBTLE);
                g.drawString(spot.worldName() + "  tp " + spot.blockX() + " " + spot.blockZ(), 140, y + 15);

                // Stacked proportionally, so the colours show what kind of load it is.
                int x = LABEL_WIDTH;
                x += segment(g, x, y, widthFor(spot.physics(), max, barArea), PHYSICS);
                x += segment(g, x, y, widthFor(spot.fluid(), max, barArea), FLUID);
                x += segment(g, x, y, widthFor(spot.redstone(), max, barArea), REDSTONE);

                g.setColor(SUBTLE);
                g.drawString(String.valueOf(spot.total()), Math.min(x + 8, WIDTH - RIGHT_PAD + 4), y + 15);
            }

            legend(g, height - 24);
            writePng(image, destination);
            return destination;

        } finally {
            g.dispose();
        }
    }

    private static int widthFor(long value, long max, int barArea) {
        if (max <= 0) return 0;

        return (int) Math.round((value / (double) max) * barArea);
    }

    private static int segment(Graphics2D g, int x, int y, int width, Color color) {
        if (width <= 0) return 0;
        g.setColor(color);
        g.fillRect(x, y + 4, width, ROW_HEIGHT - 12);

        return width;
    }

    private static void legend(Graphics2D g, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int x = 24;
        x = legendEntry(g, x, y, PHYSICS, "block physics");
        x = legendEntry(g, x, y, FLUID, "liquid flow");
        legendEntry(g, x, y, REDSTONE, "redstone");
    }

    private static int legendEntry(Graphics2D g, int x, int y, Color color, String label) {
        g.setColor(color);
        g.fillRect(x, y - 9, 11, 11);
        g.setColor(SUBTLE);
        g.drawString(label, x + 17, y);

        return x + 21 + g.getFontMetrics().stringWidth(label) + 18;
    }

    private static void writePng(BufferedImage image, File destination) throws IOException {
        final File parent = destination.getParentFile();

        if (parent != null) parent.mkdirs();
        ImageIO.write(image, "png", destination);
    }
}
