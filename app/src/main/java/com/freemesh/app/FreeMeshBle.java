package com.freemesh.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.WebView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Çoklu bağlantılı mesh: aynı anda birden çok telefona/düğüme bağlanır (merkez rolü)
 * ve birden çok telefonun bağlanmasına izin verir (sunucu rolü).
 *
 * Mesaj ("msg") ve teslim onayı ("ack") paketleri hedef telefona kadar yönlendirilir:
 * ara telefonlar paketi sadece iletir, kendi ekranına vermez. Arama, Bas-Konuş ve ses
 * paketleri yalnızca doğrudan hedefe gider.
 */
public class FreeMeshBle {
    static final UUID SVC  = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID TX   = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int INIT_TTL = 6;     // en fazla 5 atlama
    static final int MAX_LINKS = 6;    // aynı anda en çok bu kadar merkez bağlantı

    private final Context ctx;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    final String myId;
    private Voice voice;
    private boolean closed = false;
    private boolean rssiRunning = false;
    private String lastStatus = "";
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    // Merkez rolü: bu telefonun bağlandığı cihazlar
    private static final class Link {
        final String addr;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic rx;
        boolean ready = false;
        boolean writing = false;
        int mtu = 23;
        int rssi = -60;
        final ArrayDeque<byte[]> q = new ArrayDeque<>();
        final ByteArrayOutputStream in = new ByteArrayOutputStream();
        Link(String a) { addr = a; }
    }
    private final Map<String, Link> links = new HashMap<>();
    private final Set<String> connecting = new HashSet<>();
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

    // Sunucu rolü: bu telefona bağlanan cihazlar
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic srvTx;
    private BluetoothLeAdvertiser advertiser;
    private final AdvertiseCallback advCb = new AdvertiseCallback() {};
    private final Map<String, BluetoothDevice> subs = new HashMap<>();
    private final Map<String, ArrayDeque<byte[]>> subQ = new HashMap<>();
    private final Map<String, Boolean> subBusy = new HashMap<>();
    private final Map<String, Integer> subMtu = new HashMap<>();
    private final Map<String, ByteArrayOutputStream> subIn = new HashMap<>();

    FreeMeshBle(Context c, WebView w) {
        ctx = c;
        web = w;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        myId = loadId(c);
    }

    void setVoice(Voice v) { voice = v; }

