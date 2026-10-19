package com.procureflow.worker.email;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Isolated local SMTP fault fixture: a real TCP SMTP conversation on loopback that is deliberately
 * NOT Mailpit, used only to observe permanent (5xx) and transient (4xx) reply classification. It
 * accepts one connection and can also stay silent to exercise the bounded read timeout.
 */
final class FaultySmtpServer implements AutoCloseable {
    enum Behavior { REJECT_PERMANENT, REJECT_TRANSIENT, SILENT }

    private final ServerSocket server;
    private final Behavior behavior;
    private final Thread thread;

    FaultySmtpServer(Behavior behavior) throws IOException {
        this.behavior = behavior;
        this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        this.thread = Thread.ofVirtual().name("faulty-smtp").start(this::serve);
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
    }

    private void serve() {
        try (Socket connection = server.accept()) {
            if (behavior == Behavior.SILENT) {
                // Hold the socket without a greeting so the client's read timeout is the outcome.
                connection.setSoTimeout(30_000);
                connection.getInputStream().read();
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.US_ASCII));
            write(writer, "220 faulty.test ESMTP");
            boolean data = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (data) {
                    if (".".equals(line.strip())) {
                        data = false;
                        write(writer, "250 2.0.0 accepted");
                    }
                    continue;
                }
                String command = line.split(" ", 2)[0].toUpperCase(Locale.ROOT);
                switch (command) {
                    case "EHLO", "HELO" -> {
                        writer.write("250-faulty.test\r\n250 OK\r\n");
                        writer.flush();
                    }
                    case "MAIL" -> write(writer, "250 2.1.0 sender ok");
                    case "RCPT" -> write(writer, behavior == Behavior.REJECT_PERMANENT
                            ? "550 5.1.1 ServerReplyMarkerCapturedOnlyByTheTest"
                            : "451 4.3.0 ServerReplyMarkerCapturedOnlyByTheTest");
                    case "DATA" -> {
                        data = true;
                        write(writer, "354 end with .");
                    }
                    case "QUIT" -> {
                        write(writer, "221 bye");
                        return;
                    }
                    default -> write(writer, "250 OK");
                }
            }
        } catch (IOException ignored) {
            // The test owns the lifecycle; a closed socket simply ends the conversation.
        }
    }

    private static void write(BufferedWriter writer, String response) throws IOException {
        writer.write(response + "\r\n");
        writer.flush();
    }
}
