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
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
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
    private boolean locked;
    private long firstTap = -1;
    private ComponentName provider;
    private InputConnection editor;
    private DictatedText text;
    private int seenStart, seenEnd;
    private int busyRetries;
    private int shortSilences;
    private final Runnable restart = this::beginSegment;
    private static final int FLOW_SPACE_DP = 52;
    private static final int COVER_FLOW_SPACE_DP = 76;

    private AsiVoice(InputMethodService ime, FrameLayout frame) {
        this.ime = ime;
        this.frame = frame;
        EditorInfo info = ime.getCurrentInputEditorInfo();
        seenStart = info == null ? -1 : info.initialSelStart;
        seenEnd = info == null ? -1 : info.initialSelEnd;
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
        lp.rightMargin = dp(FLOW_SPACE_DP + 6);
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

    /** Apply a real margin to the toolbar row so Gboard lays out its own keys and hit boxes. */
    public static void reserveToolbarSpace(View view) {
        // keyboard_header_view_holder in the verified upstream APK.
        if (view.getId() != 0x7f0b05e8) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
        int smallestWidth = view.getResources().getConfiguration().smallestScreenWidthDp;
        int gapDp = smallestWidth > 0 && smallestWidth < 600 ? COVER_FLOW_SPACE_DP : FLOW_SPACE_DP;
        int gap = Math.round(gapDp * view.getResources().getDisplayMetrics().density);
        // The upstream header has zero margins. Set the absolute gap each time so
        // an already-attached holder can shrink again when the phone unfolds.
        if (lp.isMarginRelative()) {
            boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            lp.setMarginStart(rtl ? gap : 0);
            lp.setMarginEnd(rtl ? 0 : gap);
        }
        lp.rightMargin = gap;
        view.setLayoutParams(lp);
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
        old.end();
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
        updateMic();
        String label = editor == null ? "Dictate" : locked ? "Mic locked · Stop"
                : session != null && session.stopping ? "Finishing…" : "Listening · Stop";
        chip.setText(label);
        chip.setContentDescription(editor == null ? "Dictate with Android System Intelligence"
                : locked ? "Microphone locked. Tap to stop dictation" : "Stop dictation");
        chip.setVisibility((inCall || editor != null) && canDictate() ? View.VISIBLE : View.GONE);
        frame.requestLayout();
    }

    private void updateMic() {
        MicIndicator.setListening(editor != null && (locked || (session != null && session.ready
                && !session.stopping && !session.speechEnded)));
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
        if (action == -0x277c && !visible.inCall && visible.editor == null) return false;
        visible.toggle();
        return true;
    }

    public static boolean selection(InputMethodService ime, int oldStart, int oldEnd,
                                    int start, int end, int composingStart, int composingEnd) {
        AsiVoice v = visible;
        if (v == null || v.ime != ime) return false;
        v.seenStart = start;
        v.seenEnd = end;
        if (v.text == null) return false;
        if (!v.text.acceptsSelection(oldStart, oldEnd, start, end)) {
            v.end();
            return false;
        }
        return v.text.ownsPreview(composingStart, composingEnd);
    }

    private void message(String text) {
        Toast.makeText(ime, text, Toast.LENGTH_LONG).show();
    }

    private void toggle() {
        if (!canDictate()) return;
        long now = SystemClock.uptimeMillis();
        boolean secondTap = firstTap >= 0 && now - firstTap <= ViewConfiguration.getDoubleTapTimeout();
        firstTap = -1;
        if (editor != null) {
            if (secondTap && !locked && session != null && !session.stopping) {
                locked = true;
                refresh();
            } else {
                locked = false;
                handler.removeCallbacks(restart);
                if (session == null) end();
                else session.stop();
            }
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
        provider = null;
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
        editor = ime.getCurrentInputConnection();
        editor.finishComposingText();
        int start = seenStart, end = seenEnd;
        ExtractedTextRequest snapshot = new ExtractedTextRequest();
        snapshot.hintMaxChars = 128;
        snapshot.hintMaxLines = 1;
        ExtractedText current = editor.getExtractedText(snapshot, 0);
        if (current != null && current.selectionStart >= 0 && current.selectionEnd >= 0) {
            start = current.startOffset + current.selectionStart;
            end = current.startOffset + current.selectionEnd;
        }
        text = new DictatedText(editor, start, end);
        busyRetries = shortSilences = 0;
        locked = secondTap;
        if (!secondTap) firstTap = now;
        beginSegment();
    }

    private boolean sameEditor() {
        return visible == this && editor != null && ime.getCurrentInputConnection() == editor && canDictate();
    }

    private void beginSegment() {
        if (!sameEditor()) { end(); return; }
        try {
            session = new Session(SpeechRecognizer.createSpeechRecognizer(ime, provider));
            session.begin();
        } catch (RuntimeException ex) {
            fail("Android System Intelligence could not start (" + ex.getClass().getSimpleName() + ").");
        }
    }

    private void end() {
        handler.removeCallbacks(restart);
        if (session != null) session.release();
        if (text != null && ime.getCurrentInputConnection() == editor) text.keep();
        locked = false;
        editor = null;
        text = null;
        refresh();
    }

    private void fail(String reason) {
        firstTap = -1;
        end();
        message(reason);
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
        final SpeechRecognizer recognizer;
        final long started = SystemClock.uptimeMillis();
        final Runnable timeout = this::timedOut;
        boolean stopping;
        boolean ready;
        boolean speechEnded;

        Session(SpeechRecognizer recognizer) {
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
            try {
                recognizer.stopListening();
                arm(2500);
            } catch (RuntimeException ex) {
                end();
            }
        }

        void release() {
            session = null;  // Discard callbacks already posted by the old request.
            handler.removeCallbacks(timeout);
            try { recognizer.cancel(); } catch (RuntimeException ignored) {}
            try { recognizer.destroy(); } catch (RuntimeException ignored) {}
        }

        void timedOut() {
            if (session != this) return;
            if (locked || stopping) complete("");
            else fail("Android System Intelligence did not respond. Tap the mic to retry.");
        }

        void complete(String words) {
            if (session != this) return;
            if (!sameEditor()) { end(); return; }
            if (!text.finish(words)) {
                fail("Dictation stopped because the text field changed or declined the edit.");
                return;
            }
            release();
            if (!locked) { end(); return; }
            busyRetries = 0;
            boolean rapidSilence = words.isEmpty() && SystemClock.uptimeMillis() - started < 1000;
            shortSilences = rapidSilence ? Math.min(4, shortSilences + 1) : 0;
            handler.postDelayed(restart, 100L << shortSilences);
        }

        String first(Bundle data) {
            List<String> words = data == null ? null : data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return words == null || words.isEmpty() || words.get(0) == null ? "" : words.get(0).trim();
        }

        private void listening(long timeoutMs) {
            if (session != this || stopping || speechEnded) return;
            if (!ready) {
                ready = true;
                updateMic();
            }
            arm(timeoutMs);
        }
        public void onReadyForSpeech(Bundle params) { listening(20000); }
        public void onBeginningOfSpeech() { listening(120000); }
        public void onEndOfSpeech() {
            if (session != this || stopping) return;
            speechEnded = true;
            updateMic();
            arm(8000);
        }
        public void onPartialResults(Bundle data) {
            if (session != this || stopping) return;
            if (!sameEditor()) { end(); return; }
            if (!text.partial(first(data))) {
                fail("Dictation stopped because the insertion point changed.");
                return;
            }
            listening(120000);
        }
        public void onResults(Bundle data) { complete(first(data)); }
        public void onError(int error) {
            if (session != this) return;
            if (!sameEditor()) { end(); return; }
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    || stopping) {
                complete("");
                return;
            }
            if (locked && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 3) {
                if (!text.finish("")) { end(); return; }
                release();
                handler.postDelayed(restart, 300L << busyRetries++);
                return;
            }
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

}
