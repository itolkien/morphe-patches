package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertSame;

import android.text.Spanned;

import org.junit.Test;

public class NativeCaptionTypographyTest {
    private record NativeText(String value) implements Spanned {
        @Override
        public String toString() {
            return value;
        }

        public int length() {
            return value.length();
        }

        public char charAt(int index) {
            return value.charAt(index);
        }

        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        public <T> T[] getSpans(int start, int end, Class<T> type) {
            throw new AssertionError("Unchanged native spans must not be inspected or copied");
        }

        public int getSpanStart(Object tag) {
            throw new AssertionError();
        }

        public int getSpanEnd(Object tag) {
            throw new AssertionError();
        }

        public int getSpanFlags(Object tag) {
            throw new AssertionError();
        }

        public int nextSpanTransition(int start, int limit, Class type) {
            throw new AssertionError();
        }
    }

    @Test
    public void disabledNativeRichTextRemainsUnchanged() {
        String text = "plain native text";
        assertSame(text, NativeCaptionTypography.apply(text, 0, 0));
        assertSame(text, NativeCaptionTypography.apply(text, 2, 0));
    }

    @Test
    public void normalAndUnknownOffsetsKeepTheNativeObject() {
        var text = new NativeText("styled native text");
        for (int offset : new int[] {-1, 1, 3, Integer.MAX_VALUE}) {
            assertSame(text, NativeCaptionTypography.apply(text, offset, 0));
        }
    }

    @Test
    public void emptyStyledTextRemainsUnchanged() {
        var text = new NativeText("");
        assertSame(text, NativeCaptionTypography.apply(text, 0, 0));
        assertSame(text, NativeCaptionTypography.apply(text, 2, 0));
    }

    @Test
    public void inheritedAndUnknownFontsKeepTheNativeObject() {
        var text = new NativeText("native font");
        for (int font : new int[] {0, -1, 8, Integer.MAX_VALUE}) {
            assertSame(text, NativeCaptionTypography.apply(text, 1, font));
        }
    }
}
