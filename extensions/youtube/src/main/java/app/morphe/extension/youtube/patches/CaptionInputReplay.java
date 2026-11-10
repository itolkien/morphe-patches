/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/** Bounded buffering with byte-exact replay and parser-owned stream lifetime. */
final class CaptionInputReplay {
    private CaptionInputReplay() {}

    public static final class Copy {
        public final InputStream input;
        public final byte[] bytes;
        public final boolean complete;

        Copy(InputStream input, byte[] bytes, boolean complete) {
            this.input = input;
            this.bytes = bytes;
            this.complete = complete;
        }
    }

    /** Bound collection; replay the prefix and remaining stream byte-for-byte. */
    public static Copy copy(InputStream input, int limit) throws IOException {
        if (limit < 0 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= limit) {
            int n = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()));
            if (n < 0) {
                byte[] bytes = output.toByteArray();
                return new Copy(replay(bytes, input), bytes, true);
            }
            if (n == 0) continue;
            output.write(buffer, 0, n);
        }
        byte[] bytes = output.toByteArray();
        return new Copy(replay(bytes, input), bytes, false);
    }

    private static InputStream replay(byte[] bytes, InputStream input) {
        // SequenceInputStream closes each component at EOF. The parser owns the source
        // lifetime, so defer its close until the replay itself is explicitly closed.
        InputStream remainder = new FilterInputStream(input) {
            @Override
            public void close() {}
        };
        return new SequenceInputStream(new ByteArrayInputStream(bytes), remainder) {
            private boolean closed;

            @Override
            public synchronized void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    super.close();
                } finally {
                    input.close();
                }
            }
        };
    }
}
