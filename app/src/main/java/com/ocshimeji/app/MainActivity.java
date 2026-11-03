package com.ocshimeji.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.View;
import android.view.PixelCopy;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A local, persistent WebView host for the original OC Shimeji application. */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/assets/index.html";
    private static final int PICK_FILE = 100;
    private static final int SAVE_FILE = 101;
    private static final int LOCATION_PERMISSION = 102;
    private static final int MAX_EXPORT_BYTES = 32 * 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean exporting = new AtomicBoolean();
    private final AtomicBoolean capturing = new AtomicBoolean();
    private WebView webView;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback locationCallback;
    private String locationOrigin;
    private File pendingExport;
    private long lastBack;
    private boolean pageReady;
    private boolean backPending;
    private boolean resumed;

    @SuppressLint("SetJavaScriptEnabled") // Required by the bundled application; remote pages are blocked.
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(244, 242, 236));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(244, 242, 236));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Keep all web content inside system bars, cutouts and the software keyboard.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Read only documents explicitly selected by the user.
        settings.setGeolocationEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocal(uri)) {
                    WebResourceResponse local = loader.shouldInterceptRequest(uri);
                    return local == null ? blockedResponse() : local;
                }
                // The sole network dependency in the original HTML is optional live weather.
                if ("https".equals(uri.getScheme()) && "api.open-meteo.com".equals(uri.getHost())) {
                    return null;
                }
                return blockedResponse();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Always keep the JS bridge on the one bundled document.
                if (isLocal(uri) && "/assets/index.html".equals(uri.getPath())) return false;
                if (request.isForMainFrame() && request.hasGesture()
                        && "https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException e) { message("沒有可開啟連結的瀏覽器"); }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = HOME.equals(url);
                if (pageReady) {
                    js("window.OCAndroid && OCAndroid.resume(" + resumed + ")");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Set<String> types = new LinkedHashSet<>();
                for (String accept : params.getAcceptTypes()) {
                    for (String item : accept.split(",")) {
                        item = item.trim();
                        if (item.endsWith("json")) item = "application/json";
                        if (item.contains("/")) types.add(item);
                    }
                }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(types.size() == 1 ? types.iterator().next() : "*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                if (types.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                try { startActivityForResult(intent, PICK_FILE); }
                catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    message("找不到系統檔案選擇器");
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!isLocal(Uri.parse(origin))) { callback.invoke(origin, false, false); return; }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    clearLocationRequest();
                    locationOrigin = origin;
                    locationCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
                }
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() { clearLocationRequest(); }
        });
        webView.addJavascriptInterface(new NativeBridge(), "OCNative");
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::handleBack);
        }
        if (state != null) {
            String filename = state.getString("pendingExport");
            if (filename != null && filename.matches("[a-f0-9-]+")) {
                File file = new File(new File(getCacheDir(), "pending_exports"), filename);
                if (file.isFile()) { pendingExport = file; exporting.set(true); }
            }
        }
        io.execute(this::cleanOldExports);
        // Reload the view after process death; localStorage and IndexedDB remain on disk.
        webView.loadUrl(HOME);
    }

    private static boolean isLocal(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private void js(String code) {
        if (webView != null && !isDestroyed()) webView.evaluateJavascript(code, null);
    }

    private void message(String text) {
        runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); });
    }

    @Override
    public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (!pageReady) { finish(); return; }
        if (backPending) return;
        backPending = true;
        webView.evaluateJavascript("(function(){return window.OCAndroid ? OCAndroid.back() : !!(window.OCApp && OCApp.back())})()", result -> {
            backPending = false;
            if ("true".equals(result)) { lastBack = 0; return; }
            long now = SystemClock.elapsedRealtime();
            if (lastBack != 0 && now - lastBack < 2200) { finish(); return; }
            lastBack = now;
            message("再返回一次即可離開");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (webView != null) {
            webView.onResume();
            if (pageReady) js("window.OCAndroid && OCAndroid.resume(true)");
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (pageReady) js("window.OCAndroid && OCAndroid.resume(false)");
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (pendingExport != null) state.putString("pendingExport", pendingExport.getName());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        clearLocationRequest();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface("OCNative");
            webView.destroy();
            webView = null;
        }
        io.shutdown();
        super.onDestroy();
    }

    private void clearLocationRequest() {
        if (locationCallback != null) {
            locationCallback.invoke(locationOrigin, false, false);
            locationCallback = null;
            locationOrigin = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_PERMISSION && locationCallback != null) {
            locationCallback.invoke(locationOrigin, results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED, false);
            locationCallback = null;
            locationOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && fileCallback != null) {
            ArrayList<Uri> selected = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) addDocument(selected, clip.getItemAt(i).getUri());
                } else addDocument(selected, data.getData());
            }
            fileCallback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
            fileCallback = null;
        }
        if (requestCode == SAVE_FILE) {
            File export = pendingExport;
            pendingExport = null;
            if (export == null) { exporting.set(false); return; }
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                deleteExport(export);
                exporting.set(false);
                message("已取消儲存");
                return;
            }
            Uri uri = data.getData();
            io.execute(() -> {
                try (FileInputStream input = new FileInputStream(export);
                     OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("No output stream");
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                    message("檔案已儲存");
                } catch (IOException | SecurityException e) {
                    message("儲存失敗，請確認儲存空間後重試");
                } finally { deleteExport(export); exporting.set(false); }
            });
        }
    }

    private static void addDocument(ArrayList<Uri> selected, Uri uri) {
        if (uri != null && "content".equals(uri.getScheme()) && !selected.contains(uri)) selected.add(uri);
    }

    private static void deleteExport(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private void cleanOldExports() {
        for (String directory : new String[]{"shared_exports", "pending_exports"}) {
            File[] files = new File(getCacheDir(), directory).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (System.currentTimeMillis() - f.lastModified() > 86400000L
                        && (pendingExport == null || !f.equals(pendingExport))) {
                    File[] children = f.isDirectory() ? f.listFiles() : null;
                    if (children != null) for (File child : children) deleteExport(child);
                    deleteExport(f);
                }
            }
        }
    }

    private static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) return "oc-shimeji-export.json";
        if (value.length() <= 120) return value;
        int dot = value.lastIndexOf('.');
        String extension = dot > 0 && value.length() - dot <= 20 ? value.substring(dot) : "";
        return value.substring(0, 100) + extension;
    }

    private static String safeMime(String mime) {
        String type = mime == null ? "" : mime.split(";", 2)[0].trim();
        return type.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? type : "application/octet-stream";
    }

    private void completeShare(String id, boolean success, String error) {
        runOnUiThread(() -> js("window.OCAndroid && OCAndroid.finishRequest("
                + JSONObject.quote(id) + "," + success + "," + JSONObject.quote(error) + ")"));
    }

    private boolean queueExport(String name, String mime, String content, boolean base64, String shareId) {
        int limit = base64 ? (MAX_EXPORT_BYTES / 3) * 4 + 4 : MAX_EXPORT_BYTES;
        if (content == null || content.length() > limit) {
            message("匯出檔案超過 32 MB，請分批匯出");
            return false;
        }
        if (!exporting.compareAndSet(false, true)) { message("請先完成目前的儲存或分享"); return false; }
        String filename = safeName(name);
        String contentType = safeMime(mime);
        io.execute(() -> {
            File file = null;
            try {
                byte[] bytes = base64 ? Base64.decode(content, Base64.DEFAULT) : content.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_EXPORT_BYTES) throw new IOException("Export too large");
                File directory = new File(getCacheDir(), shareId == null ? "pending_exports" : "shared_exports");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create cache");
                if (shareId != null) {
                    directory = new File(directory, UUID.randomUUID().toString());
                    if (!directory.mkdirs()) throw new IOException("Cannot create cache");
                }
                file = new File(directory, shareId == null ? UUID.randomUUID().toString() : filename);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
                File ready = file;
                runOnUiThread(() -> {
                    if (isDestroyed()) { deleteExport(ready); exporting.set(false); return; }
                    try {
                        if (shareId == null) {
                            pendingExport = ready;
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE).setType(contentType)
                                    .putExtra(Intent.EXTRA_TITLE, filename);
                            startActivityForResult(intent, SAVE_FILE);
                        } else {
                            Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", ready);
                            Intent intent = new Intent(Intent.ACTION_SEND).setType(contentType)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.setClipData(ClipData.newRawUri(filename, uri));
                            startActivity(Intent.createChooser(intent, getString(R.string.share_title)));
                            exporting.set(false);
                            completeShare(shareId, true, "");
                        }
                    } catch (ActivityNotFoundException | IllegalArgumentException e) {
                        pendingExport = null;
                        exporting.set(false);
                        deleteExport(ready);
                        message("找不到可處理此檔案的應用程式");
                        if (shareId != null) completeShare(shareId, false, "無法開啟分享面板");
                    }
                });
            } catch (IOException | IllegalArgumentException e) {
                if (file != null) deleteExport(file);
                exporting.set(false);
                message("準備匯出檔案失敗，請確認檔案大小與儲存空間");
                if (shareId != null) completeShare(shareId, false, "無法準備分享檔案");
            }
        });
        return true;
    }

    /** Only exposed to the bundled appassets page; never load remote content into this WebView. */
    public final class NativeBridge {
        @JavascriptInterface
        public boolean captureScene(String filename) {
            if (exporting.get() || !capturing.compareAndSet(false, true)) return false;
            runOnUiThread(() -> {
                if (isDestroyed() || webView == null || !resumed || webView.getWidth() == 0) {
                    capturing.set(false);
                    js("window.OCPolish && OCPolish.photoDone()");
                    return;
                }
                int[] location = new int[2];
                webView.getLocationInWindow(location);
                Rect rectangle = new Rect(location[0], location[1],
                        location[0] + webView.getWidth(), location[1] + webView.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    PixelCopy.request(getWindow(), rectangle, bitmap, result -> {
                        js("window.OCPolish && OCPolish.photoDone()");
                        if (result != PixelCopy.SUCCESS) {
                            bitmap.recycle(); capturing.set(false);
                            message("拍照未成功，請再試一次");
                            return;
                        }
                        io.execute(() -> {
                            try {
                                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) throw new IOException("Cannot encode photo");
                                queueExport(filename, "image/png", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP), true, null);
                            } catch (IOException e) {
                                message("照片儲存失敗，請確認儲存空間");
                            } finally { bitmap.recycle(); capturing.set(false); }
                        });
                    }, new Handler(Looper.getMainLooper()));
                } catch (IllegalArgumentException e) {
                    bitmap.recycle(); capturing.set(false);
                    js("window.OCPolish && OCPolish.photoDone()");
                    message("目前無法拍照，請再試一次");
                }
            });
            return true;
        }

        @JavascriptInterface
        public boolean saveFile(String filename, String text) {
            return queueExport(filename, "application/json", text, false, null);
        }

        @JavascriptInterface
        public boolean saveBase64(String filename, String mime, String content) {
            return queueExport(filename, mime, content, true, null);
        }

        @JavascriptInterface
        public boolean shareBase64(String filename, String mime, String content, String id) {
            if (id == null || !id.matches("share-[0-9]+")) return false;
            return queueExport(filename, mime, content, true, id);
        }

        @JavascriptInterface
        public void vibrate(int milliseconds) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(Math.max(1, Math.min(milliseconds, 200)), VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }

        @JavascriptInterface
        public void setTheme(String background, boolean dark) {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                try {
                    int color = Color.parseColor(background);
                    root.setBackgroundColor(color);
                    webView.setBackgroundColor(color);
                    getWindow().setStatusBarColor(color);
                    getWindow().setNavigationBarColor(color);
                    if (Build.VERSION.SDK_INT >= 30) {
                        WindowInsetsController controller = getWindow().getInsetsController();
                        if (controller != null) {
                            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                            controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
                        }
                    } else {
                        int flags = getWindow().getDecorView().getSystemUiVisibility();
                        int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        getWindow().getDecorView().setSystemUiVisibility(dark ? flags & ~mask : flags | mask);
                    }
                } catch (IllegalArgumentException ignored) { /* Keep the current theme. */ }
            });
        }
    }
}
