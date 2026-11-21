package com.divaft.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Keep the output surface attached underneath a separate, multi-touch overlay. */
public class DisplayActivity extends Activity {
    private SurfaceView screen;
    private VirtualPad pad;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout layout = new FrameLayout(this);
        screen = new SurfaceView(this);
        screen.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { BbPresent.surface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {
                BbPresent.surface(h.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { BbPresent.surface(null); }
        });
        layout.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        pad = new VirtualPad(this, MainActivity.padFile(this));
        layout.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layout);
        immersive();
        SaveDialogBridge.start(this);
    }

    @Override protected void onDestroy() {
        SaveDialogBridge.stop();
        super.onDestroy();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
        else if (pad != null) pad.releaseAll();
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen.getHolder().getSurface().isValid()) BbPresent.surface(screen.getHolder().getSurface());
        BbPresent.setHold(false);
    }

    @Override protected void onPause() {
        if (pad != null) pad.releaseAll();
        BbPresent.setHold(true);
        BbPresent.surface(null);
        super.onPause();
    }
}
