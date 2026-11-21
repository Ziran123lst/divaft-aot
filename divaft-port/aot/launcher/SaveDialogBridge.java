package com.divaft.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;

/**
 * Hand-written save-data GUI (no system AlertDialog). Mirrors shadPS4's SaveDataDialog
 * slot picker: the native runtime posts a `request` in {@link #bridgeDir} and blocks in
 * GetResult; this class shows a custom on-screen manager, writes `response`, and the
 * native side fills OrbisSaveDataDialogResult.
 *
 * Wire format (plain text):
 *   request  : "MODE DISPTYPE SEQ SLOT;SLOT;...\n"   MODE 1=LIST, DISP 1=SAVE 2=LOAD 3=DELETE
 *   response : "<SEQ> SLOTNAME" | "<SEQ> NEW" | "<SEQ> CANCEL"
 *
 * The request file is DELETED as soon as it is consumed, and responses carry the SEQ
 * of the request they answer. This prevents an already-handled dialog from popping again.
 */
final class SaveDialogBridge {
    private static final String TAG = "DivaSaveDlg";
    static File bridgeDir;
    private static volatile boolean running = false;
    private static Thread worker;
    private static Activity activity;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Dialog dialog;
    private static volatile long lastSeq = -1;   // consumed request sequence

    static File initDir(Activity ctx) {
        bridgeDir = new File(ctx.getFilesDir(), "savedlg");
        if (!bridgeDir.isDirectory() && !bridgeDir.mkdirs()) Log.w(TAG, "cannot create " + bridgeDir);
        File req = new File(bridgeDir, "request");
        File resp = new File(bridgeDir, "response");
        if (req.exists()) req.delete();
        if (resp.exists()) resp.delete();
        lastSeq = -1;
        return bridgeDir;
    }

    static void start(Activity ctx) {
        activity = ctx;
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        File req = new File(bridgeDir, "request");
                        if (req.exists() && req.length() > 0) {
                            String raw = readAll(req);
                            // Consume immediately: delete BEFORE showing so it can never re-trigger.
                            req.delete();
                            if (raw != null && raw.trim().length() > 0) {
                                Log.i(TAG, "request: " + raw.trim());
                                final String line = raw.trim();
                                ui.post(new Runnable() { @Override public void run() { showManager(line); } });
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "poll: " + t);
                    }
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }
        }, "save-dlg-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    static void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        dismiss();
    }

    private static String readAll(File f) {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) { Log.w(TAG, "readAll: " + e); return null; }
        finally { if (r != null) try { r.close(); } catch (Exception ignore) {} }
    }

    private static void writeResponse(long seq, String value) {
        if (bridgeDir == null) return;
        File resp = new File(bridgeDir, "response");
        try {
            FileWriter w = new FileWriter(resp, false);
            w.write(seq + " " + value);
            w.flush();
            w.close();
            Log.i(TAG, "response=" + seq + " " + value);
        } catch (Exception e) {
            Log.w(TAG, "writeResponse: " + e);
            Toast.makeText(activity, "存档响应写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(int v) {
        return Math.round(v * (activity != null ? activity.getResources().getDisplayMetrics().density : 1f));
    }

    /** Fully hand-drawn save manager, built from views rather than a system dialog. */
    private static void showManager(String request) {
        if (activity == null || activity.isFinishing()) return;
        dismiss();
        String[] p = request.split("\\s+");
        long seq = -1;
        int mode = 1, disp = 1;
        try { if (p.length > 0) seq = Long.parseLong(p[0]); } catch (Exception ignore) {}
        try { if (p.length > 1) mode = Integer.parseInt(p[1]); } catch (Exception ignore) {}
        try { if (p.length > 2) disp = Integer.parseInt(p[2]); } catch (Exception ignore) {}
        String slots = p.length > 3 ? p[3] : "";
        if (seq < 0 || seq == lastSeq) { Log.w(TAG, "stale/duplicate request seq=" + seq); return; }
        lastSeq = seq;

        final long fseq = seq;
        final ArrayList<String> existing = new ArrayList<String>();
        if (slots.length() > 0) for (String s : slots.split(";")) if (s.length() > 0) existing.add(s);
        String action = disp == 2 ? "读取" : (disp == 3 ? "删除" : "保存");
        String title = "存档管理 · " + action;

        // Root panel, vertically stacked.
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101820);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));

        TextView head = new TextView(activity);
        head.setText(title);
        head.setTextColor(Color.WHITE);
        head.setTextSize(20);
        head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, 0, 0, dp(16));
        root.addView(head);

        TextView hint = new TextView(activity);
        hint.setText("选择一个存档槽位继续");
        hint.setTextColor(0xFF9FB3C8);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(14));
        root.addView(hint);

        // Scrollable slot list — each row is a hand-built button.
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        addSlotButton(list, "新建存档", seq, "NEW", true);
        for (String s : existing) addSlotButton(list, "存档 " + s, seq, s, false);
        if (existing.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("（当前没有存档）");
            empty.setTextColor(0xFF6B7C8C);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            list.addView(empty);
        }
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button cancel = new Button(activity);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setBackgroundColor(0xFF7A2E2E);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                writeResponse(fseq, "CANCEL");
                Toast.makeText(activity, "已取消", Toast.LENGTH_SHORT).show();
                dismiss();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        root.addView(cancel, bp);

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setCancelable(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) { dialog = null; }
        });
        try { dialog.show(); }
        catch (Throwable t) { Log.w(TAG, "show: " + t); }
    }

    private static void addSlotButton(LinearLayout list, String label, final long seq,
                                      final String value, boolean isNew) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setBackgroundColor(isNew ? 0xFF2E6B4F : 0xFF2E4A6B);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                writeResponse(seq, value);
                Toast.makeText(activity, "已选择: " + labelOf(value), Toast.LENGTH_SHORT).show();
                dismiss();
            }
        });
        list.addView(b);
    }

    private static String labelOf(String v) { return "NEW".equals(v) ? "新建存档" : v; }

    private static void dismiss() {
        ui.post(new Runnable() {
            @Override public void run() {
                try { if (dialog != null && dialog.isShowing()) dialog.dismiss(); }
                catch (Exception ignore) {}
                dialog = null;
            }
        });
    }
}
