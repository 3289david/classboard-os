package kr.classboard.os;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Optional accessibility service used only to toggle Android's split-screen mode. */
public class BoardAccessibility extends AccessibilityService {
    private static volatile BoardAccessibility instance;

    public static BoardAccessibility get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    public void toggleSplit() {
        performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    /** System navigation for the on-screen Back / Recents buttons. */
    public boolean global(String action) {
        switch (action) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "quick": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            default: return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
