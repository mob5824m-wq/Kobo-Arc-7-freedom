package ca.arc7freedom.app;

/** Separately installed offline encrypted authenticator app. */
public final class Arc7AuthenticatorActivity extends Arc7VaultActivity {
    @Override protected boolean authenticator() { return true; }
}
