package local.gboard.asi;

public final class SpokenPunctuationTest {
    private static void check(String raw, String expected) {
        String result = SpokenPunctuation.format(raw);
        if (!result.equals(expected)) {
            throw new AssertionError(raw + " produced [" + result + "] instead of [" + expected + "]");
        }
    }

    public static void main(String[] args) {
        check("hey comma how are you question mark", "hey, how are you?");
        check("Pickup COMMA then pack semi-colon ship FULL STOP.", "Pickup, then pack; ship.");
        check("Wait exclamation point", "Wait!");
        check("Where are you, question mark?", "Where are you?");
        check("hello , comma, there", "hello, there");
        check("thanks comma, see you period.", "thanks, see you.");
        check("Comma.", ",");
        check("do it comma comma", "do it,,");
        check("Wait ellipsis", "Wait…");
        check("I said open quote send it close quote period", "I said “send it”.");
        check("A open parenthesis red close parenthesis slash blue", "A (red)/blue");
        check("a dot b hyphen c backslash d", "a.b-c\\d");
        check("Jonah apostrophe s", "Jonah's");
        check("one colon two open bracket three close bracket", "one: two [three]");
        check("hello\ncomma  there", "hello\n,  there");
        check("full\u00a0stop", ".");
        check("حالا comma بله", "حالا, بله");
        check("a comma-separated list of commands in a periodic report", "a comma-separated list of commands in a periodic report");
        check("Hello. Already punctuated!", "Hello. Already punctuated!");
        check("", "");

        // Each revised live result is a complete hypothesis and can freely change direction.
        check("are you ready question", "are you ready question");
        check("are you ready question mark", "are you ready?");
        check("are you ready Question Mark.", "are you ready?");
        check("have a question marketing can answer", "have a question marketing can answer");
    }
}
