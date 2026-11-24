package comp3011;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class SubtitleTrack {
    private final ConcurrentSkipListMap<Long, SubtitleCue> cuesByStart = new ConcurrentSkipListMap<>();
    private final List<Consumer<List<SubtitleCue>>> cueListeners = new CopyOnWriteArrayList<>();
    private volatile boolean enabled;
    private volatile long playheadUs;

    public SubtitleTrack(boolean initiallyEnabled) {
        this.enabled = initiallyEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    public long playheadUs() {
        return playheadUs;
    }

    public void reportPlayhead(long timestampUs) {
        playheadUs = timestampUs;
    }

    public void addCueListener(Consumer<List<SubtitleCue>> listener) {
        cueListeners.add(listener);
    }

    public void addCues(Collection<SubtitleCue> cues) {
        for (SubtitleCue cue : cues) {
            cuesByStart.put(cue.startUs(), cue);
        }
        List<SubtitleCue> added = List.copyOf(cues);
        for (Consumer<List<SubtitleCue>> listener : cueListeners) {
            listener.accept(added);
        }
    }

    public SubtitleCue cueAt(long timestampUs) {
        Map.Entry<Long, SubtitleCue> entry = cuesByStart.floorEntry(timestampUs);
        if (entry == null) {
            return null;
        }
        SubtitleCue cue = entry.getValue();
        return cue.covers(timestampUs) ? cue : null;
    }
}