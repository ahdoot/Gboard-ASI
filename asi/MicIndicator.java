package local.gboard.asi;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** A temporary drawable on the actual microphone key. The themed idle icon stays underneath. */
public final class MicIndicator extends Drawable implements View.OnLayoutChangeListener {
    private static final Map<View, MicIndicator> keys = new WeakHashMap<>();
    private static boolean listening;
    private final WeakReference<Object> owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect iconBounds = new Rect();
    private boolean dark;
    private int alpha = 255;

    private MicIndicator(Object model) {
        owner = new WeakReference<>(model);
    }

    public static void bind(View key, Object model, String name, int action) {
        if (key == null) return;
        boolean microphone = action == -0x273a || action == -0x277c || action == -0x27ce
                || action == -0x27e9 || "voice".equals(name) || "mic_animation".equals(name)
                || "push_to_ramble".equals(name);
        MicIndicator previous = keys.get(key);
        if (previous != null) {
            if (microphone && previous.owner.get() == model) {
                if (listening) previous.position(key);
                return;
            }
            if (listening) previous.show(key, false);
            keys.remove(key);
        }
        if (microphone) {
            MicIndicator badge = new MicIndicator(model);
            keys.put(key, badge);
            if (listening) badge.show(key, true);
        }
    }

    public static void unbind(View key, Object model) {
        MicIndicator badge = keys.get(key);
        if (badge == null || badge.owner.get() != model) return;
        if (listening) badge.show(key, false);
        keys.remove(key);
    }

    static void setListening(boolean active) {
        if (listening == active) return;
        listening = active;
        for (Map.Entry<View, MicIndicator> entry : keys.entrySet())
            entry.getValue().show(entry.getKey(), active);
    }

    private void show(View key, boolean active) {
        if (active) {
            position(key);
            key.addOnLayoutChangeListener(this);
            key.getOverlay().add(this);
        } else {
            key.removeOnLayoutChangeListener(this);
            key.getOverlay().remove(this);
        }
    }

    private void position(View key) {
        // Image slot shared by Gboard's toolbar layouts in this upstream build.
        View icon = key.findViewById(0x7f0b03c0);
        iconBounds.set(0, 0, key.getWidth(), key.getHeight());
        if (icon != null && icon != key && key instanceof ViewGroup && icon.getWidth() > 0
                && icon.getHeight() > 0 && icon.getVisibility() == View.VISIBLE) {
            iconBounds.set(0, 0, icon.getWidth(), icon.getHeight());
            ((ViewGroup) key).offsetDescendantRectToMyCoords(icon, iconBounds);
        }
        int size = Math.min(Math.round(30 * key.getResources().getDisplayMetrics().density),
                Math.min(key.getWidth(), key.getHeight()));
        int left = iconBounds.centerX() - size / 2;
        int top = iconBounds.centerY() - size / 2;
        setBounds(left, top, left + size, top + size);
        int mode = key.getResources().getConfiguration().uiMode;
        dark = (mode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        invalidateSelf();
    }

    @Override public void onLayoutChange(View key, int l, int t, int r, int b,
                                          int ol, int ot, int or, int ob) { position(key); }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 32f, bounds.height() / 32f);
        paint.setStyle(Paint.Style.FILL);
        color(dark ? 0xff26272c : 0xffffffff);
        canvas.drawCircle(16, 16, 16, paint);
        color(dark ? 0xff8ab4f8 : 0xff4285f4);
        canvas.drawRoundRect(12.8f, 5, 19.2f, 17.5f, 3.2f, 3.2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        color(dark ? 0xff81c995 : 0xff34a853);
        canvas.drawLine(16, 21.5f, 16, 26, paint);
        canvas.drawArc(9.3f, 8.7f, 22.7f, 22.1f, 53, 78, false, paint);
        color(dark ? 0xfffdd663 : 0xfffbbc04);
        canvas.drawArc(9.3f, 8.7f, 22.7f, 22.1f, 131, 49, false, paint);
        color(dark ? 0xfff28b82 : 0xffea4335);
        canvas.drawArc(9.3f, 8.7f, 22.7f, 22.1f, 0, 53, false, paint);
        canvas.restoreToCount(save);
    }

    private void color(int argb) {
        paint.setColor(argb);
        paint.setAlpha(alpha);
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
