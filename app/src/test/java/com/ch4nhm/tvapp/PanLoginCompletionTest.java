package com.ch4nhm.tvapp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PanLoginCompletionTest {

    @Test
    public void existingUnchangedCredentialDoesNotCompleteLogin() {
        assertFalse(PanLoginCompletion.credentialsUpdated(
                true, "old-fingerprint", "old-fingerprint"));
    }

    @Test
    public void newCredentialCompletesLoginAfterReauthentication() {
        assertTrue(PanLoginCompletion.credentialsUpdated(
                true, "old-fingerprint", "new-fingerprint"));
    }

    @Test
    public void firstSuccessfulLoginCompletesLogin() {
        assertTrue(PanLoginCompletion.credentialsUpdated(true, "", "new-fingerprint"));
    }

    @Test
    public void unconfiguredProviderDoesNotCompleteLogin() {
        assertFalse(PanLoginCompletion.credentialsUpdated(
                false, "old-fingerprint", "new-fingerprint"));
    }
}
