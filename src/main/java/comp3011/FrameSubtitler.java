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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {
    private static final double FONT_HEIGHT_FRACTION = 0.055;
    private static final int MIN_FONT_SIZE = 12;
    private static final double MAX_TEXT_WIDTH_FRACTION = 0.92;
    private static final double BOTTOM_MARGIN_FRACTION = 0.06;
    private static final double OUTLINE_FRACTION = 0.2;
    private static final double LINE_SPACING = 1.2;
    private static final int MAX_CACHED_OVERLAYS = 32;

    private final Map<SubtitleCue, Overlay> overlays = new ConcurrentHashMap<>();
    private volatile Geometry geometry;
    private SubtitleTrack track;

    public FrameSubtitler() {
    }

    public void attach(SubtitleTrack track) {
        this.track = track;
        track.addCueListener(this::prerender);
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
        Geometry newGeometry = new Geometry(
                videoInfo.imageWidth(),
                videoInfo.imageHeight(),
                videoInfo.imageStride(),
                videoInfo.imageChannels());
        geometry = newGeometry;
        overlays.clear();
        renderOverlay(new SubtitleCue(0, 1, List.of("Warm-up 0123456789, ?!'")), newGeometry);
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (track == null) {
            return;
        }
        long timestampUs = info.timestampUs();
        track.reportPlayhead(timestampUs);

        if (!track.isEnabled()) {
            return;
        }
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }
        Geometry currentGeometry = geometry;
        if (currentGeometry == null) {
            return;
        }

        SubtitleCue cue = track.cueAt(timestampUs);
        if (cue == null || cue.lines().isEmpty()) {
            return;
        }

        Overlay overlay = overlays.get(cue);
        if (overlay == null || overlay.geometry() != currentGeometry) {
            overlay = renderOverlay(cue, currentGeometry);
            overlays.put(cue, overlay);
        }
        blend(pixels, overlay);

        if (overlays.size() > MAX_CACHED_OVERLAYS) {
            overlays.keySet().removeIf(cached -> cached.endUs() < timestampUs);
        }
    }

    private void prerender(List<SubtitleCue> cues) {
        Geometry currentGeometry = geometry;
        if (currentGeometry == null) {
            return;
        }
        for (SubtitleCue cue : cues) {
            overlays.computeIfAbsent(cue, key -> renderOverlay(key, currentGeometry));
        }
    }

    private Overlay renderOverlay(SubtitleCue cue, Geometry target) {
        FontRenderContext context = new FontRenderContext(null, true, true);
        Font font = fittedFont(cue.lines(), target, context);
        float outlineWidth = (float) Math.max(2.0, font.getSize2D() * OUTLINE_FRACTION);
        int lineHeight = (int) Math.ceil(font.getSize2D() * LINE_SPACING);
        int padding = (int) Math.ceil(outlineWidth);
        int overlayHeight = lineHeight * cue.lines().size() + 2 * padding;

        BufferedImage image = new BufferedImage(target.width(), overlayHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 0; i < cue.lines().size(); i++) {
                TextLayout layout = new TextLayout(cue.lines().get(i), font, context);
                double x = (target.width() - layout.getAdvance()) / 2.0;
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

        int bottomMargin = (int) Math.round(target.height() * BOTTOM_MARGIN_FRACTION);
        int top = Math.max(0, target.height() - bottomMargin - overlayHeight);
        return Overlay.from(target, image, top);
    }

    private Font fittedFont(List<String> lines, Geometry target, FontRenderContext context) {
        float size = (float) Math.max(MIN_FONT_SIZE, target.height() * FONT_HEIGHT_FRACTION);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);

        double widest = 0;
        for (String line : lines) {
            if (!line.isEmpty()) {
                widest = Math.max(widest, new TextLayout(line, font, context).getAdvance());
            }
        }
        double maxWidth = target.width() * MAX_TEXT_WIDTH_FRACTION;
        if (widest > maxWidth) {
            font = font.deriveFont((float) Math.max(MIN_FONT_SIZE, size * maxWidth / widest));
        }
        return font;
    }

    private static void blend(ByteBuffer pixels, Overlay overlay) {
        Geometry target = overlay.geometry();
        int stride = target.stride();
        int channels = target.channels();
        int width = target.width();

        for (int i = 0; i < overlay.offsets().length; i++) {
            int argb = overlay.argb()[i];
            int alpha = argb >>> 24;
            int red = (argb >> 16) & 0xFF;
            int green = (argb >> 8) & 0xFF;
            int blue = argb & 0xFF;

            int x = overlay.offsets()[i] % width;
            int y = overlay.top() + overlay.offsets()[i] / width;
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

    private record Geometry(int width, int height, int stride, int channels) {
    }

    private record Overlay(Geometry geometry, int top, int[] offsets, int[] argb) {

        static Overlay from(Geometry geometry, BufferedImage image, int top) {
            int width = image.getWidth();
            int visibleRows = Math.min(image.getHeight(), geometry.height() - top);
            int[] all = image.getRGB(0, 0, width, visibleRows, null, 0, width);

            int visible = 0;
            for (int pixel : all) {
                if ((pixel >>> 24) != 0) {
                    visible++;
                }
            }

            int[] offsets = new int[visible];
            int[] argb = new int[visible];
            int next = 0;
            for (int i = 0; i < all.length; i++) {
                if ((all[i] >>> 24) != 0) {
                    offsets[next] = i;
                    argb[next] = all[i];
                    next++;
                }
            }
            return new Overlay(geometry, top, offsets, argb);
        }
    }
}