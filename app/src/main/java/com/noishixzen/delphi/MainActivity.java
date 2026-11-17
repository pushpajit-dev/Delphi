package com.noishixzen.delphi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://appassets.androidplatform.net/index.html";
    private static final String GITHUB_URL = "https://github.com/1612elphi/delphitools";

    private WebView webView;
    private View splashLayout;
    private View maintenanceLayout;
    private ValueCallback<Uri[]> filePathCallback;

    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
    );

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {}
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        splashLayout = findViewById(R.id.splashLayout);
        maintenanceLayout = findViewById(R.id.maintenanceLayout);

        Button btnRetry = findViewById(R.id.btnRetry);
        Button btnContactDev = findViewById(R.id.btnContactDev);

        btnRetry.setOnClickListener(v -> reloadApp());
        btnContactDev.setOnClickListener(v -> openUrl(GITHUB_URL));

        requestAppPermissions();
        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        webView.loadUrl(APP_URL);
    }

    private void requestAppPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
        };
        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            permissionLauncher.launch(permissions);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                maintenanceLayout.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Hide splash smoothly
                splashLayout.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    splashLayout.setVisibility(View.GONE);
                    splashLayout.setAlpha(1f);
                });
                injectDownloadAndCopyBridge();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showMaintenanceMode();
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();

                if ("appassets.androidplatform.net".equals(host)) {
                    String path = uri.getPath();
                    if (path == null || path.isEmpty() || path.equals("/")) {
                        path = "index.html";
                    }
                    while (path.startsWith("/")) {
                        path = path.substring(1);
                    }

                    try {
                        InputStream is = getAssets().open(path);
                        String mimeType = getMimeType(path);
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        headers.put("Cross-Origin-Opener-Policy", "same-origin");
                        headers.put("Cross-Origin-Embedder-Policy", "require-corp");
                        return new WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, is);
                    } catch (IOException e) {
                        // SPA fallback: route to index.html if request is navigation without extension
                        if (!path.contains(".")) {
                            try {
                                InputStream is = getAssets().open("index.html");
                                Map<String, String> headers = new HashMap<>();
                                headers.put("Access-Control-Allow-Origin", "*");
                                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, is);
                            } catch (IOException ignored) {}
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.setType("*/*");
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fileChooserLauncher.launch(Intent.createChooser(fallback, "Select File"));
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("data:")) {
                saveDataUri(url, mimetype);
            } else if (url.startsWith("blob:")) {
                String js = "(function(){" +
                        "fetch('" + url + "').then(r=>r.blob()).then(b=>{" +
                        "  var reader = new FileReader();" +
                        "  reader.onloadend = function(){" +
                        "    AndroidNative.saveFile('download_" + System.currentTimeMillis() + "', b.type, reader.result.split(',')[1]);" +
                        "  };" +
                        "  reader.readAsDataURL(b);" +
                        "});" +
                        "})();";
                webView.evaluateJavascript(js, null);
            } else {
                openUrl(url);
            }
        });
    }

    private void injectDownloadAndCopyBridge() {
        String js = "(function() {" +
                "  if (window._delphiAndroidInjected) return;" +
                "  window._delphiAndroidInjected = true;" +
                "  var originalClipboard = navigator.clipboard && navigator.clipboard.writeText;" +
                "  if (navigator.clipboard) {" +
                "    navigator.clipboard.writeText = function(text) {" +
                "      if (window.AndroidNative) window.AndroidNative.copyToClipboard(text);" +
                "      return originalClipboard ? originalClipboard.call(navigator.clipboard, text) : Promise.resolve();" +
                "    };" +
                "  }" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void showMaintenanceMode() {
        runOnUiThread(() -> {
            splashLayout.setVisibility(View.GONE);
            maintenanceLayout.setVisibility(View.VISIBLE);
        });
    }

    private void reloadApp() {
        maintenanceLayout.setVisibility(View.GONE);
        splashLayout.setVisibility(View.VISIBLE);
        webView.loadUrl(APP_URL);
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDataUri(String dataUri, String defaultMime) {
        try {
            int commaIndex = dataUri.indexOf(",");
            if (commaIndex == -1) return;
            String metadata = dataUri.substring(5, commaIndex);
            String base64Data = dataUri.substring(commaIndex + 1);
            byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);

            String ext = ".bin";
            if (metadata.contains("image/png")) ext = ".png";
            else if (metadata.contains("image/jpeg")) ext = ".jpg";
            else if (metadata.contains("application/pdf")) ext = ".pdf";
            else if (metadata.contains("text/plain")) ext = ".txt";
            else if (metadata.contains("image/svg")) ext = ".svg";

            saveBytesToFile("delphi_" + System.currentTimeMillis() + ext, decoded, defaultMime);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveBytesToFile(String filename, byte[] data, String mimeType) {
        runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null && !mimeType.isEmpty() ? mimeType : "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) {
                                os.write(data);
                            }
                        }
                        Toast.makeText(MainActivity.this, "Saved: " + filename + " to Downloads", Toast.LENGTH_LONG).show();
                        return;
                    }
                }

                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                    dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
                File file = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                Toast.makeText(MainActivity.this, "Saved: " + file.getName() + " to Downloads", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void saveFile(String filename, String mimeType, String base64Data) {
            try {
                byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
                String ext = "";
                if (!filename.contains(".")) {
                    if (mimeType.contains("pdf")) ext = ".pdf";
                    else if (mimeType.contains("png")) ext = ".png";
                    else if (mimeType.contains("jpeg") || mimeType.contains("jpg")) ext = ".jpg";
                    else if (mimeType.contains("json")) ext = ".json";
                    else if (mimeType.contains("text")) ext = ".txt";
                    else if (mimeType.contains("zip")) ext = ".zip";
                }
                saveBytesToFile(filename + ext, decoded, mimeType);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            runOnUiThread(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("delphi", text);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @NonNull
    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
