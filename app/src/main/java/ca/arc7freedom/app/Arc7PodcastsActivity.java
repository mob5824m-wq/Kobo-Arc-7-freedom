package ca.arc7freedom.app;

/** Independent Podcasts APK; never opens a tab in Radio. */
public final class Arc7PodcastsActivity extends Arc7RadioRssActivity {
    @Override protected int contentSection() { return 1; }
}
