package com.dt.digitaltwinsimulator.embedded;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Real RFC6455 client with explicit STOMP framing, including binary content-length. */
final class StompWebSocketClient implements AutoCloseable, WebSocket.Listener {
    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private WebSocket socket;
    private volatile Throwable failure;

    StompWebSocketClient(int port) throws Exception {
        this(port, "artemis", "artemis");
    }

    StompWebSocketClient(int port, String user, String password) throws Exception {
        socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port), this).get(5, TimeUnit.SECONDS);
        text("CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:" + password + "\nheart-beat:0,0\n\n");
    }

    void connected() throws Exception { await("CONNECTED"); }

    void subscribe(String topic) throws Exception {
        text("SUBSCRIBE\nid:sub-1\ndestination:" + topic + "\nack:auto\nreceipt:subscribed\n\n");
        await("RECEIPT");
    }

    void sendText(String topic, String body) throws Exception {
        text("SEND\ndestination:" + topic + "\nreceipt:sent\n\n" + body);
        await("RECEIPT");
    }

    void sendBytes(String topic, byte[] body) throws Exception {
        byte[] header = ("SEND\ndestination:" + topic + "\nreceipt:sent\ncontent-length:" + body.length + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
        var frame = ByteBuffer.allocate(header.length + body.length + 1).put(header).put(body).put((byte)0);
        frame.flip();
        socket.sendBinary(frame, true).get(10, TimeUnit.SECONDS);
        await("RECEIPT");
    }

    private void text(String frame) throws Exception { socket.sendText(frame + '\0', true).get(5, TimeUnit.SECONDS); }

    String await(String command) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String frame = frames.poll(100, TimeUnit.MILLISECONDS);
            if (failure != null) throw new AssertionError("WebSocket failure", failure);
            if (frame == null) continue;
            if (frame.startsWith("ERROR")) throw new AssertionError("STOMP ERROR: " + frame);
            if (frame.startsWith(command + "\n")) return frame;
        }
        throw new AssertionError("Timed out waiting for " + command);
    }

    static Map<String, String> headers(String frame) {
        var result = new LinkedHashMap<String, String>();
        for (String line : frame.substring(0, frame.indexOf("\n\n")).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) result.put(line.substring(0, colon), line.substring(colon + 1));
        }
        return result;
    }

    static String body(String frame) { return frame.substring(frame.indexOf("\n\n") + 2); }

    private synchronized void accept(byte[] bytes) {
        pending.writeBytes(bytes);
        byte[] all = pending.toByteArray();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == 0) {
                String frame = new String(all, start, i - start, StandardCharsets.UTF_8).stripLeading();
                if (!frame.isBlank()) frames.add(frame);
                start = i + 1;
            }
        }
        pending.reset(); pending.write(all, start, all.length - start);
    }

    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        accept(data.toString().getBytes(StandardCharsets.UTF_8)); socket.request(1); return null;
    }
    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()]; data.get(bytes); accept(bytes); socket.request(1); return null;
    }
    @Override public void onError(WebSocket socket, Throwable error) { failure = error; }
    @Override public void close() {
        if (socket == null) return;
        try { text("DISCONNECT\nreceipt:bye\n\n"); await("RECEIPT"); socket.sendClose(1000, "done").get(2, TimeUnit.SECONDS); }
        catch (Exception | AssertionError ignored) { socket.abort(); }
    }
}

