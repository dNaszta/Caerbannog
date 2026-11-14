import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the Caerbannog app icon: a rabbit peeking over a stream log panel. Original artwork for this project. */
public class Icon {
    static Color c(String hex) { return Color.decode(hex); }

    public static void main(String[] a) throws Exception {
        int S = 1024;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // background tile (macOS style: inset with rounded corners)
        RoundRectangle2D tile = new RoundRectangle2D.Double(64, 64, 896, 896, 200, 200);
        g.setPaint(new GradientPaint(0, 64, c("#2B3A67"), 0, 960, c("#161E3A")));
        g.fill(tile);
        g.setClip(tile);

        // ears
        Color fur = c("#F4F6FA"), inner = c("#F2A7B8");
        for (int side : new int[]{-1, 1}) {
            AffineTransform t = AffineTransform.getRotateInstance(Math.toRadians(side * 9), 512 + side * 115, 520);
            Shape ear = t.createTransformedShape(new Ellipse2D.Double(512 + side * 115 - 62, 150, 124, 400));
            Shape in = t.createTransformedShape(new Ellipse2D.Double(512 + side * 115 - 30, 215, 60, 280));
            g.setColor(fur); g.fill(ear);
            g.setColor(inner); g.fill(in);
        }
        // head
        g.setColor(fur);
        g.fill(new Ellipse2D.Double(512 - 235, 400, 470, 430));
        // eyes and nose
        g.setColor(c("#161E3A"));
        g.fill(new Ellipse2D.Double(512 - 105 - 26, 560, 52, 64));
        g.fill(new Ellipse2D.Double(512 + 105 - 26, 560, 52, 64));
        g.setColor(inner);
        g.fill(new Ellipse2D.Double(512 - 28, 650, 56, 40));

        // the log panel in front of the rabbit ("behind the rabbit"): rows of offsets
        RoundRectangle2D panel = new RoundRectangle2D.Double(64, 700, 896, 400, 0, 0);
        g.setPaint(new GradientPaint(0, 700, c("#1E7F86"), 0, 960, c("#14565F")));
        g.fill(panel);
        g.setColor(c("#7FE0DA"));
        g.fill(new Rectangle2D.Double(64, 700, 896, 14));
        float[][] rows = {{150, 770, 420}, {150, 835, 560}, {150, 900, 330}};
        for (float[] r : rows) {
            g.setColor(c("#CFF5F1"));
            g.fill(new RoundRectangle2D.Double(r[0], r[1], 56, 28, 14, 14));       // offset chip
            g.setColor(c("#8FE3DC"));
            g.fill(new RoundRectangle2D.Double(r[0] + 84, r[1], r[2], 28, 14, 14)); // message bar
        }
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
