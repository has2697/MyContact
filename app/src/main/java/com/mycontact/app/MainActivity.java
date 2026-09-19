package com.mycontact.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Hosts MyContact.html (assets) inside a WebView.
 * All logic (WebRTC signaling, chat, calls, settings) lives in that single HTML file;
 * this class only wires up the Android-side permissions the page needs for
 * getUserMedia() (camera + microphone) to work inside a WebView.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_RUNTIME_PERMISSIONS = 101;
    private static final String SHARE_PREFS_NAME = "mycontact_share_prefs";
    private static final String KEY_LAST_SHARE_PACKAGE = "last_share_package";
    private static final String KEY_LAST_SHARE_LABEL = "last_share_label";
    private static final String ACTION_SHARE_TARGET_CHOSEN =
            "com.mycontact.app.ACTION_SHARE_TARGET_CHOSEN";

    private WebView webView;
    private SharedPreferences sharePrefs;
    private BroadcastReceiver shareChosenReceiver;

    // A pending WebView permission request (camera/mic for a call) that is
    // waiting on the Android runtime permission dialog to be answered.
    private PermissionRequest pendingWebPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Keep the screen on during calls / while the app is open.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharePrefs = getSharedPreferences(SHARE_PREFS_NAME, MODE_PRIVATE);
        registerShareChosenReceiver();

        webView = findViewById(R.id.webview);
        setupWebView();
        requestNeededRuntimePermissions();

        webView.loadUrl("file:///android_asset/MyContact.html");
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Exposes shareText() to the page's JavaScript as window.AndroidShare.
        // Standard Android WebViews do not implement navigator.share() at all,
        // so the page calls this bridge instead to open the real Android
        // share sheet (WhatsApp, Telegram, SMS, email, etc.).
        webView.addJavascriptInterface(new AndroidShareBridge(), "AndroidShare");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep everything inside the app itself; the app is fully self-contained
                // (no external navigation is expected in normal use).
                if (url != null && url.startsWith("file:///android_asset/")) {
                    return false;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // This fires when the page calls getUserMedia() for a voice/video call.
                runOnUiThread(() -> {
                    boolean needCamera = false;
                    boolean needMic = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            needCamera = true;
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            needMic = true;
                        }
                    }

                    boolean cameraOk = !needCamera || hasPermission(Manifest.permission.CAMERA);
                    boolean micOk = !needMic || hasPermission(Manifest.permission.RECORD_AUDIO);

                    if (cameraOk && micOk) {
                        request.grant(request.getResources());
                    } else {
                        // Ask Android for the missing permission(s) first, then grant
                        // the WebView request once the user answers.
                        pendingWebPermissionRequest = request;
                        java.util.ArrayList<String> toRequest = new java.util.ArrayList<>();
                        if (!cameraOk) toRequest.add(Manifest.permission.CAMERA);
                        if (!micOk) toRequest.add(Manifest.permission.RECORD_AUDIO);
                        ActivityCompat.requestPermissions(MainActivity.this,
                                toRequest.toArray(new String[0]), REQ_RUNTIME_PERMISSIONS);
                    }
                });
            }
        });
    }

    /** Ask for camera/mic up front so the first call attempt isn't slowed down by a dialog. */
    private void requestNeededRuntimePermissions() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_RUNTIME_PERMISSIONS);
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Listens for the app the user picked in the Android share sheet (see
     * AndroidShareBridge below) and remembers it, so next time the page can
     * offer to send straight to that app instead of showing the chooser again.
     */
    private void registerShareChosenReceiver() {
        shareChosenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ComponentName chosen = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
                if (chosen == null) return;
                String packageName = chosen.getPackageName();
                String label = packageName;
                try {
                    label = getPackageManager()
                            .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                            .toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                    // fall back to the raw package name as the label
                }
                sharePrefs.edit()
                        .putString(KEY_LAST_SHARE_PACKAGE, packageName)
                        .putString(KEY_LAST_SHARE_LABEL, label)
                        .apply();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SHARE_TARGET_CHOSEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shareChosenReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(shareChosenReceiver, filter);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RUNTIME_PERMISSIONS) return;

        if (pendingWebPermissionRequest != null) {
            boolean cameraOk = !containsResource(pendingWebPermissionRequest, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    || hasPermission(Manifest.permission.CAMERA);
            boolean micOk = !containsResource(pendingWebPermissionRequest, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    || hasPermission(Manifest.permission.RECORD_AUDIO);

            if (cameraOk && micOk) {
                pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
            } else {
                pendingWebPermissionRequest.deny();
                Toast.makeText(this, "برای تماس صوتی/تصویری به مجوز دوربین و میکروفن نیاز است",
                        Toast.LENGTH_LONG).show();
            }
            pendingWebPermissionRequest = null;
        }
    }

    private boolean containsResource(PermissionRequest request, String resource) {
        for (String r : request.getResources()) {
            if (r.equals(resource)) return true;
        }
        return false;
    }

    /**
     * JS bridge exposed as window.AndroidShare in MyContact.html. Opens the
     * real Android share sheet via Intent.ACTION_SEND, since the standard
     * WebView (unlike the Chrome app) does not implement navigator.share().
     */
    private class AndroidShareBridge {
        /**
         * @param targetPackage if non-empty, sends straight to this app
         *                      (no chooser dialog); if it's not installed
         *                      anymore, falls back to the normal chooser.
         */
        @JavascriptInterface
        public void shareText(final String text, final String title, final String targetPackage) {
            runOnUiThread(() -> {
                try {
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("text/plain");
                    sendIntent.putExtra(Intent.EXTRA_TEXT, text);
                    if (title != null && !title.isEmpty()) {
                        sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                    }

                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        try {
                            Intent direct = new Intent(sendIntent);
                            direct.setPackage(targetPackage);
                            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(direct);
                            return;
                        } catch (Exception e) {
                            // app no longer installed / can't handle it — fall
                            // through to the normal chooser below instead.
                        }
                    }

                    // Chooser with a receiver attached so we find out which
                    // app the user picked, to remember it for next time.
                    Intent receiverIntent = new Intent(ACTION_SHARE_TARGET_CHOSEN)
                            .setPackage(getPackageName());
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT;
                    PendingIntent pi = PendingIntent.getBroadcast(
                            MainActivity.this, 0, receiverIntent, flags);
                    Intent chooser = Intent.createChooser(sendIntent, title, pi.getIntentSender());
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "اشتراک‌گذاری ممکن نشد",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        /**
         * Returns the last app the user picked from the share sheet, as a
         * JSON string like {"package":"org.telegram.messenger","label":"Telegram"},
         * or null if nothing has been picked yet.
         */
        @JavascriptInterface
        public String getLastShareTarget() {
            String pkg = sharePrefs.getString(KEY_LAST_SHARE_PACKAGE, null);
            String label = sharePrefs.getString(KEY_LAST_SHARE_LABEL, null);
            if (pkg == null || label == null) return null;
            // Only offer it back if the app is still installed.
            try {
                getPackageManager().getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("package", pkg);
                obj.put("label", label);
                return obj.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        if (shareChosenReceiver != null) {
            try {
                unregisterReceiver(shareChosenReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
        }
        super.onDestroy();
    }
}
