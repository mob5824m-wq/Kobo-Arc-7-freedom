package ca.arc7freedom.app;

/** Separately installed offline encrypted passwords app. */
public final class Arc7PasswordsActivity extends Arc7VaultActivity {
    @Override protected boolean authenticator() { return false; }
}
