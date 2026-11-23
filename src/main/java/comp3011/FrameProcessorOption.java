package comp3011;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

public enum FrameProcessorOption {
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortName;
    private final String longName;
    private final String description;
    private final Supplier<FrameProcessor> factory;

    FrameProcessorOption(char shortName, String longName, String description, Supplier<FrameProcessor> factory) {
        this.shortName = shortName;
        this.longName = longName;
        this.description = description;
        this.factory = factory;
    }

    public char shortName() {
        return shortName;
    }

    public String longName() {
        return longName;
    }

    public String description() {
        return description;
    }

    /** Creates a new, uninitialised instance of the effect this option represents. */
    public FrameProcessor create() {
        return factory.get();
    }

    public static Optional<FrameProcessorOption> fromShortName(char shortName) {
        return Arrays.stream(values()).filter(option -> option.shortName == shortName).findFirst();
    }

    public static Optional<FrameProcessorOption> fromLongName(String longName) {
        return Arrays.stream(values()).filter(option -> option.longName.equals(longName)).findFirst();
    }
}