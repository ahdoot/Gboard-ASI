package local.gboard.asi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats one complete ASR hypothesis. Revised hypotheses never reuse the previous output. */
public final class SpokenPunctuation {
    private static final Map<String, Character> MARKS = new HashMap<>();
    private static final Pattern SEPARATOR = Pattern.compile("[\\p{Zs}\\t-]+");
    private static final Pattern COMMAND;
    private static final String OPEN = "“‘([{";
    private static final String JOIN = "'-/\\—–";
    private static final String SENTENCE = ",.!?:;…";

    static {
        add(',', "comma");
        add('.', "period", "full stop", "dot");
        add('?', "question mark");
        add('!', "exclamation point", "exclamation mark", "exclamation");
        add(':', "colon");
        add(';', "semicolon", "semi colon");
        add('…', "ellipsis", "three dots");
        add('“', "quote", "open quote", "opening quote", "open double quote", "double quote",
                "quotation mark");
        add('”', "unquote", "close quote", "closing quote", "end quote", "close double quote");
        add('‘', "open single quote", "opening single quote");
        add('’', "close single quote", "closing single quote");
        add('\'', "apostrophe");
        add('(', "open parenthesis", "open parentheses", "left parenthesis", "open paren");
        add(')', "close parenthesis", "close parentheses", "right parenthesis", "close paren");
        add('[', "open bracket", "left bracket", "open square bracket");
        add(']', "close bracket", "right bracket", "close square bracket");
        add('{', "open brace", "left brace", "open curly brace");
        add('}', "close brace", "right brace", "close curly brace");
        add('-', "hyphen");
        add('—', "dash", "em dash");
        add('–', "en dash");
        add('/', "slash", "forward slash");
        add('\\', "backslash", "back slash");

        List<String> names = new ArrayList<>(MARKS.keySet());
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        // Names contain only ASCII letters and spaces. Accept normal ASR variants like semi-colon.
        String alternatives = String.join("|", names).replace(" ", SEPARATOR.pattern());
        String word = "[\\p{L}\\p{M}\\p{N}_'’\\-]";
        COMMAND = Pattern.compile("(?<!" + word + ")(?:" + alternatives + ")(?!" + word + ")",
                Pattern.CASE_INSENSITIVE);
    }

    private SpokenPunctuation() {}

    private static void add(char symbol, String... names) {
        for (String name : names) MARKS.put(name, symbol);
    }

    private static boolean horizontalSpace(char c) {
        return c == '\t' || Character.isSpaceChar(c) && c != '\u2028' && c != '\u2029';
    }

    public static String format(String text) {
        Matcher match = COMMAND.matcher(text);
        if (!match.find()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        do {
            String name = SEPARATOR.matcher(match.group().toLowerCase(Locale.ROOT)).replaceAll(" ");
            Character symbol = MARKS.get(name);
            if (symbol == null) continue; // A Unicode case-fold match outside the English vocabulary.
            char mark = symbol;
            boolean opens = OPEN.indexOf(mark) >= 0;
            boolean joins = JOIN.indexOf(mark) >= 0 || name.equals("dot");

            int left = match.start();
            if (!opens) {
                while (left > copied && horizontalSpace(text.charAt(left - 1))) left--;
                // Some recognizers insert a mark immediately before its spoken name.
                // Only remove a mark in this input span, never one from a prior spoken command.
                if (left > copied && (text.charAt(left - 1) == mark
                        || text.charAt(left - 1) == ',' && SENTENCE.indexOf(mark) >= 0)) {
                    left--;
                    while (left > copied && horizontalSpace(text.charAt(left - 1))) left--;
                }
            }
            out.append(text, copied, left).append(mark);

            copied = match.end();
            // Automatic punctuation after an explicit command can arrive only on the final result.
            if (copied < text.length()) {
                char after = text.charAt(copied);
                if (after == mark || SENTENCE.indexOf(mark) >= 0 && ".,?!".indexOf(after) >= 0) copied++;
            }
            if (opens || joins) {
                while (copied < text.length() && horizontalSpace(text.charAt(copied))) copied++;
            } else if (copied < text.length() && Character.isLetterOrDigit(text.codePointAt(copied))) {
                out.append(' ');
            }
        } while (match.find());
        return out.append(text, copied, text.length()).toString();
    }
}
