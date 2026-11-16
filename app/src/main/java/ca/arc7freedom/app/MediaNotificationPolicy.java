package ca.arc7freedom.app;

/** A notification belongs to the APK actually playing media, never a metadata observer. */
final class MediaNotificationPolicy {
    private MediaNotificationPolicy() {}

    static boolean ownsSession(String ownPackage, String sourcePackage, boolean active) {
        return active && ownPackage != null && ownPackage.equals(sourcePackage);
    }

    static boolean mayPost(String ownPackage, String sourcePackage, boolean active,
                           boolean audioPrepared, boolean foregroundVideoPrepared) {
        return ownsSession(ownPackage, sourcePackage, active)
                && (audioPrepared || foregroundVideoPrepared);
    }
}
