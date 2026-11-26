package local.gboard.asi.haptics;

import android.app.NotificationManager;
import android.content.Context;
import android.view.View;

public final class DndHaptics {
    private DndHaptics() {}

    public static boolean allowed(Context context) {
        if (context == null) return false;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null
                    && manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean feedback(View view, int effect) {
        return allowed(view.getContext()) && view.performHapticFeedback(effect);
    }

    public static boolean feedback(View view, int effect, int flags) {
        return allowed(view.getContext()) && view.performHapticFeedback(effect, flags);
    }
}
