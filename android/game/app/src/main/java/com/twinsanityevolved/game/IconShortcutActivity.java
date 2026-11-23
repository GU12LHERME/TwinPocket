package com.twinsanityevolved.game;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The custom home-screen icon's target (LauncherActivity's Ícone personalizado). Android starts a pinned shortcut with
 * FLAG_ACTIVITY_CLEAR_TASK -- straight onto the launcher that would end a game in progress. This invisible activity, in a
 * task of its own, opens the app the way its own icon does (the launch intent: the app's task brought to the front as it
 * is -- the game if one is running, else the launcher) and finishes at once.
 */
public final class IconShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            open.setPackage(null);
            startActivity(open);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
