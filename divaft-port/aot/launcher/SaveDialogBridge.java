package com.divaft.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * File bridge for the game's PS4 SaveDataDialog, mirroring shadPS4's interactive
 * slot picker. The native runtime (runtime_services.c) posts a `request` file in
 * {@link #bridgeDir} when the game opens the save dialog and blocks in GetResult
 * waiting for a `response`. This class polls for the request, shows a native
 * Android picker with the existing slots, and writes the chosen slot back.
 *
 * Wire format (plain text, same as the C side):
 *   request  : "MODE DISPTYPE SLOT;SLOT;...\n"   MODE 1=LIST, DISP 1=SAVE 2=LOAD 3=DELETE
 *   response : "SAVEDATA00" | "NEW" | "CANCEL"
 */
final class SaveDialogBridge {
    private static final String TAG = "DivaSaveDlg";
    static File bridgeDir;            // set by MainActivity before starting the game process
    private static volatile boolean running = false;
    private static volatile boolean shown = false;
    private static Thread worker;
    private static Activity activity;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** Called by MainActivity: create the directory and hand its path to native via the caller. */
    static File initDir(Activity ctx) {
        bridgeDir = new File(ctx.getFilesDir(), "savedlg");
        if (!bridgeDir.isDirectory() && !bridgeDir.mkdirs()) {
            Log.w(TAG, "cannot create " + bridgeDir);
        }
        File req = new File(bridgeDir, "request");
        File resp = new File(bridgeDir, "response");
        if (req.exists()) req.delete();
        if (resp.exists()) resp.delete();
        return bridgeDir;
    }

    static void start(Activity ctx) {
        activity = ctx;
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                long lastLen = -1;
                while (running) {
                    try {
                        File req = new File(bridgeDir, "request");
                        if (req.exists() && req.length() > 0) {
                            long len = req.length();
                            // Only react to a fresh/changed request, not the same one each poll.
                            if (len != lastLen || !shown) {
                                lastLen = len;
                                String raw = readAll(req);
                                if (raw != null && raw.trim().length() > 0) {
                                    shown = true;
                                    final String line = raw.trim();
                                    ui.post(new Runnable() { @Override public void run() { showPicker(line); } });
                                }
                            }
                        } else {
                            // request removed or empty: allow a future one to show.
                            lastLen = -1;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "poll: " + t);
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                }
            }
        }, "save-dlg-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    static void stop() {
        running = false;
        shown = false;
        if (worker != null) worker.interrupt();
        dismiss();
    }

    private static String readAll(File f) {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) { sb.append(line).append('\n'); }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "readAll: " + e);
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
    }

    private static void writeResponse(String value) {
        File dir = bridgeDir;
        if (dir == null) return;
        File resp = new File(dir, "response");
        try {
            FileWriter w = new FileWriter(resp, false);
            w.write(value);
            w.flush();
            w.close();
            Log.i(TAG, "response=" + value);
        } catch (Exception e) {
            Log.w(TAG, "writeResponse: " + e);
            Toast.makeText(activity, "存档响应写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        shown = false;
    }

    private static void showPicker(String request) {
        if (activity == null || activity.isFinishing()) { shown = false; return; }
        String[] parts = request.split("\\s+");
        int mode = 1, disp = 1;
        try { if (parts.length > 0) mode = Integer.parseInt(parts[0]); } catch (Exception ignore) {}
        try { if (parts.length > 1) disp = Integer.parseInt(parts[1]); } catch (Exception ignore) {}
        String slots = parts.length > 2 ? parts[2] : "";

        ArrayList<String> existing = new ArrayList<String>();
        if (slots.length() > 0) {
            for (String s : slots.split(";")) if (s.length() > 0) existing.add(s);
        }
        final ArrayList<String> options = new ArrayList<String>();
        options.add("新建存档");
        for (String s : existing) options.add(s);

        String action = disp == 2 ? "读取" : (disp == 3 ? "删除" : "保存");
        String title = "存档管理 - " + action + (mode == 5 ? " (进度)" : "");
        final String[] items = options.toArray(new String[0]);

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(title);
        b.setCancelable(false);
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                String picked = items[which];
                if ("新建存档".equals(picked)) writeResponse("NEW");
                else writeResponse(picked);
                Toast.makeText(activity, "已选择: " + picked, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                writeResponse("CANCEL");
            }
        });
        try {
            current = b.create();
            current.show();
        } catch (Throwable t) {
            Log.w(TAG, "show picker: " + t);
            shown = false;
        }
    }

    private static AlertDialog current;
    private static void dismiss() {
        ui.post(new Runnable() {
            @Override public void run() {
                try { if (current != null && current.isShowing()) current.dismiss(); }
                catch (Exception ignore) {}
                current = null;
            }
        });
    }
}
