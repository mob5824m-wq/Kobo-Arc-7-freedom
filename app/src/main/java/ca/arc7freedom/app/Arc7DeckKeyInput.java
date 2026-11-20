package ca.arc7freedom.app;

/** Per-key gesture ownership. Android View owns taps/edit gestures; PTT owns the entire touch. */
public final class Arc7DeckKeyInput {
    // Android MotionEvent values, kept free of android.* for the executable regression.
    public static final int DOWN = 0, UP = 1, CANCEL = 3;
    public interface Actions {
        void tap();
        void edit();
        void hold(boolean down);
    }
    private final int mode;
    private final Actions actions;
    private boolean held;

    public Arc7DeckKeyInput(int mode, Actions actions) {
        this.mode = mode;
        this.actions = actions;
    }

    public boolean touch(int action, boolean editing) {
        if (editing) { cancel(); return false; }
        if (mode != 2) return false;
        if (action == DOWN && !held) {
            held = true;
            actions.hold(true);
        } else if (action == UP || action == CANCEL) cancel();
        // Consume DOWN/MOVE/UP/CANCEL, so View never schedules a PTT long-click/editor.
        return true;
    }

    public void click(boolean editing) {
        if (editing) actions.edit();
        else if (mode != 2) actions.tap();
    }

    public boolean longClick(boolean editing) {
        if (!editing) return false;
        actions.edit();
        return true;
    }

    public void cancel() {
        if (!held) return;
        held = false;
        actions.hold(false);
    }
}
