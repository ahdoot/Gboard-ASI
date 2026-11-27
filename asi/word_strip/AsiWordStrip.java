import android.content.SharedPreferences;
import android.graphics.RectF;
import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Prediction policy and first-label placement for the signed ASI keyboard. */
public final class AsiWordStrip {
    private static final int WORD_LABEL = 0x7f0b05f3;
    private static final int WORD_HOLDER = 0x7f0b14e5;
    private static final int SPLIT_GROUP = 0x7f0b067b;
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
                                 float width, float density, boolean rtl) {
        float space = rtl ? width - (inkRight + original) : inkLeft + original;
        float shift = Math.min(8f * density, Math.max(0f, space - density));
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

    public static float align(View label, RectF available, RectF ink, float original) {
        if (label.getId() != WORD_LABEL) return original;
        View child = label;
        for (ViewParent p = child.getParent(); p instanceof ViewGroup; p = child.getParent()) {
            ViewGroup parent = (ViewGroup) p;
            if (parent.getId() == SPLIT_GROUP && !firstVisibleChild(parent, child)) return original;
            if (parent instanceof LinearLayout && (parent.getId() == WORD_HOLDER
                    || HOLDER_CLASS.equals(parent.getClass().getName()))) {
                if (!firstVisibleChild(parent, child)) return original;
                return firstWordOffset(original, ink.left, ink.right,
                        available.right + label.getPaddingRight(),
                        label.getResources().getDisplayMetrics().density,
                        parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            }
            child = parent;
        }
        return original;
    }
}
