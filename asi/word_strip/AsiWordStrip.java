import android.content.SharedPreferences;
import android.graphics.RectF;
import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Prediction policy and first-label placement for the signed ASI keyboard. */
public final class AsiWordStrip {
    private static final int WORD_LABEL = 0x7f0b05f3;
    private static final int WORD_HOLDER = 0x7f0b14e5;
    private static final int SPLIT_GROUP = 0x7f0b067b;
    private static final int COMPACT_ROW_TAG = 0x7e010001;
    private static final String HOLDER_CLASS =
            "com.google.android.apps.inputmethod.latin.keyboard.widget.LatinFixedCountCandidatesHolderView";

    public static void disableEmojiPrefs(SharedPreferences prefs) {
        String predictions = "pref_key_enable_emoji_suggestion";
        String quickBar = "enable_fast_access_bar";
        if (prefs.getBoolean(predictions, true) || prefs.getBoolean(quickBar, true)) {
            prefs.edit().putBoolean(predictions, false).putBoolean(quickBar, false).apply();
        }
    }

    public static boolean blockedFlag(String name) {
        if (name == null) return false;
        switch (name) {
            case "enable_emoji_suggestion_from_top_candidate":
            case "enable_predictions_from_emoji_shortcuts":
            case "enable_ngram_emoji_shortcuts_by_spans":
            case "enable_ngram_emoji_shortcuts_for_prediction":
            case "retain_emoji_shortcut_candidates_on_selection":
            case "enable_fast_access_bar":
            case "enable_emojify_shortcut_when_followed_by_emoji":
                return true;
            default:
                return false;
        }
    }

    static boolean hasEmoji(CharSequence text) {
        if (text == null) return false;
        int length = text.length();
        for (int i = 0; i < length;) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (cp == 0x20e3) return true; // Combining enclosing keycap.
            if (cp < 0xa9) continue; // Digits, # and * are ordinary text on their own.
            int next = i < length ? Character.codePointAt(text, i) : -1;
            if (next == 0xfe0e) continue; // Explicit text presentation.
            if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) return true;
            if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)) {
                if (next == 0xfe0f) return true;
                // Hearts, hands and similar unqualified pictographs can occur in
                // mixed word predictions. Keep ordinary copyright/trademark text.
                if (Character.getType(cp) == Character.OTHER_SYMBOL
                        && cp != 0xa9 && cp != 0xae && cp != 0x2122) return true;
            }
        }
        return false;
    }

    public static oqg wordOrNull(oqg candidate) {
        if (candidate == null || candidate.w == 4 || candidate.w == 5 || candidate.e == oqf.h) {
            return null;
        }
        return hasEmoji(candidate.a) ? null : candidate;
    }

    public static List<oqg> words(List<oqg> input) {
        if (input == null) return null;
        ArrayList<oqg> filtered = null;
        for (int i = 0, size = input.size(); i < size; i++) {
            oqg candidate = input.get(i);
            if (candidate != null && wordOrNull(candidate) == null) {
                if (filtered == null) {
                    filtered = new ArrayList<>(size);
                    filtered.addAll(input.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(candidate);
            }
        }
        return filtered == null ? input : filtered;
    }

    static float firstWordOffset(float original, float inkLeft, float inkRight,
                                 float width, boolean rtl) {
        float space = rtl ? width - (inkRight + original) : inkLeft + original;
        // Anchor to the leading edge, down to one physical pixel for glyph antialiasing.
        float shift = Math.max(0f, space - 1f);
        return rtl ? original + shift : original - shift;
    }

    // The native holder creates its keys in logical order, including nested split keys.
    private static boolean firstVisibleChild(ViewGroup group, View child) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View item = group.getChildAt(i);
            if (item.getVisibility() == View.VISIBLE) return item == child;
        }
        return false;
    }

    // Preserve the row's total width and each word's usable tap target. Give
    // released width to words that would otherwise shrink, then to the last cell.
    static void fitWords(int[] widths, int[] wanted, int count, int minTarget) {
        int spare = 0;
        for (int i = 0; i < count; i++) {
            int compact = Math.min(widths[i], Math.max(minTarget, wanted[i]));
            spare += widths[i] - compact;
            widths[i] = compact;
        }
        for (int i = 0; i < count && spare > 0; i++) {
            int give = Math.min(spare, Math.max(0, wanted[i] - widths[i]));
            widths[i] += give;
            spare -= give;
        }
        widths[count - 1] += spare;
    }

    public static void compactWords(LinearLayout row) {
        row.setTag(COMPACT_ROW_TAG, null);
        int count = row.getChildCount();
        if (count < 2) return;
        View[] keys = new View[count];
        int[] nativeWidths = new int[count];
        int[] wanted = new int[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            View key = row.getChildAt(i);
            if (key.getVisibility() != View.VISIBLE) continue;
            if (key.getId() == SPLIT_GROUP) {
                ViewGroup group = (ViewGroup) key;
                int visible = 0;
                for (int j = 0; j < group.getChildCount(); j++) {
                    if (group.getChildAt(j).getVisibility() == View.VISIBLE) visible++;
                }
                if (visible != 1) return;
            }
            View candidate = key.findViewById(WORD_LABEL);
            if (!(candidate instanceof TextView) || candidate.getVisibility() != View.VISIBLE) return;
            TextView label = (TextView) candidate;
            CharSequence text = label.getText();
            int width = key.getMeasuredWidth();
            if (text == null || text.length() == 0 || width <= 0 || label.getMeasuredWidth() <= 0) return;
            int structure = Math.max(0, width - label.getMeasuredWidth());
            int ink = (int) Math.ceil(android.text.Layout.getDesiredWidth(text, label.getPaint()));
            keys[used] = key;
            nativeWidths[used] = width;
            wanted[used] = ink + structure + label.getCompoundPaddingLeft() + label.getCompoundPaddingRight();
            used++;
        }
        if (used < 2) return;
        row.setTag(COMPACT_ROW_TAG, Boolean.TRUE);
        fitWords(nativeWidths, wanted, used, Math.round(48 * row.getResources().getDisplayMetrics().density));
        for (int i = 0; i < used; i++) {
            View key = keys[i];
            if (nativeWidths[i] != key.getMeasuredWidth()) {
                key.measure(View.MeasureSpec.makeMeasureSpec(nativeWidths[i], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(key.getMeasuredHeight(), View.MeasureSpec.EXACTLY));
            }
        }
    }

    public static float align(View label, RectF available, RectF ink, float original) {
        if (label.getId() != WORD_LABEL) return original;
        View child = label;
        for (ViewParent p = child.getParent(); p instanceof ViewGroup; p = child.getParent()) {
            ViewGroup parent = (ViewGroup) p;
            if (parent.getId() == SPLIT_GROUP && !firstVisibleChild(parent, child)) return original;
            if (parent instanceof LinearLayout && (parent.getId() == WORD_HOLDER
                    || HOLDER_CLASS.equals(parent.getClass().getName()))) {
                boolean first = firstVisibleChild(parent, child);
                if (!first && parent.getTag(COMPACT_ROW_TAG) != Boolean.TRUE) return original;
                // The following labels keep a small inset next to their divider.
                float inset = first ? 0 : Math.max(0f, 3 * label.getResources().getDisplayMetrics().density - 1f);
                return firstWordOffset(original, ink.left - inset, ink.right + inset,
                        available.right + label.getPaddingRight(),
                        parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            }
            child = parent;
        }
        return original;
    }
}
