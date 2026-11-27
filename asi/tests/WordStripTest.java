import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WordStripTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static oqg candidate(int kind, String text) {
        oqg candidate = new oqg();
        candidate.w = kind;
        candidate.a = text;
        return candidate;
    }

    public static void main(String[] args) {
        oqf.h = new oqf();
        for (String word : new String[]{"computers", "۴۵", "سلام", "東京", "𐐀", "#1", "2*3", "©", "®", "™", "♥︎"}) {
            require(!AsiWordStrip.hasEmoji(word), "Lost ordinary text: " + word);
        }
        for (String emoji : new String[]{"🙂", "🏳️‍🌈", "🇮🇷", "👍🏽", "1️⃣", "#⃣", "❤️", "I ❤ this", "yes🙂", "©️"}) {
            require(AsiWordStrip.hasEmoji(emoji), "Missed emoji: " + emoji);
        }
        oqg first = candidate(1, "computers");
        oqg second = candidate(1, "compress");
        oqg third = candidate(1, "colors");
        oqg group = candidate(4, "");
        oqg emoji = candidate(5, "text label");
        oqg tagged = candidate(1, "face");
        tagged.e = oqf.h;
        List<oqg> originalWords = Arrays.asList(first, second, third);
        List<oqg> mixed = Collections.unmodifiableList(Arrays.asList(first, group, second, emoji, tagged, third));
        require(AsiWordStrip.words(originalWords) == originalWords, "Allocated a new word-only list");
        require(AsiWordStrip.words(mixed).equals(originalWords) && mixed.size() == 6, "Changed candidate order or input");
        require(AsiWordStrip.words(Arrays.asList(group, emoji)).isEmpty(), "Leaked an emoji-only batch");
        require(AsiWordStrip.wordOrNull(tagged) == null, "Leaked a highlighted emoji");
        require(AsiWordStrip.words(null) == null, "Changed reset signal");

        for (float slotWidth : new float[]{88, 144}) {
            float middle = slotWidth / 2 - 12;
            require(AsiWordStrip.firstWordOffset(middle, 0, 24, slotWidth, 1, false) == middle - 8,
                    "First label did not move toward the grid");
            require(AsiWordStrip.firstWordOffset(middle, 0, 24, slotWidth, 1, true) == middle + 8,
                    "RTL first label did not mirror");
            require(AsiWordStrip.firstWordOffset(3, 0, slotWidth - 6, slotWidth, 1, false) == 1,
                    "Long word exceeded its slot");
        }
        require(AsiWordStrip.blockedFlag("enable_fast_access_bar"), "Quick emoji bar enabled");
        require(!AsiWordStrip.blockedFlag("emoji_palette_v2"), "Manual emoji picker disabled");
        require(!AsiWordStrip.blockedFlag("enable_smart_compose"), "Word prediction disabled");
    }
}
