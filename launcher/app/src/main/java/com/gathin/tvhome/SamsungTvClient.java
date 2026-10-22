package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/**
 * One bounded native Samsung connection, with no Mac dependency, implicit pairing, arbitrary URL,
 * key string, or global TLS bypass. All network, protocol, and token writes run off the UI thread.
 * Socket-send acceptance is only a request; this API does not read the Samsung's selected input.
 */
public final class SamsungTvClient implements AutoCloseable {
    public enum State { IDLE, CONNECTING, PAIRING, READY, ERROR }

    public enum Key {
        SOURCE("KEY_SOURCE"), UP("KEY_UP"), DOWN("KEY_DOWN"), LEFT("KEY_LEFT"),
        RIGHT("KEY_RIGHT"), ENTER("KEY_ENTER"), RETURN("KEY_RETURN");
        final String wireName;
        Key(String wireName) { this.wireName = wireName; }
    }

    public interface Listener {
        void onState(State state, String message);
        default void onSent(Key key) { }
    }

    private static final int MAX_BYTES = 16384;
    private static final int MAX_PENDING = 8;
    private static final String LOG_TAG = "NepalSamsung";
    private enum Stage {
        TLS_SETUP, IDENTITY_REQUEST, IDENTITY_READ, IDENTITY_VERIFIED,
        WEBSOCKET_CONNECT, WEBSOCKET_OPEN, AUTHORIZATION, AUTH_DENIED,
        AUTH_TOKEN_MISSING, AUTH_STORAGE_FAILED, AUTH_INVALID_RESPONSE, AUTH_READY
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<Key> pending = new ArrayDeque<>();
    private volatile State state = State.IDLE;
    private volatile boolean closing;
    private boolean connecting;
    private boolean pairing;
    private boolean draining;
    private int generation;
    private long nextSendAt;
    private OkHttpClient transport;
    private WebSocket socket;
    private Call identityCall;
    private ScheduledFuture<?> authorizationTimeout;

    public SamsungTvClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public State getState() { return state; }

    /** The only method that can request the Samsung's on-screen Allow prompt. */
    public void pair() {
        submit(() -> {
            pending.clear();
            disconnect();
            connect(true);
        });
    }

    /** Reuses a saved pairing, reconnecting if necessary; never starts a new approval request. */
    public void send(Key key) {
        if (key == null) return;
        submit(() -> {
            if (pending.size() >= MAX_PENDING) {
                fail("Too many Samsung key requests. Open Sources again and press one direction at a time.");
                return;
            }
            pending.add(key);
            if (state == State.READY && socket != null) drain();
            else if (!connecting) connect(false);
        });
    }

