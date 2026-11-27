import com.akshaykadam.pixelboard.extension.emoji.GboardCandidateEmojiRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CandidateOrderingTest {
    // These fields mirror the text and position fields the old hook rewrote.
    private static final class Candidate {
        @SuppressWarnings("unused") // Read reflectively by the 0.14 helper.
        final CharSequence a;
        final int l;

        Candidate(String text, int position) {
            a = text;
            l = position;
        }
    }

    private static void check(boolean readOnly) {
        Candidate word = new Candidate("happy", -1);
        Candidate emoji = new Candidate("🙂", 0);
        Candidate completion = new Candidate("happier", 1);
        List<Candidate> expected = Arrays.asList(word, emoji, completion);
        List<Candidate> input = new ArrayList<>(expected);
        if (readOnly) input = Collections.unmodifiableList(input);
        List<?> result = GboardCandidateEmojiRuntime.reorderCandidatesForStrip(null, input);
        if (word.l != -1 || emoji.l != 0 || completion.l != 1) {
            throw new AssertionError("Candidate positions were rewritten");
        }
        if (result != input || !input.equals(expected)) {
            throw new AssertionError("Candidate list was replaced or reordered");
        }
    }

    public static void main(String[] args) {
        check(true);
        check(false);
    }
}
