package nexus.web;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class SampleImageGenerator {

    private SampleImageGenerator() {
    }

    public static byte[] createSampleNexusImage() {
        int width = 160;
        int height = 160;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(242, 242, 247));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(0, 122, 255));
        g.fillOval(20, 20, 120, 120);

        g.setColor(new Color(255, 255, 255, 90));
        g.drawOval(35, 35, 90, 90);
        g.drawOval(45, 45, 70, 70);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString("N", 63, 96);

        g.setColor(new Color(28, 28, 30));
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("NEXUS PROTOCOL", 28, 152);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }
}