    private void submit(Runnable work) {
        if (closing) return;
        try { worker.execute(() -> { if (!closing) work.run(); }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private void connect(boolean pair) {
        final SamsungTvSettings target = SamsungTvSettings.load(context);
        if (!target.isConfigured()) { fail("Set up the Samsung TV's IP address and exact model first."); return; }
        if (!pair && !target.isPaired()) { fail("Pair Samsung TV first, then allow Nepal Home on the TV."); return; }
        pairing = pair;
        connecting = true;
        int attempt = ++generation;
        final AtomicReference<Stage> phase = new AtomicReference<>(Stage.TLS_SETUP);
        diagnostic(phase.get(), null);
        report(State.CONNECTING, "Checking Samsung TV on your local network...");
        try {
            // Immutable per-attempt captures: a retiring TLS handshake must never observe a later
            // connection's target or trust state when Pair is pressed again.
            final SamsungTvSecurity.TargetTrust trust =
                    new SamsungTvSecurity.TargetTrust(target.certificatePin, pair);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[] {trust}, new SecureRandom());
            // TV certificates do not normally contain the IP SAN. Instead, require this exact
            // configured IPv4 plus the leaf checked by this client's scoped TOFU/pin manager.
            transport = new OkHttpClient.Builder()
                    .sslSocketFactory(tls.getSocketFactory(), trust)
                    .hostnameVerifier((hostname, session) -> {
                        if (!target.address.equals(hostname)) return false;
                        try {
                            Certificate[] peers = session.getPeerCertificates();
                            return peers.length > 0 && peers[0] instanceof X509Certificate
                                    && SamsungTvSecurity.fingerprint((X509Certificate) peers[0]).equals(trust.observedPin());
                        } catch (Exception error) { return false; }
                    })
                    .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                    .writeTimeout(3, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build();
            HttpUrl base = new HttpUrl.Builder().scheme("https").host(target.address).port(8002)
                    .addPathSegment("api").addPathSegment("v2").addPathSegment("").build();
            identityCall = transport.newCall(new Request.Builder().url(base)
                    // Samsung can close its identity HTTP socket without advertising that closure.
                    // Do not reuse that socket for the WebSocket upgrade: keep the same scoped TLS
                    // trust/pin, but require a fresh connection after this bounded preflight.
                    .header("Accept", "application/json").header("Connection", "close").build());
            phase.set(Stage.IDENTITY_REQUEST);
            diagnostic(phase.get(), null);
            try (Response response = identityCall.execute()) {
                phase.set(Stage.IDENTITY_READ);
                diagnostic(phase.get(), null);
                if (response.code() != 200 || response.body() == null) throw new ProtocolError("identity_unavailable");
                JSONObject info = new JSONObject(readBounded(response.body()));
                JSONObject device = info.optJSONObject("device");
                if (device == null || !target.model.equals(device.optString("modelName", ""))) {
                    throw new ProtocolError("wrong_tv_model");
                }
                if (!"true".equalsIgnoreCase(device.optString("TokenAuthSupport", ""))) {
                    throw new ProtocolError("unsupported_auth");
                }
            } finally { identityCall = null; }
            if (closing || generation != attempt) return;
            phase.set(Stage.IDENTITY_VERIFIED);
            diagnostic(phase.get(), null);
            String name = Base64.encodeToString("Nepal Home".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            HttpUrl.Builder url = base.newBuilder().encodedPath("/api/v2/channels/samsung.remote.control")
                    .addQueryParameter("name", name);
            if (!pair) url.addQueryParameter("token", target.token);
            if (pair) {
                report(State.PAIRING, "Choose Allow for Nepal Home on your Samsung TV. Use the Samsung remote if needed.");
            }
            authorizationTimeout = worker.schedule(() -> {
                if (attempt == generation && state != State.READY) {
                    diagnostic(phase.get(), new java.util.concurrent.TimeoutException());
                    fail(pair ? "Samsung pairing timed out. Open Pair again and choose Allow on the TV."
                            : "Samsung did not authorize the saved pairing. Try Pair Samsung TV again.");
                }
            }, pair ? 40 : 10, TimeUnit.SECONDS);
            phase.set(Stage.WEBSOCKET_CONNECT);
            diagnostic(phase.get(), null);
            socket = transport.newWebSocket(new Request.Builder().url(url.build()).build(), new WebSocketListener() {
                private int messages;
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    phase.set(Stage.WEBSOCKET_OPEN);
                    diagnostic(phase.get(), null);
                    phase.set(Stage.AUTHORIZATION);
                    diagnostic(phase.get(), null);
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    // Bound the messages forwarded to our one serialized command queue.
                    if (++messages > 256 || text.length() > MAX_BYTES) {
                        diagnostic(Stage.AUTH_INVALID_RESPONSE, null);
                        webSocket.cancel();
                        submit(() -> { if (attempt == generation) fail("Samsung sent an invalid response."); });
                        return;
                    }
                    submit(() -> { if (attempt == generation) receive(text, target, trust, phase); });
                }
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    diagnostic(Stage.AUTH_INVALID_RESPONSE, null);
                    webSocket.cancel();
                    submit(() -> { if (attempt == generation) fail("Samsung sent an invalid response."); });
                }
                @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(1000, null);
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    submit(() -> { if (attempt == generation) fail("Samsung connection closed. Open Sources to reconnect."); });
                }
                @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                    diagnostic(phase.get(), error);
                    submit(() -> { if (attempt == generation) fail(publicError(error)); });
                }
            });
        } catch (Exception error) {
            diagnostic(phase.get(), error);
            fail(publicError(error));
        }
    }

