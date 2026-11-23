package local.gboard.asi;

import android.view.inputmethod.InputConnection;
import java.util.ArrayDeque;

/** Owns at most one mutable phrase in a single editor. All offsets are UTF-16. */
final class DictatedText {
    private final InputConnection editor;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private int anchor;
    private int caret;
    private String preview = "";
    private int preceding;
    private boolean live;

    DictatedText(InputConnection editor, int start, int end) {
        this.editor = editor;
        anchor = Math.min(start, end);
        caret = end;
        nextPhrase();
    }

    private void nextPhrase() {
        preview = "";
        live = anchor >= 0;
        preceding = -1;
        CharSequence before = editor.getTextBeforeCursor(2, 0);
        if (before != null && before.length() > 0)
            preceding = Character.codePointBefore(before, before.length());
    }

    private String format(String words) {
        int first = words.codePointAt(0);
        if (preceding >= 0 && Character.isLetterOrDigit(first)
                && (Character.isLetterOrDigit(preceding) || ".,!?;:".indexOf(preceding) >= 0)
                && needsWordSpace(first) && needsWordSpace(preceding)) return " " + words;
        return words;
    }

    // Some editors drop the composing marker between callbacks. Only re-mark text
    // we actually displayed and can still identify immediately behind the caret.
    private boolean markPreview() {
        if (preview.isEmpty()) return true;
        CharSequence before = editor.getTextBeforeCursor(preview.length(), 0);
        if (before == null || !preview.contentEquals(before)) return false;
        return editor.setComposingRegion(anchor, anchor + preview.length());
    }

    private void expect(int offset) {
        if (offset < 0) return;
        caret = offset;
        if (pending.size() == 64) pending.removeFirst();
        pending.addLast(offset);
    }

    boolean acceptsSelection(int oldStart, int oldEnd, int start, int end) {
        if (start == end && pending.contains(end)) {
            while (pending.removeFirst() != end) { /* Android may coalesce several edits. */ }
            return true;
        }
        return (oldStart == start && oldEnd == end) || (start == end && caret == end);
    }

    boolean ownsPreview(int start, int end) {
        return !preview.isEmpty() && start == anchor && end > start;
    }

    boolean partial(String words) {
        if (words.isEmpty() || !live) return true;
        String text = format(words);
        if (text.equals(preview)) return true;
        if (!markPreview()) return false;
        if (editor.setComposingText(text, 1)) {
            preview = text;
            expect(anchor + text.length());
        } else {
            // An editor can decline inline composition but still accept a final commit.
            live = false;
        }
        return true;
    }

    boolean finish(String words) {
        boolean hadPreview = !preview.isEmpty();
        if (words.isEmpty()) {
            keep();
        } else {
            String text = format(words);
            if (text.equals(preview)) {
                keep();
            } else {
                if (!markPreview() || !editor.commitText(text, 1)) return false;
                if (anchor >= 0) expect(anchor + text.length());
                preview = "";
            }
        }
        // With a selected range and no words, keep the original insertion anchor.
        if (!words.isEmpty() || hadPreview) anchor = anchor < 0 ? -1 : caret;
        nextPhrase();
        return true;
    }

    void keep() {
        if (!preview.isEmpty()) {
            editor.finishComposingText();
            preview = "";
        }
    }

    private static boolean needsWordSpace(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script != Character.UnicodeScript.HAN && script != Character.UnicodeScript.HIRAGANA
                && script != Character.UnicodeScript.KATAKANA && script != Character.UnicodeScript.THAI
                && script != Character.UnicodeScript.LAO && script != Character.UnicodeScript.KHMER
                && script != Character.UnicodeScript.MYANMAR;
    }
}
