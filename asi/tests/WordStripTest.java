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
            require(AsiWordStrip.firstWordOffset(middle, 0, 24, slotWidth, false) == 1,
                    "First label did not move toward the grid");
            require(AsiWordStrip.firstWordOffset(middle, 0, 24, slotWidth, true) == slotWidth - 25,
                    "RTL first label did not mirror");
            require(AsiWordStrip.firstWordOffset(3, 0, slotWidth - 6, slotWidth, false) == 1,
                    "Long word exceeded its slot");
        }
        int[] folded = {144, 161, 149};
        AsiWordStrip.fitWords(folded, new int[]{93, 155, 161}, 3, 90);
        require(Arrays.equals(folded, new int[]{93, 155, 206}), "Short first word retained its empty space");
        int[] longFirst = {120, 120, 120};
        AsiWordStrip.fitWords(longFirst, new int[]{170, 55, 130}, 3, 80);
        require(Arrays.equals(longFirst, new int[]{160, 80, 120}), "Long first word lost available width");
        int[] narrow = {48, 48, 48};
        AsiWordStrip.fitWords(narrow, new int[]{30, 30, 30}, 3, 60);
        require(Arrays.equals(narrow, new int[]{48, 48, 48}), "Narrow targets shrank further");
        require(AsiWordStrip.blockedFlag("enable_fast_access_bar"), "Quick emoji bar enabled");
        require(!AsiWordStrip.blockedFlag("emoji_palette_v2"), "Manual emoji picker disabled");
        require(!AsiWordStrip.blockedFlag("enable_smart_compose"), "Word prediction disabled");
    }
}
