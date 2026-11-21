package com.divaft.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.SurfaceView;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {
    private TextView log;
    private TextView status;
    private ScrollView scroll;
    private SurfaceView surfaceView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String gamePath = null;
    private File dataDir;
    private static final int REQ_GAME = 41;
    private static final int REQ_DATA = 42;
    private volatile Process proc;
    private boolean starting;
    private CheckBox useTurnip;

    static File padFile(Context context) {
        return new File(context.getFilesDir(), "pad.state");
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dataDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "diva-data");

        String savedGame = getPreferences(MODE_PRIVATE).getString("game", null);
        String savedData = getPreferences(MODE_PRIVATE).getString("data", null);
        if (savedGame != null) gamePath = savedGame;
        if (savedData != null) dataDir = new File(savedData);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        Button perm = new Button(this);
        perm.setText("1 授权存储");
        perm.setOnClickListener(v -> requestStorage());
        Button pickData = new Button(this);
        pickData.setText("2 设置数据目录(默认Download/diva-data)");
        pickData.setOnClickListener(v -> pick(REQ_DATA));
        Button pick = new Button(this);
        pick.setText("3 选择游戏文件夹");
        pick.setOnClickListener(v -> pick(REQ_GAME));
        useTurnip = new CheckBox(this);
        useTurnip.setText("Turnip Vulkan 驱动（高通 Adreno 6/7/8；非 Adreno 请取消）");
        useTurnip.setChecked(getPreferences(MODE_PRIVATE).getBoolean("turnip", true));
        useTurnip.setOnCheckedChangeListener((button, checked) ->
            getPreferences(MODE_PRIVATE).edit().putBoolean("turnip", checked).apply());
        Button start = new Button(this);
        start.setText("4 开始游戏");
        start.setOnClickListener(v -> startGame());
        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> { if (proc != null) proc.destroy(); });

        surfaceView = new SurfaceView(this);
        status = new TextView(this);
        status.setTextSize(12);
        status.setText("画面帧数: 0");
        log = new TextView(this);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(perm); root.addView(pickData); root.addView(pick);
        root.addView(useTurnip);
        root.addView(start); root.addView(stop);
        root.addView(surfaceView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));
        root.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        append("DIVA Launcher · 触屏手柄版\n数据目录: " + dataDir.getPath()
            + "\n游戏目录: " + (gamePath == null ? "未选择" : gamePath)
            + "\n顺序: 1授权 -> 3选游戏目录 -> 4开始。\n日志与运行数据都在数据目录里可见。\n");
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
        }
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
        append("已请求存储权限。\n");
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    private String uriToPath(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        String doc = DocumentsContract.getTreeDocumentId(uri);
        if (!doc.contains(":")) return null;
        String base = Environment.getExternalStorageDirectory().getPath();
        String rel = doc.contains(":") ? doc.substring(doc.indexOf(':') + 1) : "";
        String vol = doc.contains(":") ? doc.substring(0, doc.indexOf(':')) : "primary";
        if (!vol.equals("primary")) base = "/storage/" + vol;
        return base + "/" + rel;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        String path = uriToPath(uri);
        if (path == null) { append("请选择本机存储里的文件夹，网盘目录不支持直接启动。\n"); return; }
        if (req == REQ_GAME) {
            gamePath = path;
            getPreferences(MODE_PRIVATE).edit().putString("game", path).apply();
            append("游戏目录: " + path + "\n");
        } else if (req == REQ_DATA) {
            dataDir = new File(path);
            getPreferences(MODE_PRIVATE).edit().putString("data", path).apply();
            append("数据目录: " + path + "\n");
        }
    }

    private void append(final String s) {
        runOnUiThread(() -> {
            // The full log stays on disk; avoid growing the UI on long runs.
            if (log.length() > 32000) log.setText(log.getText().subSequence(log.length() - 16000, log.length()).toString());
            log.append(s);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private File extractAsset(String assetName, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Cannot create " + parent);
        File temp = File.createTempFile("payload-", ".tmp", parent);
        try {
            try (InputStream in = getAssets().open(assetName);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[1 << 20];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            android.system.Os.rename(temp.getPath(), destination.getPath());
            return destination;
        } catch (Exception e) {
            temp.delete();
            throw new Exception("释放内置文件失败: " + assetName, e);
        }
    }

    private File requirePayload(String name, File explicitDir, File gameFile, String assetName) throws Exception {
        File selected = new File(explicitDir, name);
        if (selected.isFile() && selected.canRead() && selected.length() > 0) return selected;
        if (gameFile.isFile() && gameFile.canRead() && gameFile.length() > 0) return gameFile;
        return extractAsset(assetName, new File(getFilesDir(), "payload/" + name));
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 20]; int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.getFD().sync();
        }
    }

    private void startGame() {
        if (starting || (proc != null && proc.isAlive())) {
            append("游戏已经在运行。\n");
            if (proc != null && proc.isAlive()) startActivity(new Intent(this, DisplayActivity.class));
            return;
        }
        if (gamePath == null) { append("请先选择游戏文件夹。\n"); return; }
        final File game = new File(gamePath);
        final File work = dataDir;
        final boolean turnipEnabled = useTurnip.isChecked();
        starting = true;
        new Thread(() -> {
            Process running = null;
            try {
                if (!new File(game, "eboot.bin").canRead()) {
                    throw new Exception("找不到或不能读取 eboot.bin。请选择 CUSA08053 根目录并确认存储权限。");
                }
                if ((!work.isDirectory() && !work.mkdirs()) || !work.canWrite()) {
                    throw new Exception("数据目录不可写：" + work + "。请授权存储或选择其他目录。");
                }
                long freeBytes = new android.os.StatFs(getFilesDir().getPath()).getAvailableBytes();
                long requiredBytes = 400L * 1024L * 1024L;
                if (freeBytes < requiredBytes) {
                    throw new Exception("应用私有存储至少需要 400 MiB 空闲空间用于启动镜像、AOT 与 Turnip。");
                }
                String nld = getApplicationInfo().nativeLibraryDir;
                File exe = new File(nld, "libbbprobe.so");
                if (!exe.canExecute()) throw new Exception("APK 未包含可执行 Android ARM64 运行时。");
                File buildData = new File(game, ".bbwork/data");
                File boot = requirePayload("boot-linked.bin", work, new File(buildData, "boot-linked.bin"),
                        "payload/boot-linked.bin");
                File content = requirePayload("content.bin", work, new File(buildData, "content.bin"),
                        "payload/content.bin");
                File aotSrc = requirePayload("libbbaot.so", work, new File(new File(game, "aot"), "libbbaot.so"),
                        "payload/libbbaot.so");
                // Stage a fresh copy: two different builds can have the same file size.
                File privateRoot = getFilesDir().getCanonicalFile();
                File aot = aotSrc.getCanonicalPath().startsWith(privateRoot.getPath() + File.separator)
                        ? aotSrc : new File(privateRoot, "libbbaot.so");
                if (aot != aotSrc) {
                    File staged = File.createTempFile("aot-", ".tmp", privateRoot);
                    try {
                        copyFile(aotSrc, staged);
                        android.system.Os.rename(staged.getPath(), aot.getPath());
                    } finally { if (staged.exists()) staged.delete(); }
                }

                File logFile = new File(work, "diva-boot.log");
                String sockPath = new File(getFilesDir(), "bbpresent.sock").getPath();
                String present = BbPresent.listen(sockPath, new File(work, "bbpresent-diag.log").getPath());
                if (!"present running".equals(present)) throw new Exception(present);
                VirtualPad.writeSnapshot(padFile(this), "\n");
                append("游戏目录: " + game + "\n启动 Android ARM64 运行时…\n");
                ProcessBuilder pb = new ProcessBuilder(
                    exe.getPath(), boot.getPath(), "--app0", game.getPath(),
                    "--content-profile", content.getPath(), "--timeout", "0");
                String home = getFilesDir().getPath();
                pb.environment().put("LD_LIBRARY_PATH", nld);
                pb.environment().put("HOME", home);
                pb.environment().put("XDG_DATA_HOME", home);
                pb.environment().put("XDG_CONFIG_HOME", home);
                pb.environment().put("XDG_CACHE_HOME", home);
                pb.environment().put("TMPDIR", home);
                pb.environment().put("BB_AOT", aot.getPath());
                if (turnipEnabled) {
                    File turnip = extractAsset("driver/libvulkan_freedreno.so",
                            new File(getFilesDir(), "vulkan/libvulkan_freedreno.so"));
                    pb.environment().put("BB_VULKAN_DRIVER", turnip.getPath());
                    append("Vulkan 驱动: Turnip 26.3.1（Adreno）\\n");
                } else {
                    pb.environment().remove("BB_VULKAN_DRIVER");
                    append("Vulkan 驱动: Android 系统驱动\\n");
                }
                pb.environment().put("BB_PRESENT_SOCKET", sockPath);
                File audioFifo = new File(getFilesDir(), "audio.fifo");
                append("音频: " + AudioBridge.start(audioFifo) + "\n");
                pb.environment().put("BB_AUDIO_FIFO", audioFifo.getPath());
                pb.environment().put("BB_PAD_FILE", padFile(this).getPath());
                pb.environment().put("BB_PAD_QUIET", "1");
                pb.environment().put("BB_ANDROID_INPUT", "1");
                // SaveDataDialog GUI bridge: native posts a request here, DisplayActivity
                // shows a native slot picker and writes the selection back.
                File saveDlgDir = SaveDialogBridge.initDir(this);
                pb.environment().put("BB_SAVEDLG_DIR", saveDlgDir.getPath());
                pb.environment().put("BB_SAVEDLG_TIMEOUT", "600");
                pb.environment().put("BB_IMPORT_STUBS", "1");
                pb.environment().put("BB_DRAW_PIPE", "0");
                pb.environment().put("BB_HONEST_LABELS", "1");
                pb.environment().put("BB_UPSCALER", "none");
                pb.redirectErrorStream(true);
                pb.directory(work);
                proc = running = pb.start();
                final Process session = running;
                runOnUiThread(() -> startActivity(new Intent(this, DisplayActivity.class)));
                handler.post(new Runnable() {
                    @Override public void run() {
                        status.setText("画面帧数: " + BbPresent.framesIfLoaded()
                            + (session.isAlive() ? " (运行中)" : " (已退出)"));
                        if (session.isAlive()) handler.postDelayed(this, 2000);
                    }
                });
                try (FileOutputStream fos = new FileOutputStream(logFile);
                     BufferedReader br = new BufferedReader(new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        fos.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        append(line + "\n");
                    }
                }
                append("进程退出 code=" + running.waitFor() + "\n日志: " + logFile.getPath() + "\n");
            } catch (Exception | LinkageError e) {
                append("错误: " + e + "\n");
            } finally {
                if (running != null && running.isAlive()) running.destroy();
                AudioBridge.stop();
                BbPresent.setHold(true);
                BbPresent.surface(null);
                proc = null;
                runOnUiThread(() -> starting = false);
            }
        }, "diva-runtime").start();
    }
}