    // Telefonun kalıcı kimliği (ilk açılışta üretilir)
    private static String loadId(Context c) {
        SharedPreferences p = c.getSharedPreferences("freemesh", Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            id = "PH-" + randomHex(6);
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    private static String randomHex(int n) {
        String hex = "0123456789ABCDEF";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(hex.charAt(r.nextInt(16)));
        return sb.toString();
    }

    synchronized boolean isConnected() { return hasAnyLink(); }

    private boolean has(String perm) { return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private boolean hasScanPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT);
        return has(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean hasAdvPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return has(Manifest.permission.BLUETOOTH_ADVERTISE) && has(Manifest.permission.BLUETOOTH_CONNECT);
        return true;
    }

    // ---------- Başlatma ----------
    synchronized void start() {
        if (closed || adapter == null) return;
        if (!adapter.isEnabled()) { status("bt_off"); retryLater(); return; }
        if (!hasScanPerms()) return;
        startServer();
        startScan();
        if (!rssiRunning) {
            rssiRunning = true;
            ui.postDelayed(rssiTick, 3000);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startScan() {
        if (closed || scanning || adapter == null || !adapter.isEnabled() || !hasScanPerms()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        scanner.startScan(Collections.singletonList(f), s, scanCb);
    }

    @SuppressLint("MissingPermission")
    private synchronized void startServer() {
        if (server != null || !hasAdvPerms()) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        server = bm.openGattServer(ctx, serverCb);
        if (server == null) return;

        BluetoothGattService svc = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rxc = new BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        srvTx = new BluetoothGattCharacteristic(TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        srvTx.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(rxc);
        svc.addCharacteristic(srvTx);
        server.addService(svc);

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser != null) {
            AdvertiseSettings st = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SVC))
                    .build();
            advertiser.startAdvertising(st, data, advCb);
        }
    }

    // ---------- Tarama ve merkez bağlantıları ----------
    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            tryConnect(r.getDevice());
        }
        @Override public void onScanFailed(int errorCode) {
            synchronized (FreeMeshBle.this) { scanning = false; }
            retryLater();
        }
    };

    @SuppressLint("MissingPermission")
    private synchronized void tryConnect(BluetoothDevice d) {
        String a = d.getAddress();
        if (closed || links.containsKey(a) || connecting.contains(a)) return;
        if (subs.containsKey(a)) return; // bu cihaz zaten bize bağlı
        if (links.size() >= MAX_LINKS) return;
        Link l = new Link(a);
        links.put(a, l);
        connecting.add(a);
        status("connecting");
        l.gatt = d.connectGatt(ctx, false, new GattCb(l), BluetoothDevice.TRANSPORT_LE);
    }

    private final class GattCb extends BluetoothGattCallback {
        private final Link l;
        GattCb(Link link) { l = link; }

        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dropLink(l, g);
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int st) {
            synchronized (FreeMeshBle.this) { l.mtu = newMtu; }
            g.discoverServices();
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { g.disconnect(); return; }
            BluetoothGattCharacteristic rxc = svc.getCharacteristic(RX);
            BluetoothGattCharacteristic tx = svc.getCharacteristic(TX);
            if (rxc == null || tx == null) { g.disconnect(); return; }
            synchronized (FreeMeshBle.this) { l.rx = rxc; }
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                markReady(l);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            markReady(l);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            onBytes(ch.getValue(), l.in, l.addr, l.rssi);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int st) {
            synchronized (FreeMeshBle.this) { l.writing = false; }
            pump(l);
        }

        @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int st) {
            if (st == BluetoothGatt.GATT_SUCCESS) {
                synchronized (FreeMeshBle.this) { l.rssi = rssi; }
            }
        }
    }

    private synchronized void markReady(Link l) {
        if (links.get(l.addr) != l) return;
        l.ready = true;
        connecting.remove(l.addr);
        updateReady();
    }

    private synchronized void dropLink(Link l, BluetoothGatt g) {
        if (links.get(l.addr) == l) links.remove(l.addr);
        connecting.remove(l.addr);
        l.ready = false;
        l.q.clear();
        try { g.close(); } catch (Exception ignored) { }
        updateReady();
        retryLater();
    }

    private final Runnable rssiTick = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override public void run() {
            synchronized (FreeMeshBle.this) {
                for (Link l : links.values()) if (l.ready && l.gatt != null) l.gatt.readRemoteRssi();
            }
            ui.postDelayed(this, 3000);
        }
    };

    // ---------- Sunucu rolü ----------
    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) removeSub(d.getAddress());
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int reqId,
                BluetoothGattCharacteristic ch, boolean prep, boolean respNeeded, int offset, byte[] value) {
            if (RX.equals(ch.getUuid())) {
                ByteArrayOutputStream buf;
                synchronized (FreeMeshBle.this) {
                    buf = subIn.get(d.getAddress());
                    if (buf == null) { buf = new ByteArrayOutputStream(); subIn.put(d.getAddress(), buf); }
                }
                onBytes(value, buf, d.getAddress(), -60);
            }
            if (respNeeded && server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int reqId, BluetoothGattDescriptor desc,
                boolean prep, boolean respNeeded, int offset, byte[] value) {
            if (CCCD.equals(desc.getUuid())) {
                boolean on = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                if (on) addSub(d);
                else removeSub(d.getAddress());
            }
            if (respNeeded && server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onMtuChanged(BluetoothDevice d, int m) {
            synchronized (FreeMeshBle.this) { subMtu.put(d.getAddress(), m); }
        }

        @Override public void onNotificationSent(BluetoothDevice d, int st) {
            synchronized (FreeMeshBle.this) { subBusy.put(d.getAddress(), false); }
            flushSub(d.getAddress());
        }
    };

    private synchronized void addSub(BluetoothDevice d) {
        String a = d.getAddress();
        subs.put(a, d);
        if (!subQ.containsKey(a)) subQ.put(a, new ArrayDeque<>());
        subBusy.put(a, false);
        updateReady();
    }

    private synchronized void removeSub(String a) {
        subs.remove(a);
        subQ.remove(a);
        subBusy.remove(a);
        subMtu.remove(a);
        subIn.remove(a);
        updateReady();
    }

    @SuppressLint("MissingPermission")
    private synchronized void flushSub(String a) {
        if (Boolean.TRUE.equals(subBusy.get(a))) return;
        BluetoothDevice d = subs.get(a);
        ArrayDeque<byte[]> q = subQ.get(a);
        if (d == null || q == null || server == null || srvTx == null) return;
        byte[] c = q.poll();
        if (c == null) return;
        subBusy.put(a, true);
        srvTx.setValue(c);
        if (!server.notifyCharacteristicChanged(d, srvTx, false)) subBusy.put(a, false);
    }

    // ---------- Gönderme ----------
    /** Web'den gelen JSON. Mesaj/onay ise yönlendirilir, diğerleri komşulara gider. */
    synchronized void send(String json) {
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        String k = o.optString("k");
        try {
            o.put("from", myId);
            if (k.equals("msg") || k.equals("ack")) {
                if (o.optString("id").isEmpty()) o.put("id", randomHex(10));
                o.put("ttl", INIT_TTL);
                markSeen(o.getString("id"));
            }
        } catch (Exception ignored) { }
        broadcast(o.toString(), null);
    }

    /** Tüm hazır bağlantılara gönderir; exceptAddr'dan gelen bağlantıya geri göndermez. */
    private synchronized void broadcast(String json, String exceptAddr) {
        byte[] b = (json + "\n").getBytes(StandardCharsets.UTF_8);
        for (Link l : links.values()) {
            if (!l.ready || l.rx == null || l.addr.equals(exceptAddr)) continue;
            int cs = Math.max(20, l.mtu - 3);
            for (int i = 0; i < b.length; i += cs)
                l.q.add(Arrays.copyOfRange(b, i, Math.min(b.length, i + cs)));
            pump(l);
        }
        for (String a : new HashSet<>(subs.keySet())) {
            if (a.equals(exceptAddr)) continue;
            int cs = Math.max(20, subMtu.getOrDefault(a, 23) - 3);
            ArrayDeque<byte[]> q = subQ.get(a);
            if (q == null) continue;
            for (int i = 0; i < b.length; i += cs)
                q.add(Arrays.copyOfRange(b, i, Math.min(b.length, i + cs)));
            flushSub(a);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void pump(Link l) {
        if (l.writing || l.gatt == null || l.rx == null) return;
        byte[] c = l.q.poll();
        if (c == null) return;
        l.writing = true;
        l.rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        l.rx.setValue(c);
        if (!l.gatt.writeCharacteristic(l.rx)) l.writing = false;
    }

    // ---------- Alma ve yönlendirme ----------
    private synchronized boolean markSeen(String id) {
        if (seen.contains(id)) return false;
        seen.add(id);
        if (seen.size() > 500) {
            String first = seen.iterator().next();
            seen.remove(first);
        }
        return true;
    }

    private void onBytes(byte[] v, ByteArrayOutputStream buf, String fromAddr, int rssi) {
        if (v == null) return;
        for (byte b : v) {
            if (b == '\n') {
                String line = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                buf.reset();
                if (!line.isEmpty()) onLine(line, fromAddr, rssi);
            } else {
                buf.write(b);
            }
        }
    }

    private void onLine(String raw, String fromAddr, int rssi) {
        JSONObject o;
        try { o = new JSONObject(raw); } catch (Exception e) { return; }

        // ESP32 düğümünden gelen hazır "rx" satırları olduğu gibi web'e gider
        if ("rx".equals(o.optString("t"))) {
            js("window.onMeshRx && window.onMeshRx(" + JSONObject.quote(raw) + ")");
            return;
        }

        String k = o.optString("k");
        String to = o.optString("to");
        int hop = 1;

        if (k.equals("msg") || k.equals("ack")) {
            String id = o.optString("id");
            if (id.isEmpty() || !markSeen(id)) return;       // daha önce görüldü
            int ttl = o.optInt("ttl", 1);
            hop = INIT_TTL - ttl + 1;
            if (!to.equals(myId)) {                           // bize değil: sadece ilet
                if (ttl > 1) {
                    try { o.put("ttl", ttl - 1); } catch (Exception ignored) { }
                    broadcast(o.toString(), fromAddr);
                }
                return;
            }
        } else if (k.equals("au") || k.equals("ptt") || k.startsWith("call_")) {
            if (!to.equals(myId)) return;                     // arama ve ses yalnızca doğrudan hedefe
            if (k.equals("au")) {
                if (voice != null) voice.playLine(raw);
                return;
            }
        }

        JSONObject rx = new JSONObject();
        try {
            rx.put("t", "rx");
            rx.put("k", k);
            rx.put("from", o.optString("from"));
            rx.put("rssi", rssi);
            rx.put("hop", hop);
            rx.put("id", o.optString("id"));
            rx.put("ref", o.optString("ref"));
            rx.put("text", o.optString("text"));
        } catch (Exception ignored) { }
        js("window.onMeshRx && window.onMeshRx(" + JSONObject.quote(rx.toString()) + ")");
    }

    // ---------- Web ile iletişim ----------
    private synchronized boolean hasAnyLink() {
        if (!subs.isEmpty()) return true;
        for (Link l : links.values()) if (l.ready) return true;
        return false;
    }

    private synchronized void updateReady() {
        boolean ready = hasAnyLink();
        js("window.__fmReady = " + ready);
        status(ready ? "connected" : "disconnected");
    }

    private void retryLater() {
        if (!closed) ui.postDelayed(this::start, 3000);
    }

    private void status(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        js("window.onBleState && window.onBleState('" + s + "')");
    }

    private void js(String s) {
        ui.post(() -> web.evaluateJavascript(s, null));
    }

    @SuppressLint("MissingPermission")
    synchronized void close() {
        closed = true;
        ui.removeCallbacks(rssiTick);
        if (scanning && scanner != null && hasScanPerms()) { try { scanner.stopScan(scanCb); } catch (Exception ignored) { } }
        scanning = false;
        for (Link l : links.values()) { if (l.gatt != null) { try { l.gatt.close(); } catch (Exception ignored) { } } }
        links.clear();
        if (advertiser != null && hasAdvPerms()) { try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) { } }
        if (server != null) { try { server.close(); } catch (Exception ignored) { } server = null; }
        subs.clear();
    }
}
