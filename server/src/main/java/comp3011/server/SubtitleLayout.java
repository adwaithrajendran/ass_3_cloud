package comp3011.server;

import java.util.ArrayList;
import java.util.List;

public class SubtitleLayout {
    private static final int MAX_LINE_LENGTH = 42;
    private static final double MIN_DURATION = 1.0;
    private static final double MAX_DURATION = 7.0;
    private static final double PAUSE_GAP = 0.6;
    private static final double LINGER = 0.3;

    public record Cue(double start, double end, List<String> lines) {
    }

    private record TimedToken(String text, double start, double end) {
    }

    public List<Cue> layout(Transcription transcription, double offsetSeconds) {
        List<List<TimedToken>> groups = group(speechTokens(transcription));
        List<Cue> cues = new ArrayList<>();

        for (int i = 0; i < groups.size(); i++) {
            List<TimedToken> group = groups.get(i);
            double start = group.get(0).start();
            double end = Math.max(group.get(group.size() - 1).end() + LINGER, start + MIN_DURATION);
            end = Math.min(end, start + MAX_DURATION);
            if (i + 1 < groups.size()) {
                end = Math.min(end, groups.get(i + 1).get(0).start());
            }
            if (end <= start) {
                end = start + 0.1;
            }
            cues.add(new Cue(start + offsetSeconds, end + offsetSeconds, wrapOrForce(join(group))));
        }
        return cues;
    }

    private List<TimedToken> speechTokens(Transcription transcription) {
        List<Transcription.Word> words = transcription.words();
        List<TimedToken> tokens = new ArrayList<>();
        int wordIndex = 0;

        for (Transcription.Segment segment : transcription.segments()) {
            List<Transcription.Word> segmentWords = new ArrayList<>();
            while (wordIndex < words.size()) {
                Transcription.Word word = words.get(wordIndex);
                double middle = (word.start() + word.end()) / 2;
                if (middle >= segment.end()) {
                    break;
                }
                if (middle >= segment.start()) {
                    segmentWords.add(word);
                }
                wordIndex++;
            }

            if (!segment.isSpeech() || segment.text() == null || segment.text().isBlank()) {
                continue;
            }

            String[] texts = segment.text().trim().split("\\s+");
            if (texts.length == segmentWords.size()) {
                for (int i = 0; i < texts.length; i++) {
                    tokens.add(new TimedToken(texts[i], segmentWords.get(i).start(), segmentWords.get(i).end()));
                }
            } else {
                double from = segmentWords.isEmpty() ? segment.start() : segmentWords.get(0).start();
                double to = segmentWords.isEmpty() ? segment.end() : segmentWords.get(segmentWords.size() - 1).end();
                double step = (to - from) / texts.length;
                for (int i = 0; i < texts.length; i++) {
                    tokens.add(new TimedToken(texts[i], from + i * step, from + (i + 1) * step));
                }
            }
        }
        return tokens;
    }

    private List<List<TimedToken>> group(List<TimedToken> tokens) {
        List<List<TimedToken>> groups = new ArrayList<>();
        List<TimedToken> current = new ArrayList<>();

        for (TimedToken token : tokens) {
            if (!current.isEmpty() && startsNewCue(current, token)) {
                boolean pause = token.start() - current.get(current.size() - 1).end() > PAUSE_GAP;
                int split = pause ? current.size() : lastPhraseBreak(current);
                groups.add(new ArrayList<>(current.subList(0, split)));
                current = new ArrayList<>(current.subList(split, current.size()));
            }
            current.add(token);
            if (endsSentence(token.text())) {
                groups.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return mergeShortGroups(groups);
    }

    private int lastPhraseBreak(List<TimedToken> current) {
        for (int i = current.size() - 2; i >= current.size() / 2; i--) {
            if (endsWithPunctuation(current.get(i).text())) {
                return i + 1;
            }
        }
        return current.size();
    }

    private List<List<TimedToken>> mergeShortGroups(List<List<TimedToken>> groups) {
        List<List<TimedToken>> merged = new ArrayList<>();
        for (List<TimedToken> group : groups) {
            if (!merged.isEmpty()) {
                List<TimedToken> previous = merged.get(merged.size() - 1);
                double duration = group.get(group.size() - 1).end() - group.get(0).start();
                double gap = group.get(0).start() - previous.get(previous.size() - 1).end();
                List<TimedToken> combined = new ArrayList<>(previous);
                combined.addAll(group);
                double combinedDuration = group.get(group.size() - 1).end() - previous.get(0).start();
                if (duration < MIN_DURATION && gap <= PAUSE_GAP && combinedDuration <= MAX_DURATION
                        && wrap(join(combined)) != null) {
                    merged.set(merged.size() - 1, combined);
                    continue;
                }
            }
            merged.add(group);
        }
        return merged;
    }

    private boolean startsNewCue(List<TimedToken> current, TimedToken next) {
        TimedToken last = current.get(current.size() - 1);
        if (next.start() - last.end() > PAUSE_GAP) {
            return true;
        }
        if (next.end() - current.get(0).start() > MAX_DURATION) {
            return true;
        }
        List<TimedToken> candidate = new ArrayList<>(current);
        candidate.add(next);
        return wrap(join(candidate)) == null;
    }

    private static boolean endsSentence(String text) {
        return text.endsWith(".") || text.endsWith("?") || text.endsWith("!")
                || text.endsWith(".\"") || text.endsWith("?\"") || text.endsWith("!\"");
    }

    private static boolean endsWithPunctuation(String text) {
        return !text.isEmpty() && ",.;:?!-\"".indexOf(text.charAt(text.length() - 1)) >= 0;
    }

    private static String join(List<TimedToken> tokens) {
        StringBuilder text = new StringBuilder();
        for (TimedToken token : tokens) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(token.text());
        }
        return text.toString();
    }

    static List<String> wrap(String text) {
        if (text.length() <= MAX_LINE_LENGTH) {
            return List.of(text);
        }

        int bestSplit = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ' ') {
                continue;
            }
            String first = text.substring(0, i);
            String second = text.substring(i + 1);
            if (first.length() > MAX_LINE_LENGTH || second.length() > MAX_LINE_LENGTH) {
                continue;
            }
            int score = Math.abs(first.length() - second.length());
            if (!endsWithPunctuation(first)) {
                score += 1000;
            }
            if (score < bestScore) {
                bestScore = score;
                bestSplit = i;
            }
        }

        if (bestSplit < 0) {
            return null;
        }
        return List.of(text.substring(0, bestSplit), text.substring(bestSplit + 1));
    }

    private static List<String> wrapOrForce(String text) {
        List<String> lines = wrap(text);
        if (lines != null) {
            return lines;
        }
        String first = text.substring(0, Math.min(MAX_LINE_LENGTH, text.length()));
        String rest = text.substring(first.length()).trim();
        return rest.isEmpty() ? List.of(first) : List.of(first, rest.substring(0, Math.min(MAX_LINE_LENGTH, rest.length())));
    }
}