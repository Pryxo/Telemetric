package dev.telemetric.game;

import com.mojang.blaze3d.platform.NativeImage;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * MC ADAPTER: decodes an image file from the user (PNG, JPG, animated GIF) into {@link NativeImage} frames, scaled
 * down to at most {@link #MAX_SIZE} px. GIF frames are composited the way a browser does it (frame offsets and
 * disposal methods). It's slow for big GIFs, so call it off the render thread. The caller owns and closes the
 * returned images.
 */
public final class ImageFrames {

    public static final int MAX_SIZE = 256;
    private static final int MAX_FRAMES = 300, DEFAULT_DELAY_MS = 100, MIN_DELAY_MS = 20;

    /** One frame and how long it shows. */
    public record Frame(NativeImage image, int delayMs) {}

    private ImageFrames() {}

    public static boolean isGif(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /** All frames of the file (just one for still images). Empty if it can't be read. */
    public static List<Frame> decode(Path file) throws IOException {
        if (isGif(file)) return decodeGif(file);
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) return List.of();
        return List.of(new Frame(toNative(fit(img)), DEFAULT_DELAY_MS));
    }

    private static List<Frame> decodeGif(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(Files.newInputStream(file))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return List.of();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false);
                int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
                int[] size = logicalSize(reader);
                BufferedImage canvas = null;
                List<Frame> frames = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    BufferedImage part = reader.read(i);
                    Node meta = root(reader.getImageMetadata(i), "javax_imageio_gif_image_1.0");
                    int left = intAttr(child(meta, "ImageDescriptor"), "imageLeftPosition", 0);
                    int top = intAttr(child(meta, "ImageDescriptor"), "imageTopPosition", 0);
                    Node gce = child(meta, "GraphicControlExtension");
                    int delay = intAttr(gce, "delayTime", 0) * 10;
                    String disposal = gce == null ? "none" : attr(gce, "disposalMethod", "none");
                    if (canvas == null) {
                        int w = size != null ? size[0] : part.getWidth() + left;
                        int h = size != null ? size[1] : part.getHeight() + top;
                        canvas = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
                    }
                    BufferedImage before = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                    Graphics2D g = canvas.createGraphics();
                    g.drawImage(part, left, top, null);
                    g.dispose();
                    frames.add(new Frame(toNative(fit(copy(canvas))), delay < MIN_DELAY_MS ? DEFAULT_DELAY_MS : delay));
                    if (disposal.equals("restoreToBackgroundColor")) {
                        Graphics2D c = canvas.createGraphics();
                        c.setComposite(AlphaComposite.Clear);
                        c.fillRect(left, top, part.getWidth(), part.getHeight());
                        c.dispose();
                    } else if (before != null) {
                        canvas = before;
                    }
                }
                return frames;
            } finally {
                reader.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int[] logicalSize(ImageReader reader) throws IOException {
        IIOMetadata stream = reader.getStreamMetadata();
        if (stream == null) return null;
        Node lsd = child(root(stream, "javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
        int w = intAttr(lsd, "logicalScreenWidth", 0), h = intAttr(lsd, "logicalScreenHeight", 0);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    private static Node root(IIOMetadata meta, String format) {
        try {
            return meta.getAsTree(format);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) return n;
        }
        return null;
    }

    private static String attr(Node node, String name, String fallback) {
        Node a = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(attr(node, name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Scales the image down (keeping the aspect ratio) if it's larger than {@link #MAX_SIZE}. */
    private static BufferedImage fit(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w <= MAX_SIZE && h <= MAX_SIZE) return img;
        double s = (double) MAX_SIZE / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static NativeImage toNative(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                out.setPixelABGR(x, y, (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >>> 16) & 0xFF));
            }
        }
        return out;
    }
}
