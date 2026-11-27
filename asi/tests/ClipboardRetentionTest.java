import com.akshaykadam.pixelboard.extension.clipboard.GboardClipboardHistoryRuntime;
import com.akshaykadam.pixelboard.extension.clipboard.GboardClipboardHistorySettings;
import java.util.Arrays;

/** Exercises the same preference methods the keyboard calls when opening the clipboard screen. */
public final class ClipboardRetentionTest {
    private static final class Preference {
        CharSequence[] K, L;
        int P, b, d;
        final String storedHours;

        Preference(String storedHours) {
            this.storedHours = storedHours;
        }

        String w(String fallback) { return storedHours == null ? fallback : storedHours; }
        void al(int max) { d = max; }
        void ap(int value, boolean notify) { b = value; }
        void d() {}
    }

    public static void main(String[] args) {
        String[] values = {"1", "24", "72", "168", "720", "876000"};
        String[] labels = {"1 hour", "24 hours", "3 days", "7 days", "30 days",
                "Never (Unlimited / Manual deletion only)"};
        for (int i = 0; i < values.length; i++) {
            Preference pref = new Preference(values[i]);
            GboardClipboardHistoryRuntime.configureClipboardHistorySlider(pref, null);
            if (!Arrays.equals(pref.K, labels) || !Arrays.equals(pref.L, values)
                    || pref.d != 5 || pref.b != i || pref.P != 1) {
                throw new AssertionError("Slider mismatch for saved hours " + values[i]);
            }
            long expected = Long.parseLong(values[i]) * 3_600_000L;
            if (GboardClipboardHistorySettings.toMillis(values[i]) != expected) {
                throw new AssertionError("Retention mismatch for " + values[i]);
            }
        }
        Preference fresh = new Preference(null);
        GboardClipboardHistoryRuntime.configureClipboardHistorySlider(fresh, null);
        if (!"24".equals(fresh.L[fresh.b])
                || GboardClipboardHistorySettings.toMillis("3d") != 259_200_000L
                || GboardClipboardHistorySettings.toMillis("invalid") != 86_400_000L) {
            throw new AssertionError("Unexpected preference fallback or duration conversion");
        }
    }
}
