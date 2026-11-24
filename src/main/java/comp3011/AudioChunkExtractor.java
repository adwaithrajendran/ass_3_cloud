package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

public class AudioChunkExtractor implements AutoCloseable {
    private static final double MIN_CHUNK_SECONDS = 0.5;

    private final FFmpegFrameGrabber grabber;

    public AudioChunkExtractor(File mediaFile) throws IOException {
        if (!mediaFile.canRead()) {
            throw new IOException("Media file is not readable: " + mediaFile.getName());
        }
        grabber = new FFmpegFrameGrabber(mediaFile);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
    }

    public long durationUs() {
        return grabber.getLengthInTime();
    }

    public byte[] extractWav(long startUs, long lengthUs) throws IOException {
        if (!grabber.hasAudio()) {
            return null;
        }

        grabber.setTimestamp(startUs);
        long endUs = startUs + lengthUs;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int sampleRate = 0;

        Frame frame;
        while ((frame = grabber.grabSamples()) != null) {
            long timestampUs = grabber.getTimestamp();
            if (timestampUs >= endUs) {
                break;
            }
            if (timestampUs < startUs || frame.samples == null || frame.sampleRate <= 0) {
                continue;
            }
            sampleRate = frame.sampleRate;
            appendMono(frame, pcm);
        }

        if (sampleRate <= 0 || pcm.size() < sampleRate * 2 * MIN_CHUNK_SECONDS) {
            return null;
        }
        return toWav(pcm.toByteArray(), sampleRate);
    }

    @Override
    public void close() {
        try {
            grabber.stop();
        } catch (Exception e) {
        }
        try {
            grabber.close();
        } catch (Exception e) {
        }
    }

    static void appendMono(Frame frame, ByteArrayOutputStream out) {
        Buffer[] samples = frame.samples;
        int channels = Math.max(1, frame.audioChannels);

        if (channels > 1 && samples.length == channels) {
            ShortBuffer[] planes = new ShortBuffer[channels];
            int count = Integer.MAX_VALUE;
            for (int c = 0; c < channels; c++) {
                if (!(samples[c] instanceof ShortBuffer plane)) {
                    return;
                }
                planes[c] = plane;
                count = Math.min(count, plane.remaining());
            }
            for (int i = 0; i < count; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += planes[c].get(planes[c].position() + i);
                }
                writeSample(out, sum / channels);
            }
        } else if (samples.length > 0 && samples[0] instanceof ShortBuffer interleaved) {
            int base = interleaved.position();
            int count = interleaved.remaining() / channels;
            for (int i = 0; i < count; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += interleaved.get(base + i * channels + c);
                }
                writeSample(out, sum / channels);
            }
        }
    }

    private static void writeSample(ByteArrayOutputStream out, int sample) {
        out.write(sample & 0xFF);
        out.write((sample >> 8) & 0xFF);
    }

    static byte[] toWav(byte[] pcm, int sampleRate) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + pcm.length);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes());
        header.putInt(pcm.length);

        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }
}