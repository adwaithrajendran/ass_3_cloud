package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;

import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {
    private static final double FONT_HEIGHT_FRACTION = 0.055;
    private static final int MIN_FONT_SIZE = 12;
    private static final double MAX_TEXT_WIDTH_FRACTION = 0.92;
    private static final double BOTTOM_MARGIN_FRACTION = 0.06;
    private static final double OUTLINE_FRACTION = 0.2;
    private static final double LINE_SPACING = 1.2;

    private SubtitleTrack track;
    private volatile Overlay cachedOverlay;

    public FrameSubtitler() {
    }

    public void attach(SubtitleTrack track) {
        this.track = track;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameSubtitler requires 8-bit image frames");
        }
        if (videoInfo.imageChannels() != 1 && videoInfo.imageChannels() < 3) {
            throw new Exception("FrameSubtitler requires 1 (grey) or 3+ (BGR) image channels");
        }
        cachedOverlay = null;
        renderOverlay(new SubtitleCue(0, 1, List.of("Warm-up 0123456789, ?!'")));
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (track == null || !track.isEnabled()) {
            return;
        }
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }

        SubtitleCue cue = track.cueAt(info.timestampUs());
        if (cue == null || cue.lines().isEmpty()) {
            return;
        }

        Overlay overlay = cachedOverlay;
        if (overlay == null || overlay.cue() != cue) {
            overlay = renderOverlay(cue);
            cachedOverlay = overlay;
        }
        blend(pixels, overlay);
    }

    private Overlay renderOverlay(SubtitleCue cue) {
        int frameWidth = videoInfo.imageWidth();
        int frameHeight = videoInfo.imageHeight();

        FontRenderContext context = new FontRenderContext(null, true, true);
        Font font = fittedFont(cue.lines(), frameWidth, frameHeight, context);
        float outlineWidth = (float) Math.max(2.0, font.getSize2D() * OUTLINE_FRACTION);
        int lineHeight = (int) Math.ceil(font.getSize2D() * LINE_SPACING);
        int padding = (int) Math.ceil(outlineWidth);
        int overlayHeight = lineHeight * cue.lines().size() + 2 * padding;

        BufferedImage image = new BufferedImage(frameWidth, overlayHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 0; i < cue.lines().size(); i++) {
                TextLayout layout = new TextLayout(cue.lines().get(i), font, context);
                double x = (frameWidth - layout.getAdvance()) / 2.0;
                double baseline = padding + i * lineHeight + layout.getAscent();
                Shape glyphs = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));

                g.setColor(Color.BLACK);
                g.draw(glyphs);
                g.setColor(Color.WHITE);
                g.fill(glyphs);
            }
        } finally {
            g.dispose();
        }

        int bottomMargin = (int) Math.round(frameHeight * BOTTOM_MARGIN_FRACTION);
        int top = Math.max(0, frameHeight - bottomMargin - overlayHeight);
        return Overlay.from(cue, image, top, frameHeight);
    }

    private Font fittedFont(List<String> lines, int frameWidth, int frameHeight, FontRenderContext context) {
        float size = (float) Math.max(MIN_FONT_SIZE, frameHeight * FONT_HEIGHT_FRACTION);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);

        double widest = 0;
        for (String line : lines) {
            if (!line.isEmpty()) {
                widest = Math.max(widest, new TextLayout(line, font, context).getAdvance());
            }
        }
        double maxWidth = frameWidth * MAX_TEXT_WIDTH_FRACTION;
        if (widest > maxWidth) {
            font = font.deriveFont((float) Math.max(MIN_FONT_SIZE, size * maxWidth / widest));
        }
        return font;
    }

    private void blend(ByteBuffer pixels, Overlay overlay) {
        int stride = videoInfo.imageStride();
        int channels = videoInfo.imageChannels();

        for (int i = 0; i < overlay.offsets().length; i++) {
            int argb = overlay.argb()[i];
            int alpha = argb >>> 24;
            int red = (argb >> 16) & 0xFF;
            int green = (argb >> 8) & 0xFF;
            int blue = argb & 0xFF;

            int x = overlay.offsets()[i] % overlay.width();
            int y = overlay.top() + overlay.offsets()[i] / overlay.width();
            int base = y * stride + x * channels;

            if (channels == 1) {
                int grey = (red * 299 + green * 587 + blue * 114) / 1000;
                blendChannel(pixels, base, grey, alpha);
            } else {
                blendChannel(pixels, base, blue, alpha);
                blendChannel(pixels, base + 1, green, alpha);
                blendChannel(pixels, base + 2, red, alpha);
            }
        }
    }

    private static void blendChannel(ByteBuffer pixels, int index, int source, int alpha) {
        int destination = pixels.get(index) & 0xFF;
        pixels.put(index, (byte) ((source * alpha + destination * (255 - alpha) + 127) / 255));
    }

    private record Overlay(SubtitleCue cue, int width, int top, int[] offsets, int[] argb) {

        static Overlay from(SubtitleCue cue, BufferedImage image, int top, int frameHeight) {
            int width = image.getWidth();
            int visibleRows = Math.min(image.getHeight(), frameHeight - top);
            int[] all = image.getRGB(0, 0, width, visibleRows, null, 0, width);

            int visible = 0;
            for (int pixel : all) {
                if ((pixel >>> 24) != 0) {
                    visible++;
                }
            }

            int[] offsetArray = new int[visible];
            int[] argbArray = new int[visible];
            int next = 0;
            for (int i = 0; i < all.length; i++) {
                if ((all[i] >>> 24) != 0) {
                    offsetArray[next] = i;
                    argbArray[next] = all[i];
                    next++;
                }
            }
            return new Overlay(cue, width, top, offsetArray, argbArray);
        }
    }
}