    private static String readBounded(ResponseBody body) throws Exception {
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new ProtocolError("identity_too_large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void receive(String text, SamsungTvSettings target, SamsungTvSecurity.TargetTrust trust,
                         AtomicReference<Stage> phase) {
        try {
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ProtocolError("invalid_message");
            JSONObject message = new JSONObject(text);
            String event = message.optString("event", "");
            if ("ms.channel.unauthorized".equals(event) || "ms.channel.timeOut".equals(event) || "ms.error".equals(event)) {
                phase.set(Stage.AUTH_DENIED);
                diagnostic(phase.get(), null);
                fail("Samsung denied permission. Choose Pair and allow Nepal Home on the TV."); return;
            }
            if (!"ms.channel.connect".equals(event)) return;
            if (state == State.READY) return;
            JSONObject data = message.optJSONObject("data");
            String token = data == null ? "" : data.optString("token", "");
            if (pairing && !SamsungTvSecurity.validToken(token)) {
                phase.set(Stage.AUTH_TOKEN_MISSING);
                diagnostic(phase.get(), null);
                fail("Samsung approved this session without a reusable token. Pair again on the TV."); return;
            }
            if (!pairing && token.isEmpty()) token = target.token;
            if (!SamsungTvSettings.savePairing(context, target, token, trust.observedPin())) {
                phase.set(Stage.AUTH_STORAGE_FAILED);
                diagnostic(phase.get(), null);
                fail("Could not save the Samsung pairing. Check settings and pair again."); return;
            }
            cancelTimeout();
            connecting = false;
            phase.set(Stage.AUTH_READY);
            diagnostic(phase.get(), null);
            report(State.READY, "Samsung connected. Check the selected input on the TV itself.");
            drain();
        } catch (Exception error) {
            diagnostic(Stage.AUTH_INVALID_RESPONSE, error);
            fail("Samsung sent an invalid response.");
        }
    }

    private void drain() {
        if (draining || pending.isEmpty() || state != State.READY || socket == null) return;
        draining = true;
        long wait = Math.max(0, nextSendAt - android.os.SystemClock.elapsedRealtime());
        worker.schedule(() -> {
            draining = false;
            if (closing || state != State.READY || socket == null || pending.isEmpty()) return;
            Key key = pending.remove();
            // Enum-owned values only; no user input is interpolated into a protocol command.
            String payload = "{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\",\"DataOfCmd\":\""
                    + key.wireName + "\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}";
            if (!socket.send(payload)) { fail("Samsung key request was not sent. Open Sources to reconnect."); return; }
            nextSendAt = android.os.SystemClock.elapsedRealtime() + (key == Key.SOURCE ? 1200 : 400);
            main.post(() -> { if (!closing) listener.onSent(key); });
            drain();
        }, wait, TimeUnit.MILLISECONDS);
    }

    private void report(State value, String message) {
        state = value;
        main.post(() -> { if (!closing) listener.onState(value, message); });
    }

    private void fail(String message) {
        disconnect();
        pending.clear();
        report(State.ERROR, message);
    }

    private void cancelTimeout() {
        if (authorizationTimeout != null) authorizationTimeout.cancel(false);
        authorizationTimeout = null;
    }

    private void disconnect() {
        ++generation;
        connecting = false;
        cancelTimeout();
        if (socket != null) socket.cancel();
        socket = null;
        if (identityCall != null) identityCall.cancel();
        identityCall = null;
        cleanupTransport();
    }

    private void cleanupTransport() {
        if (transport == null) return;
        transport.dispatcher().cancelAll();
        transport.connectionPool().evictAll();
        transport.dispatcher().executorService().shutdown();
        transport = null;
    }

    private static String publicError(Throwable error) {
        boolean tlsFailure = false;
        for (Throwable item = error; item != null; item = item.getCause()) {
            String message = item.getMessage();
            if ("certificate_changed".equals(message)) {
                return "Samsung certificate changed. Verify the TV address, then use Forget pairing before pairing again.";
            }
            if (item instanceof ProtocolError) {
                if ("wrong_tv_model".equals(message)) return "The TV model did not match. Check the exact Samsung model code in setup.";
                if ("unsupported_auth".equals(message)) return "This Samsung TV did not report supported token pairing.";
                if ("identity_unavailable".equals(message)) return "Samsung's local device information was unavailable. Check TV connection settings.";
            }
            if (item instanceof SSLPeerUnverifiedException) return "Samsung certificate could not be verified. Check setup and pair again.";
            if (item instanceof SSLException) tlsFailure = true;
            if (item instanceof java.net.ConnectException) return "The stick could not reach Samsung. Check the TV address and local network.";
            if (item instanceof java.net.SocketTimeoutException) return "Samsung did not respond in time. Check that the TV is on and try again.";
        }
        if (tlsFailure) return "Samsung's secure connection failed. Reopen Sources and try pairing again.";
        // Never expose exception strings, token-bearing WebSocket URLs, or server-supplied text.
        return "Could not connect to Samsung. Check that the TV is on and both devices use the same network.";
    }

    /** Local diagnostics only: no exception messages, server text, URLs, addresses, or credentials. */
    private static void diagnostic(Stage stage, Throwable error) {
        StringBuilder line = new StringBuilder("stage=").append(stage.name());
        if (error != null) {
            Throwable root = error;
            for (int count = 0; count < 8 && root.getCause() != null && root.getCause() != root; count++) {
                root = root.getCause();
            }
            line.append(" error=").append(safeSymbol(error.getClass().getName()));
            line.append(" root=").append(safeSymbol(root.getClass().getName()));
            StackTraceElement[] frames = root.getStackTrace();
            if (frames.length > 0) {
                line.append(" frame=").append(safeSymbol(frames[0].getClassName())).append('.')
                        .append(safeSymbol(frames[0].getMethodName())).append(':').append(frames[0].getLineNumber());
            }
        }
        // Do not pass Throwable to Log: Android would print its message and complete stack trace.
        Log.i(LOG_TAG, line.toString());
    }

    private static String safeSymbol(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_.$<>]", "_");
        return safe.substring(0, Math.min(120, safe.length()));
    }

    private static final class ProtocolError extends Exception {
        ProtocolError(String message) { super(message); }
    }

    /** Gracefully drains the final Enter/Return before releasing this dialog's connection. */
    @Override public void close() {
        if (closing) return;
        closing = true;
        try {
            worker.execute(() -> {
                ++generation;
                pending.clear();
                cancelTimeout();
                if (identityCall != null) identityCall.cancel();
                if (socket != null) socket.close(1000, null);
                worker.schedule(() -> {
                    if (socket != null) socket.cancel();
                    socket = null;
                    cleanupTransport();
                    worker.shutdown();
                }, 3, TimeUnit.SECONDS);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
}
