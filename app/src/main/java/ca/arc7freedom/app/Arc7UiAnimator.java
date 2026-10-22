package ca.arc7freedom.app;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;

/**
 * Hardware-accelerated 60 fps UI animation engine for all custom Arc7 apps on Android 4.2+:
 *   - Smooth screen entrance glide (`alpha + translationY`)
 *   - Staggered card entrance cascade across scrollable dashboards & app managers
 *   - Tactile spring physics (`scaleX/scaleY` down -> `OvershootInterpolator` release) on buttons
 *   - Smooth `LayoutTransition` container updates & animated tab/page switches
 */
public final class Arc7UiAnimator {

    private Arc7UiAnimator() {}

    public static void attachActivityAnimations(final Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        final View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.post(new Runnable() {
            @Override
            public void run() {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) {
                    float dy = 14f * activity.getResources().getDisplayMetrics().density;
                    content.setAlpha(0f);
                    content.setTranslationY(dy);
                    content.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(230L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    enhanceHierarchyRecursive(content, 0);
                }
            }
        });
    }

    public static void enhanceHierarchyRecursive(View view, int depth) {
        if (view == null || depth > 9) return;
        if (view instanceof Button) {
            attachSpringPress(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            if (depth <= 3 && vg.getLayoutTransition() == null && vg.getChildCount() <= 24) {
                try {
                    LayoutTransition lt = new LayoutTransition();
                    lt.setDuration(170L);
                    vg.setLayoutTransition(lt);
                } catch (Throwable ignored) {}
            }
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = vg.getChildAt(i);
                enhanceHierarchyRecursive(child, depth + 1);
            }
        }
    }

    public static void attachSpringPress(final View button) {
        if (button == null) return;
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(75L).start();
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L)
                            .setInterpolator(new OvershootInterpolator(1.65f)).start();
                }
                return false;
            }
        });
    }

    public static void animatePageSwitch(View incoming) {
        if (incoming == null) return;
        float dy = 12f * incoming.getResources().getDisplayMetrics().density;
        incoming.setAlpha(0f);
        incoming.setTranslationY(dy);
        incoming.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(195L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void animatePageSwitch(View target, int direction) {
        if (target == null) return;
        float dx = 18f * (direction >= 0 ? 1f : -1f) * target.getResources().getDisplayMetrics().density;
        target.setAlpha(0.18f);
        target.setTranslationX(dx);
        target.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(195L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void animateCardEntrance(View card, int index) {
        if (card == null) return;
        float dy = 14f * card.getResources().getDisplayMetrics().density;
        card.setAlpha(0f);
        card.setTranslationY(dy);
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(index, 8) * 26L)
                .setDuration(210L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void startAnimated(Activity caller, Intent intent) {
        caller.startActivity(intent);
        caller.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    public static void finishAnimated(Activity caller) {
        caller.finish();
        caller.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
