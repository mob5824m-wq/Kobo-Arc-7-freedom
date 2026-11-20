package ca.arc7freedom.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DeckKeyInputHarness {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static final class Actions implements Arc7DeckKeyInput.Actions {
        final List<String> events = new ArrayList<String>();
        public void tap() { events.add("tap"); }
        public void edit() { events.add("edit"); }
        public void hold(boolean down) { events.add(down ? "down" : "up"); }
    }
    public static void main(String[] args) {
        for (int mode = 0; mode < 3; mode++) {
            Actions actions = new Actions();
            Arc7DeckKeyInput input = new Arc7DeckKeyInput(mode, actions);
            check(input.touch(Arc7DeckKeyInput.DOWN, false) == (mode == 2), "PTT must own DOWN, taps must use View");
            check(!input.longClick(false), "Normal-mode long press opened editor");
            input.touch(2, false); // MOVE during an arbitrarily long press.
            input.touch(Arc7DeckKeyInput.UP, false);
            input.click(false); // Android normally suppresses this for PTT; guard it too.
            check(actions.events.equals(mode == 2 ? Arrays.asList("down", "up") : Arrays.asList("tap")), "Normal long hold changed mode " + mode + ": " + actions.events);
            actions.events.clear();
            check(!input.touch(Arc7DeckKeyInput.DOWN, true), "Edit mode sent a momentary packet");
            input.touch(Arc7DeckKeyInput.UP, true);
            input.click(true);
            check(input.longClick(true), "Edit-mode long press was not consumed");
            check(actions.events.equals(Arrays.asList("edit", "edit")), "Editing dispatched commands: " + actions.events);
        }
        Actions actions = new Actions();
        Arc7DeckKeyInput hold = new Arc7DeckKeyInput(2, actions);
        hold.touch(0, false); hold.touch(0, false); hold.touch(3, false); hold.touch(1, false); hold.cancel();
        check(actions.events.equals(Arrays.asList("down", "up")), "Duplicate DOWN/UP or CANCEL leaked a held key");
        actions.events.clear();
        hold.touch(0, false); hold.cancel(); hold.cancel();
        check(actions.events.equals(Arrays.asList("down", "up")), "Rerender/stop did not release PTT exactly once");
        actions.events.clear();
        hold.touch(0, false); hold.touch(2, true); hold.touch(1, true);
        check(actions.events.equals(Arrays.asList("down", "up")), "Entering edit mode while held left the PC key down");
        System.out.println("PASS Stream Deck production gesture policy: edit-only long press, normal tap/toggle release, PTT complete touch ownership, cancel/rerender/stop release and no duplicate packets");
    }
}
