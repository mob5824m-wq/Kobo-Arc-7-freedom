package ca.arc7freedom.app;

/** Independent News APK; never opens a tab in Radio. */
public final class Arc7NewsActivity extends Arc7RadioRssActivity {
    @Override protected int contentSection() { return 2; }
}
