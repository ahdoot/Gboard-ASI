package local.gboard.asi;

import android.view.inputmethod.InputConnection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Host-side editor contract tests. Execute with the Android SDK android.jar on the classpath. */
public final class DictatedTextTest {
    private static final class Field implements InvocationHandler {
        String value;
        int contextReads;
        int batches;
        boolean inBatch;
        int start, end;
        int markedStart = -1, markedEnd;
        boolean supportsComposition = true;
        final InputConnection connection = (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(), new Class<?>[]{InputConnection.class}, this);

        Field(String value, int start, int end) {
            this.value = value;
            this.start = start;
            this.end = end;
        }
        DictatedText begin() { return new DictatedText(connection, start, end); }

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            int insertion = Math.min(start, end);
            switch (name) {
                case "beginBatchEdit":
                    require(!inBatch);
                    inBatch = true;
                    batches++;
                    return true;
                case "endBatchEdit":
                    require(inBatch);
                    inBatch = false;
                    return true;
                case "getTextBeforeCursor":
                    contextReads++;
                    return value.substring(Math.max(0, insertion - (Integer) args[0]), insertion);
                case "setComposingRegion":
                    require(inBatch);
                    if (!supportsComposition) return false;
                    markedStart = (Integer) args[0];
                    markedEnd = (Integer) args[1];
                    return true;
                case "setComposingText":
                case "commitText":
                    boolean composing = name.equals("setComposingText");
                    if (composing && !supportsComposition) return false;
                    int left = markedStart < 0 ? insertion : markedStart;
                    int right = markedStart < 0 ? Math.max(start, end) : markedEnd;
                    String replacement = args[0].toString();
                    value = value.substring(0, left) + replacement + value.substring(right);
                    start = end = left + replacement.length();
                    markedStart = composing ? left : -1;
                    markedEnd = end;
                    return true;
                case "finishComposingText":
                    markedStart = -1;
                    return true;
                default:
                    throw new AssertionError(name);
            }
        }
    }

    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    private static void eq(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        Field f = new Field("Pickup at  today", 10, 10);
        DictatedText text = f.begin();
        require(text.finish(""));
        require(f.contextReads == 0);
        text.setPreceding(' ');
        require(text.partial("for"));
        require(f.contextReads == 0);
        require(f.batches == 0);
        eq(f.value, "Pickup at for today");
        require(text.partial("four"));
        require(f.batches == 1 && !f.inBatch);
        eq(f.value, "Pickup at four today");
        // Coalesced self-generated cursor updates must leave the mic running.
        require(text.acceptsSelection(10, 10, 14, 14));
        f.markedStart = -1;
        require(text.finish("four thirty"));
        eq(f.value, "Pickup at four thirty today");
        require(text.acceptsSelection(14, 14, f.end, f.end));
        require(text.finish(""));  // silence during locked dictation
        require(text.partial("tomorrow"));
        require(text.finish("tomorrow"));  // identical partial and final
        eq(f.value, "Pickup at four thirty tomorrow today");
        require(f.markedStart == -1);

        f = new Field("Red crate", 3, 0);  // reversed selection
        text = f.begin();
        require(text.finish(""));
        require(text.partial("Blue"));
        require(text.finish("Green"));
        eq(f.value, "Green crate");

        f = new Field("Hello", 5, 5);
        text = f.begin();
        require(text.partial("there"));
        text.keep();  // explicit stop with no final or editor closing
        eq(f.value, "Hello there");
        require(f.markedStart == -1);

        f = new Field("Go", 2, 2);
        f.supportsComposition = false;
        text = f.begin();
        require(text.partial("home"));
        eq(f.value, "Go");
        require(text.finish("home"));
        eq(f.value, "Go home");

        f = new Field("A", 1, 1);
        text = f.begin();
        require(text.partial("test"));
        require(!text.acceptsSelection(6, 6, 0, 0));
        f.start = f.end = 0;
        require(!text.partial("testing"));
        require(!f.inBatch);
        eq(f.value, "A test");

        f = new Field("東京", 2, 2);
        text = f.begin();
        require(text.finish("駅"));
        eq(f.value, "東京駅");

        f = new Field("🦊 ", 3, 3);
        text = f.begin();
        require(text.partial("🌙"));
        require(text.partial("🌙 moon"));
        require(text.finish("🌙 moon"));
        eq(f.value, "🦊 🌙 moon");

        System.out.println("Dictated text editor tests passed");
    }
}
