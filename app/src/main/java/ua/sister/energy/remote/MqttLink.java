package ua.sister.energy.remote;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;

/** Broker settings (baked in at build time from secrets; never stored in git). */
final class BrokerConfig {
    final String host, user, pass;
    final int port;
    final boolean tls;

    BrokerConfig(String host, int port, String user, String pass, boolean tls) {
        this.host = host == null ? "" : host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.tls = tls;
    }

    boolean configured() { return !host.isEmpty() && !user.isEmpty(); }
}

/** Blocking MQTT link — always called from the RemoteMonitor I/O thread, never from Main/BLE. */
interface MqttLink {
    boolean isConnected();
    /** Connects with LWT {@code bms/<login>/online}="0" retained; throws on failure. */
    void connect(String login) throws Exception;
    /** Publish; throws on failure. QoS 1 blocks until PUBACK (or timeout). */
    void publish(String topic, String payload, int qos, boolean retained) throws Exception;
    /** QoS 1 subscription; {@code onMessage} runs on the client thread. */
    void subscribe(String topic, java.util.function.Consumer<String> onMessage) throws Exception;
    void disconnect();
}

/** Keep-alive 120 s: short enough for typical mobile NAT bindings, few enough PINGs for the free-tier budget. */
final class PahoLink implements MqttLink {
    static final int KEEPALIVE_S = 120;
    private final BrokerConfig cfg;
    private final String clientId;
    private MqttClient client;

    PahoLink(BrokerConfig cfg, String clientId) {
        this.cfg = cfg;
        this.clientId = clientId;
    }

    @Override public synchronized boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override public synchronized void connect(String login) throws Exception {
        disconnect();
        String uri = (cfg.tls ? "ssl://" : "tcp://") + cfg.host + ":" + cfg.port;
        MqttClient c = new MqttClient(uri, clientId, new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setAutomaticReconnect(false);
        o.setConnectionTimeout(15);
        o.setKeepAliveInterval(KEEPALIVE_S);
        o.setMaxInflight(10);
        o.setUserName(cfg.user);
        o.setPassword(cfg.pass.toCharArray());
        if (cfg.tls) o.setSocketFactory(SSLContext.getDefault().getSocketFactory());
        o.setWill(RemotePayload.onlineTopic(login), "0".getBytes(), 1, true);
        c.setTimeToWait(15_000);
        c.connect(o);
        client = c;
    }

    @Override public synchronized void publish(String topic, String payload, int qos, boolean retained) throws Exception {
        MqttClient c = client;
        if (c == null) throw new IllegalStateException("not connected");
        MqttMessage m = new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        m.setQos(qos);
        m.setRetained(retained);
        c.publish(topic, m);
    }

    @Override public synchronized void subscribe(String topic, java.util.function.Consumer<String> onMessage) throws Exception {
        MqttClient c = client;
        if (c == null) throw new IllegalStateException("not connected");
        c.subscribe(topic, 1, (t, msg) -> {
            try { onMessage.accept(new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
        });
    }

    @Override public synchronized void disconnect() {
        MqttClient c = client;
        client = null;
        if (c == null) return;
        try { if (c.isConnected()) c.disconnect(2_000); } catch (Exception ignored) { }
        try { c.close(); } catch (Exception ignored) { }
    }
}
