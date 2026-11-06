package com.ch4nhm.tvapp;

/**
 * Decides whether an asynchronous pan login completed even though the
 * provider did not report an explicit success state.
 */
final class PanLoginCompletion {

    private PanLoginCompletion() {
    }

    static boolean credentialsUpdated(boolean configured, String before, String after) {
        return configured
                && after != null
                && !after.isBlank()
                && !after.equals(before);
    }
}
