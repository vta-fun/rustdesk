package dev.rustdeskfix.ime;

import android.util.Log;

import java.lang.reflect.Method;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Makes the Android soft keyboard reach the remote peer the way a physical keyboard would.
 *
 * RustDesk's hidden input field is rewritten in place by IMEs: romaji becomes kana, kana gains
 * dakuten, and a conversion replaces the composing run. Flutter reports only the final string,
 * so the remote peer never sees the deletions that the rewrite implies and the text arrives
 * garbled. This class replays those deletions as individual edits before the final state.
 */
public final class ImeEditReplayer {
    private static final String TAG = "RustDeskImePatch";
    private static final int FILLER_PREFIX_LENGTH = 32;
    private static final int MAX_REMOVED_CHARACTERS = 256;

    private static String dartText;
    private static boolean lookupFailed;
    private static Method updateEditingState;

    private ImeEditReplayer() {}

    /** Records the text Flutter currently believes the hidden input field holds. */
    public static void rememberDartText(String text) {
        dartText = text;
    }

    /**
     * Replays the character deletions between the remembered text and {@code text}.
     *
     * @param plugin the Flutter text input plugin instance that owns the client
     * @param clientId the client whose editing state is being updated
     */
    public static void replayRemovals(Object plugin, int clientId, String text) {
        String previous = dartText;
        dartText = null;
        if (previous == null || !isHiddenField(previous) || !isHiddenField(text)) return;
        for (String state : removalStates(previous, text)) {
            if (!send(plugin, clientId, state)) return;
        }
    }

    private static List<String> removalStates(String previous, String next) {
        int commonPrefixLength = commonPrefixLength(previous, next);
        if (commonPrefixLength == previous.length()) return Collections.emptyList();

        List<String> states = new ArrayList<>();
        BreakIterator boundaries = BreakIterator.getCharacterInstance();
        boundaries.setText(previous);
        boundaries.last();
        int boundary;
        do {
            boundary = boundaries.previous();
            if (boundary == -1) return Collections.emptyList();
            if (states.size() == MAX_REMOVED_CHARACTERS) return Collections.emptyList();
            states.add(previous.substring(0, boundary));
        } while (boundary > commonPrefixLength);

        if (boundary == next.length()) states.remove(states.size() - 1);
        return states;
    }

    private static int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) index++;
        return index;
    }

    private static boolean send(Object plugin, int clientId, String text) {
        if (lookupFailed) return false;
        try {
            if (updateEditingState == null) updateEditingState = findUpdateEditingState(plugin.getClass());
        } catch (NoSuchMethodException | RuntimeException error) {
            lookupFailed = true;
            Log.w(TAG, "Failed to find updateEditingState", error);
            return false;
        }
        try {
            updateEditingState.invoke(plugin, clientId, text, text.length(), text.length(), -1, -1);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Failed to replay a soft keyboard edit", error);
            return false;
        }
    }

    private static Method findUpdateEditingState(Class<?> pluginClass) throws NoSuchMethodException {
        Class<?>[] expected = {int.class, String.class, int.class, int.class, int.class, int.class};
        for (Method method : pluginClass.getDeclaredMethods()) {
            if (method.getReturnType() != void.class) continue;
            if (!Arrays.equals(method.getParameterTypes(), expected)) continue;
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException("updateEditingState in " + pluginClass.getName());
    }

    /** RustDesk keeps its hidden field prefilled with a run of '1' characters. */
    private static boolean isHiddenField(String text) {
        if (text.length() < FILLER_PREFIX_LENGTH) return false;
        for (int index = 0; index < FILLER_PREFIX_LENGTH; index++) {
            if (text.charAt(index) != '1') return false;
        }
        return true;
    }
}
