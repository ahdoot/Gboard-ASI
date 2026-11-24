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
    private static final int UNKNOWN = -2;
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
        preceding = UNKNOWN;
    }

    void setPreceding(int codePoint) {
        preceding = codePoint;
    }

    private String format(String words) {
        if (preceding == UNKNOWN) {
            CharSequence before = editor.getTextBeforeCursor(2, 0);
            preceding = before == null || before.length() == 0 ? -1 : Character.codePointBefore(before, before.length());
        }
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

    private boolean write(String value, boolean composing) {
        // Keep re-marking and replacing the visible phrase in one editor update.
        boolean batch = !preview.isEmpty();
        if (batch) editor.beginBatchEdit();
        try {
            if (!markPreview()) return false;
            if (composing) {
                if (!editor.setComposingText(value, 1)) {
                    live = false;  // The editor can still accept the final text.
                    return true;
                }
                preview = value;
            } else {
                if (!editor.commitText(value, 1)) return false;
                preview = "";
            }
            if (anchor >= 0) expect(anchor + value.length());
            return true;
        } finally {
            if (batch) editor.endBatchEdit();
        }
    }

    boolean partial(String words) {
        if (words.isEmpty() || !live) return true;
        String text = format(words);
        return text.equals(preview) || write(text, true);
    }

    boolean finish(String words) {
        boolean hadPreview = !preview.isEmpty();
        if (words.isEmpty()) {
            keep();
        } else {
            String text = format(words);
            if (text.equals(preview)) {
                keep();
            } else if (!write(text, false)) return false;
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
