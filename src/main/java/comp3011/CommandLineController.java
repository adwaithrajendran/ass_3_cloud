/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Adwaith Rajendran, (a1911056)
 *      - Generalised option parsing to support long, short and stacked short options.
 *      - Added frame processor selection options (see {@link FrameProcessorOption}).
 * Used the help of Claude AI for code checks, comments suggestion and help with making a build plan.
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file, supported launch options and the ordered list of frame
 * processing effects to apply, reports invalid input or help text, and exposes the
 * resulting launch configuration via methods to the {@link VideoPlayerApp}.
 * </p>
 *
 * <p>
 * Options may be given in long form ({@code --audio}), short form ({@code -a}), or as
 * stacked short options ({@code -anss}), and these forms may be freely mixed. Frame
 * processor options are recorded in the order given and may be repeated.
 * </p>
 */
public class CommandLineController {
    private static final String LONG_PREFIX = "--";
    private static final String SHORT_PREFIX = "-";

    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessorOption> frameProcessorOptions = new ArrayList<>();

    /** The player's general launch options (as opposed to frame processing effects). */
    private enum PlayerOption {
        HELP('h', "help", "Show this help message"),
        AUDIO('a', "audio", "Play audio"),
        MAXIMISE('x', "maximise", "Open the player maximised"),
        MONITOR_1('1', "monitor-1", "Open the player on display 1"),
        MONITOR_2('2', "monitor-2", "Open the player on display 2");

        private final char shortName;
        private final String longName;
        private final String description;

        PlayerOption(char shortName, String longName, String description) {
            this.shortName = shortName;
            this.longName = longName;
            this.description = description;
        }

        static Optional<PlayerOption> fromShortName(char shortName) {
            return Arrays.stream(values()).filter(option -> option.shortName == shortName).findFirst();
        }

        static Optional<PlayerOption> fromLongName(String longName) {
            return Arrays.stream(values()).filter(option -> option.longName.equals(longName)).findFirst();
        }
    }

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    /** The frame processing effects requested, in command-line order, including repeats. */
    public List<FrameProcessorOption> getFrameProcessorOptions() {
        return Collections.unmodifiableList(frameProcessorOptions);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }
    
    public boolean isSubtitlesRequested() {
        return frameProcessorOptions.contains(FrameProcessorOption.SUBTITLE);
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith(LONG_PREFIX)) {
                parseLongOption(arg);
            } else if (arg.startsWith(SHORT_PREFIX)) {
                parseShortOptions(arg);
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    // Handles a single long option such as --audio or --dust-frames.
    private void parseLongOption(String arg) {
        String name = arg.substring(LONG_PREFIX.length());

        Optional<PlayerOption> playerOption = PlayerOption.fromLongName(name);
        if (playerOption.isPresent()) {
            apply(playerOption.get());
            return;
        }

        Optional<FrameProcessorOption> frameProcessorOption = FrameProcessorOption.fromLongName(name);
        if (frameProcessorOption.isPresent()) {
            frameProcessorOptions.add(frameProcessorOption.get());
            return;
        }

        errorMessage = "Unknown option: " + arg;
    }

    // Handles one or more stacked short options such as -a or -nssn.
    private void parseShortOptions(String arg) {
        String letters = arg.substring(SHORT_PREFIX.length());
        if (letters.isEmpty()) {
            errorMessage = "Unknown option: " + arg;
            return;
        }

        for (char letter : letters.toCharArray()) {
            Optional<PlayerOption> playerOption = PlayerOption.fromShortName(letter);
            Optional<FrameProcessorOption> frameProcessorOption = FrameProcessorOption.fromShortName(letter);

            if (playerOption.isPresent()) {
                apply(playerOption.get());
            } else if (frameProcessorOption.isPresent()) {
                frameProcessorOptions.add(frameProcessorOption.get());
            } else {
                errorMessage = "Unknown option: " + SHORT_PREFIX + letter;
            }
        }
    }

    private void apply(PlayerOption option) {
        switch (option) {
            case HELP -> helpRequested = true;
            case AUDIO -> audioRequested = true;
            case MAXIMISE -> maximiseRequested = true;
            case MONITOR_1 -> setDisplayId(1);
            case MONITOR_2 -> setDisplayId(2);
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        for (PlayerOption option : PlayerOption.values()) {
            System.out.printf("  -%c, --%-11s  %s%n", option.shortName, option.longName, option.description);
        }
        System.out.println();
        System.out.println("Frame processors:");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            System.out.printf("  -%c, --%-15s  %s%n", option.shortName(), option.longName(), option.description());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}