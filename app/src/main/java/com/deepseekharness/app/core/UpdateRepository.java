package com.deepseekharness.app.core;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.UpdatePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 版本清单、可取消下载及 APK 核验；不持有 Activity，旋转不会重新下载。 */
public final class UpdateRepository extends AndroidViewModel {
    public static final String FEED = "https://dsha.cc/api/updates.json";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private String channel;
    private UpdatePolicy.Release candidate;
    private File verifiedApk;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State("尚未检查更新", false, 0, 0, null, null));
    public static final class State {
        public final String message;
        public final boolean busy;
        public final long downloaded, total;
        public final UpdatePolicy.Release release;
        public final File apk;
        State(String text, boolean busy, long n, long total, UpdatePolicy.Release release, File apk) {
            message=text; this.busy=busy; downloaded=n; this.total=total; this.release=release; this.apk=apk;
        }
    }
    public UpdateRepository(@NonNull Application app) {
        super(app);
        channel = app.getSharedPreferences("dsha-updates", 0).getString("channel", UpdatePolicy.defaultChannel(BuildConfig.VERSION_NAME));
        if (!UpdatePolicy.PREVIEW.equals(channel)) channel = UpdatePolicy.STABLE;
    }
    public LiveData<State> state() { return state; }
    public String channel() { return channel; }
    public void setChannel(String value) {
        if (busy.get() || value.equals(channel)) return;
        if (!UpdatePolicy.STABLE.equals(value) && !UpdatePolicy.PREVIEW.equals(value)) return;
        channel = value;
        getApplication().getSharedPreferences("dsha-updates", 0).edit().putString("channel", value).apply();
        check();
    }
    public void cancel() {
        cancelled = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }
    private interface Task { String run() throws Exception; }
    private void submit(String message, Task task) {
        if (!busy.compareAndSet(false, true)) return;
        cancelled = false;
        state.setValue(new State(message, true, 0, 0, candidate, verifiedApk));
        IO.execute(() -> {
            String result;
            try { result = task.run(); }
            catch (Exception error) {
                result = cancelled ? "已取消，可重新检查或下载" : "更新失败：" + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage());
            }
            connection = null;
            DiagnosticLog.record(getApplication(), "APP_UPDATE", result);
            final String done = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                busy.set(false);
                state.setValue(new State(done, false, 0, 0, candidate, verifiedApk));
            });
        });
    }
    public void check() {
        submit("正在检查" + (UpdatePolicy.PREVIEW.equals(channel) ? "预览版" : "稳定版") + "更新…", () -> {
            candidate = null; verifiedApk = null;
            HttpURLConnection conn = connect(FEED);
            byte[] raw;
            try (InputStream input = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    ensureActive();
                    if (bytes.size() + n > 1024 * 1024) throw new IOException("更新清单过大");
                    bytes.write(buffer, 0, n);
                }
                raw = bytes.toByteArray();
            } finally { conn.disconnect(); }
            JSONObject feed = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (feed.optInt("schemaVersion") != 1) throw new IOException("更新清单版本不兼容");
            JSONArray releases = feed.getJSONArray("releases");
            ArrayList<UpdatePolicy.Release> options = new ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                JSONArray artifacts = release.getJSONArray("artifacts");
                for (int j = 0; j < artifacts.length(); j++) {
                    JSONObject apk = artifacts.getJSONObject(j);
                    options.add(new UpdatePolicy.Release(release.getInt("versionCode"), release.getString("version"),
                            release.getString("channel"), apk.getString("flavor"), apk.getInt("minSdk"),
                            apk.getString("abi"), apk.getString("url"), apk.getString("sha256"), apk.getLong("bytes"),
                            release.optString("notes"), release.getString("pageUrl")));
                }
            }
            ensureActive();
            candidate = UpdatePolicy.select(options, BuildConfig.VERSION_CODE, BuildConfig.LOW_ANDROID ? "low" : "standard", Build.VERSION.SDK_INT, channel);
            return candidate != null ? "发现新版本 " + candidate.version : "此通道暂无适合当前设备的更新；当前版本码 " + BuildConfig.VERSION_CODE;
        });
    }
    public void download() {
        if (candidate == null) return;
        final UpdatePolicy.Release release = candidate;
        submit("正在下载 " + release.version + "…", () -> {
            verifiedApk = null;
            File directory = new File(getApplication().getCacheDir(), "updates");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
            File partial = new File(directory, "download.part");
            File apk = new File(directory, "dsha-update.apk");
            if (directory.getUsableSpace() < release.bytes + 32L * 1024 * 1024) throw new IOException("存储空间不足，请至少留出安装包大小加 32 MiB 空间");
            HttpURLConnection conn = connect(release.url);
            long n = 0, last = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try {
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        ensureActive(); n += count;
                        if (n > release.bytes) throw new IOException("下载大小与发布清单不符");
                        out.write(buffer, 0, count); digest.update(buffer, 0, count);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last > 200) {
                            state.postValue(new State("正在下载，可随时取消", true, n, release.bytes, release, null)); last = now;
                        }
                    }
                    out.getFD().sync();
                }
                ensureActive();
                if (n != release.bytes || !hex(digest.digest()).equalsIgnoreCase(release.sha256)) throw new IOException("SHA-256 校验失败，请重试下载");
                state.postValue(new State("下载完成，正在核验包名、版本与签名…", true, n, release.bytes, release, null));
                validatePackage(partial, release);
                android.system.Os.rename(partial.getAbsolutePath(), apk.getAbsolutePath());
                verifiedApk = apk;
                return "下载及校验完成，点击「安装更新」继续";
            } finally { conn.disconnect(); partial.delete(); }
        });
    }
    public File installableApk() throws Exception {
        if (candidate == null || verifiedApk == null || !verifiedApk.isFile()) throw new IOException("请先下载并校验安装包");
        return verifiedApk;
    }
    private void validatePackage(File apk, UpdatePolicy.Release release) throws Exception {
        PackageManager pm = getApplication().getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(getApplication().getPackageName(), flags);
        if (next == null || !installed.packageName.equals(next.packageName)) throw new IOException("安装包不是 DSHA");
        long code = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
        if (code != release.versionCode || code <= BuildConfig.VERSION_CODE) throw new IOException("安装包版本不匹配或不是更新版本");
        if (Build.VERSION.SDK_INT >= 24 && next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("安装包不支持当前 Android 版本");
        String expectedVersion = release.version + (BuildConfig.LOW_ANDROID ? "low" : "");
        if (!expectedVersion.equals(next.versionName)) throw new IOException("安装包不是当前高/低安卓版本");
        Signature[] oldSign = signatures(installed), newSign = signatures(next);
        if (oldSign.length == 0 || newSign.length != oldSign.length) throw new IOException("安装包签名不匹配");
        ArrayList<String> oldHashes = new ArrayList<>(), newHashes = new ArrayList<>();
        for (Signature sig : oldSign) oldHashes.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));
        for (Signature sig : newSign) newHashes.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));
        java.util.Collections.sort(oldHashes); java.util.Collections.sort(newHashes);
        if (!oldHashes.equals(newHashes)) throw new IOException("签名与已安装版本不同，已阻止安装");
    }
    private Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.signingInfo == null ? new Signature[0] : info.signingInfo.getApkContentsSigners();
        return info.signatures == null ? new Signature[0] : info.signatures;
    }
    private HttpURLConnection connect(String target) throws Exception {
        for (int i = 0; i < 6; i++) {
            ensureActive();
            if (!UpdatePolicy.https(target)) throw new IOException("更新地址必须使用 HTTPS");
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection(); connection = conn;
            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(TrustedNetwork.sockets(getApplication()));
            conn.setConnectTimeout(15000); conn.setReadTimeout(30000); conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "DSHA/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code >= 300 && code <= 399) {
                String location = conn.getHeaderField("Location"); conn.disconnect();
                if (location == null) throw new IOException("下载重定向缺少地址");
                target = new URL(new URL(target), location).toString(); continue;
            }
            if (code != 200) { conn.disconnect(); throw new IOException("HTTP " + code + "，请稍后重试或使用发布页下载"); }
            return conn;
        }
        throw new IOException("下载重定向次数过多");
    }
    private void ensureActive() throws IOException { if (cancelled) throw new IOException("已取消"); }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString();
    }
    @Override protected void onCleared() { cancel(); }
}
