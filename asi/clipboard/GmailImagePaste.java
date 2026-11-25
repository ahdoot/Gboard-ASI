package local.gboard.asi.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.lang.ref.WeakReference;

/** Use Gmail's ordinary Paste command when an image tap cannot use rich-content insertion. */
public final class GmailImagePaste {
    private static final String GMAIL = "com.google.android.gm";
    private static WeakReference<InputMethodService> active = new WeakReference<>(null);

    public static void bind(InputMethodService ime) {
        if (active.get() != ime) active = new WeakReference<>(ime);
    }

    public static boolean paste(EditorInfo target, Uri uri, String mime) {
        if (Looper.myLooper() != Looper.getMainLooper() || target == null || !GMAIL.equals(target.packageName)
                || uri == null || !"content".equals(uri.getScheme()) || mime == null || !mime.startsWith("image/"))
            return false;

        InputMethodService ime = active.get();
        if (ime == null || !ime.isInputViewShown()) return false;
        EditorInfo current = ime.getCurrentInputEditorInfo();
        if (current == null || !GMAIL.equals(current.packageName)
                || current.fieldId != target.fieldId || current.inputType != target.inputType) return false;
        InputConnection input = ime.getCurrentInputConnection();
        ClipboardManager clipboard = ime.getSystemService(ClipboardManager.class);
        if (input == null || clipboard == null) return false;

        try {
            // A history tile can be older than the system clipboard. Publish the exact selected
            // URI so Android can grant read access to the foreground editor when it pastes.
            ClipData image = new ClipData("Image", new String[] {mime}, new ClipData.Item(uri));
            clipboard.setPrimaryClip(image);
            return input.performContextMenuAction(android.R.id.paste);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private GmailImagePaste() {}
}
