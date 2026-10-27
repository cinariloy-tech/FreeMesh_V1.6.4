package com.freemesh.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_BLE = 41;
    private static final String CH_ID = "freemesh_msgs";
    private WebView webView;
    private FreeMeshBle ble;
    private Voice voice;
    private boolean resumed = false;

    // WebView'de window.FMBridge olarak görünür
    public class JsBridge {
        @JavascriptInterface
        public void send(String json) { if (ble != null) ble.send(json); }

        @JavascriptInterface
        public boolean connected() { return ble != null && ble.isConnected(); }

        // Yönlendirme: mesajı, geldiği bağlantı hariç diğer bağlantılara iletir
        @JavascriptInterface
        public void relay(String json, String via) { if (ble != null) ble.relay(json, via); }

        // Bu telefonun kalıcı kimliği (PH-XXXXXX)
        @JavascriptInterface
        public String myId() { return ble != null ? ble.myId() : "PH-LOCAL"; }

        // Uygulama arka plandayken bildirim gösterir
        @JavascriptInterface
        public void notify(String title, String body) { showNotification(title, body); }

        // Yerel ses: mikrofon, hoparlör, zil
        @JavascriptInterface
        public boolean micStart(String mac) { return voice != null && voice.micStart(mac); }

        @JavascriptInterface
        public void micStop() { if (voice != null) voice.micStop(); }

        @JavascriptInterface
        public void ringStart() { if (voice != null) voice.ringStart(); }

        @JavascriptInterface
        public void ringStop() { if (voice != null) voice.ringStop(); }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources()); // yalnızca mikrofon istenir
            }
        });
        webView.addJavascriptInterface(new JsBridge(), "FMBridge");

        setContentView(webView);
        ble = new FreeMeshBle(this, webView);
        voice = new Voice(this, ble);
        ble.setVoice(voice);
        // Uygulama dosyaları APK'nın içinde; sunucu veya internet gerekmez
        webView.loadUrl("file:///android_asset/index.html");
        askPermissions();
    }

    private void askPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (need.isEmpty()) ble.start();
        else requestPermissions(need.toArray(new String[0]), REQ_BLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE && ble != null) ble.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    private void showNotification(String title, String body) {
        if (resumed) return; // uygulama açıkken bildirim gerekmez
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Mesajlar ve aramalar",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }

    @Override
    protected void onDestroy() {
        if (voice != null) voice.close();
        if (ble != null) ble.close();
        super.onDestroy();
    }
}
