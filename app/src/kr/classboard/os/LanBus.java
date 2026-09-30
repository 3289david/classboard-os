package kr.classboard.os;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** UDP broadcast discovery so classroom boards find the school hub on the local network without internet. */
public class LanBus {
    public static final int PORT = 8788;
    private final Context ctx;
    private final Map<String, JSONObject> hubs = new ConcurrentHashMap<>();
    private volatile boolean running;
    private DatagramSocket sock;
    private WifiManager.MulticastLock lock;

    public LanBus(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void start(Supplier<JSONObject> beacon) {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("classboard");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w("LanBus", "multicast lock", e);
        }
        Thread rx = new Thread(() -> {
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.bind(new java.net.InetSocketAddress(PORT));
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    try {
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if ("hub".equals(o.optString("t"))) {
                            String ip = p.getAddress().getHostAddress();
                            Util.put(o, "ip", ip);
                            Util.put(o, "url", "http://" + ip + ":" + o.optInt("port", DeviceConfig.PORT));
                            Util.put(o, "seen", System.currentTimeMillis());
                            hubs.put(o.optString("id", ip), o);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                if (running) Log.w("LanBus", "rx", e);
            }
        }, "lan-rx");
        rx.setDaemon(true);
        rx.start();
        Thread tx = new Thread(() -> {
            while (running) {
                try {
                    JSONObject b = beacon.get();
                    if (b != null) send(b.toString().getBytes(StandardCharsets.UTF_8));
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "lan-tx");
        tx.setDaemon(true);
        tx.start();
    }

    private void send(byte[] data) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            boolean sent = false;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bc = ia.getBroadcast();
                    if (bc == null) continue;
                    s.send(new DatagramPacket(data, data.length, bc, PORT));
                    sent = true;
                }
            }
            if (!sent) s.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), PORT));
        } catch (Exception e) {
            Log.d("LanBus", "send", e);
        }
    }

    public JSONArray hubs() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        for (JSONObject o : hubs.values()) if (now - o.optLong("seen") < 15000) a.put(o);
        return a;
    }

    public void stop() {
        running = false;
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}
