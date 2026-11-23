package local.gboard.asi;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

/** Owned by the visible IME window. All entry points are on its main thread. */
public final class AsiVoice {
    private static final String ASI = "com.google.android.as";
    private static AsiVoice visible;
    private final InputMethodService ime;
    private final FrameLayout frame;
    private final TextView chip;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] position = new int[2];
    private final Rect bounds = new Rect();
    private Runnable unregister;
    private Session session;
    private boolean inCall;

    private AsiVoice(InputMethodService ime, FrameLayout frame) {
        this.ime = ime;
        this.frame = frame;
        audio = ime.getSystemService(AudioManager.class);
        chip = new TextView(ime);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setMaxWidth(dp(260));
        chip.setElevation(dp(4));
        chip.setOnClickListener(v -> toggle());
        chip.setVisibility(View.INVISIBLE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xff384469);
        chip.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.RIGHT);
        lp.rightMargin = dp(6);
        frame.addView(chip, lp);
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioManager.OnModeChangedListener listener = this::modeChanged;
                audio.addOnModeChangedListener(ime.getMainExecutor(), listener);
                unregister = () -> audio.removeOnModeChangedListener(listener);
            }
            modeChanged(audio.getMode());
        }
        refresh();
    }

    private int dp(int value) {
        return Math.round(value * ime.getResources().getDisplayMetrics().density);
    }

    public static void show(InputMethodService ime) {
        if (visible != null && visible.ime == ime) {
            visible.refresh();
            return;
        }
        if (visible != null) hide(visible.ime);
        if (ime.getWindow() == null || ime.getWindow().getWindow() == null) return;
        View content = ime.getWindow().getWindow().findViewById(android.R.id.content);
        if (content instanceof FrameLayout) visible = new AsiVoice(ime, (FrameLayout) content);
    }

    public static void hide(InputMethodService ime) {
        AsiVoice old = visible;
        if (old == null || old.ime != ime) return;
        visible = null;
        if (old.session != null) old.session.close();
        if (old.unregister != null) old.unregister.run();
        old.frame.removeView(old.chip);
    }

    private void modeChanged(int mode) {
        inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT;
        refresh();
    }

    private boolean canDictate() {
        EditorInfo e = ime.getCurrentInputEditorInfo();
        if (e == null || e.inputType == InputType.TYPE_NULL || ime.getCurrentInputConnection() == null) return false;
        int cls = e.inputType & InputType.TYPE_MASK_CLASS;
        int variation = e.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_NUMBER) return variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return cls != InputType.TYPE_CLASS_TEXT || (variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    private void refresh() {
        chip.setText(session == null ? "Dictate" : session.stopping ? "Finishing…" : "Listening · Stop");
        chip.setContentDescription(session == null ? "Dictate with Android System Intelligence" : "Stop dictation");
        chip.setVisibility((inCall || session != null) && canDictate() ? View.VISIBLE : View.GONE);
        frame.requestLayout();
    }

    /** Pin the button at the keyboard edge. Limit extra touch ownership to the chip. */
    public static void place(InputMethodService ime, InputMethodService.Insets insets) {
        AsiVoice v = visible;
        if (v == null || v.ime != ime || v.chip.getVisibility() != View.VISIBLE) return;
        v.frame.getLocationInWindow(v.position);
        int w = v.chip.getWidth(), h = v.chip.getHeight();
        if (w <= 0 || h <= 0) return;
        int top = Math.max(v.position[1], insets.visibleTopInsets - h - v.dp(2));
        top = Math.min(top, v.position[1] + Math.max(0, v.frame.getHeight() - h));
        v.chip.setTranslationY(top - v.position[1] - v.chip.getTop());
        int left = v.position[0] + v.chip.getLeft();
        v.bounds.set(left, top, left + w, top + h);
        if (insets.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_FRAME) return;
        if (insets.touchableInsets != InputMethodService.Insets.TOUCHABLE_INSETS_REGION) {
            int edge = insets.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
                    ? insets.contentTopInsets : insets.visibleTopInsets;
            View root = v.frame.getRootView();
            insets.touchableRegion.set(0, edge, root.getWidth(), root.getHeight());
            insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
        }
        insets.touchableRegion.op(v.bounds, Region.Op.UNION);
    }

    /** Called only for upstream microphone actions. Synthetic auto-starts keep their original route. */
    public static boolean mic(InputMethodService ime, int action, Object data) {
        if ("auto start voice".equals(data)) return false;
        if (visible == null || visible.ime != ime || !visible.canDictate()) return false;
        if (action == -0x277c && !visible.inCall) return false;
        visible.toggle();
        return true;
    }

    public static void selection(InputMethodService ime, int oldStart, int oldEnd, int start, int end) {
        if (visible != null && visible.ime == ime && visible.session != null
                && (oldStart != start || oldEnd != end)) visible.session.close();
    }

    private void message(String text) {
        Toast.makeText(ime, text, Toast.LENGTH_LONG).show();
    }

    private void toggle() {
        if (!canDictate()) return;
        if (session != null) {
            session.stop();
            return;
        }
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message("Open Permissions → Microphone and allow access, then tap Dictate again.");
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ime.getPackageName(), null));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (settings.resolveActivity(ime.getPackageManager()) != null) ime.startActivity(settings);
            return;
        }
        Intent lookup = new Intent(RecognitionService.SERVICE_INTERFACE).setPackage(ASI);
        ComponentName provider = null;
        for (ResolveInfo item : ime.getPackageManager().queryIntentServices(lookup, 0)) {
            ServiceInfo s = item.serviceInfo;
            if (s != null && s.exported && ASI.equals(s.packageName)
                    && (s.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                provider = new ComponentName(s.packageName, s.name);
                break;
            }
        }
        if (provider == null) {
            message("Android System Intelligence has no available speech recognition service on this device.");
            return;
        }
        InputConnection editor = ime.getCurrentInputConnection();
        editor.finishComposingText();
        try {
            SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(ime, provider);
            session = new Session(editor, recognizer);
            session.begin();
        } catch (RuntimeException ex) {
            if (session != null) session.close();
            message("Android System Intelligence could not start (" + ex.getClass().getSimpleName() + ").");
        }
    }

    @SuppressWarnings("deprecation")
    private String language() {
        InputMethodManager manager = ime.getSystemService(InputMethodManager.class);
        InputMethodSubtype subtype = manager == null ? null : manager.getCurrentInputMethodSubtype();
        if (subtype != null && !TextUtils.isEmpty(subtype.getLanguageTag())) return subtype.getLanguageTag();
        if (subtype != null && !TextUtils.isEmpty(subtype.getLocale())) return subtype.getLocale().replace('_', '-');
        return Locale.getDefault().toLanguageTag();
    }

    private final class Session implements RecognitionListener {
        final InputConnection editor;
        final SpeechRecognizer recognizer;
        final Runnable timeout = () -> fail("Android System Intelligence did not respond. Tap Dictate to retry.");
        boolean stopping;

        Session(InputConnection editor, SpeechRecognizer recognizer) {
            this.editor = editor;
            this.recognizer = recognizer;
        }

        void begin() {
            refresh();
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language());
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            request.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ime.getPackageName());
            recognizer.setRecognitionListener(this);
            recognizer.startListening(request);
            arm(12000);
        }

        void arm(long ms) {
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, ms);
        }

        void stop() {
            if (stopping) return;
            stopping = true;
            refresh();
            recognizer.stopListening();
            arm(8000);
        }

        void close() {
            if (session != this) return;
            session = null;
            handler.removeCallbacks(timeout);
            try {
                recognizer.cancel();
            } finally {
                recognizer.destroy();
                refresh();
            }
        }

        void fail(String reason) {
            if (session != this) return;
            close();
            message(reason);
        }

        String first(Bundle data) {
            List<String> words = data == null ? null : data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return words == null || words.isEmpty() || words.get(0) == null ? "" : words.get(0).trim();
        }

        public void onReadyForSpeech(Bundle params) { if (session == this) arm(20000); }
        public void onBeginningOfSpeech() { if (session == this) arm(120000); }
        public void onEndOfSpeech() { if (session == this) arm(8000); }
        public void onPartialResults(Bundle data) {
            if (session != this || stopping) return;
            String text = first(data);
            if (!text.isEmpty()) chip.setText(text);
            arm(120000);
        }
        public void onResults(Bundle data) {
            if (session != this) return;
            String text = first(data);
            boolean valid = visible == AsiVoice.this && ime.getCurrentInputConnection() == editor && canDictate();
            close();
            if (!valid) return;
            if (text.isEmpty()) {
                message("No words heard by Android System Intelligence.");
                return;
            }
            CharSequence before = editor.getTextBeforeCursor(1, 0);
            if (!TextUtils.isEmpty(before) && Character.isLetterOrDigit(before.charAt(0))
                    && Character.isLetterOrDigit(text.codePointAt(0)) && needsWordSpace(text.codePointAt(0))
                    && needsWordSpace(before.charAt(0))) text = " " + text;
            if (!editor.commitText(text, 1)) message("This text field did not accept the dictation.");
        }
        public void onError(int error) {
            String detail;
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO: detail = "Microphone audio unavailable"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: detail = "Recognizer busy"; break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: detail = "No speech heard"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: detail = "Microphone permission denied"; break;
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: detail = "Language model unavailable"; break;
                default: detail = "Recognition failed";
            }
            fail("Android System Intelligence · " + detail + " (" + error + ")");
        }
        public void onRmsChanged(float value) {}
        public void onBufferReceived(byte[] buffer) {}
        public void onEvent(int event, Bundle params) {}
    }

    private static boolean needsWordSpace(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script != Character.UnicodeScript.HAN && script != Character.UnicodeScript.HIRAGANA
                && script != Character.UnicodeScript.KATAKANA && script != Character.UnicodeScript.THAI
                && script != Character.UnicodeScript.LAO && script != Character.UnicodeScript.KHMER
                && script != Character.UnicodeScript.MYANMAR;
    }
}
