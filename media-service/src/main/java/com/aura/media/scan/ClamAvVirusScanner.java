package com.aura.media.scan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Streams content to a clamd daemon over its INSTREAM protocol.
 *
 * <p>INSTREAM rather than SCAN-by-path because clamd would otherwise need to see the same
 * filesystem as this service — which it does not, since the bytes live in object storage.
 *
 * <p>The wire protocol: send {@code zINSTREAM\0}, then a sequence of length-prefixed chunks
 * (4-byte big-endian length, then that many bytes), then a zero length to signal the end. clamd
 * replies with one line, {@code stream: OK} or {@code stream: <signature> FOUND}.
 */
@Slf4j
@RequiredArgsConstructor
public class ClamAvVirusScanner implements VirusScanner {

    private static final int CHUNK_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** Generous: a large video takes real time to scan, and a false timeout means a false reject. */
    private static final int READ_TIMEOUT_MS = 120_000;

    private final String host;
    private final int port;

    @Override
    public ScanResult scan(InputStream content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 InputStream in = new BufferedInputStream(socket.getInputStream());
                 InputStream source = new BufferedInputStream(content)) {

                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    out.writeInt(read);
                    out.write(buffer, 0, read);
                }
                // Zero-length chunk terminates the stream.
                out.writeInt(0);
                out.flush();

                String reply = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
                return interpret(reply);
            }
        } catch (IOException e) {
            // A scanner that cannot be reached must not silently pass files. Throwing marks the
            // file FAILED (retryable) rather than READY, so nothing becomes servable unscanned.
            throw new ScanUnavailableException("Could not reach the virus scanner", e);
        }
    }

    private ScanResult interpret(String reply) {
        if (reply.endsWith("OK")) {
            return ScanResult.clean();
        }
        if (reply.contains("FOUND")) {
            // "stream: Eicar-Test-Signature FOUND" -> "Eicar-Test-Signature"
            String signature = reply.replace("stream:", "").replace("FOUND", "").trim();
            return ScanResult.infected(signature);
        }
        throw new ScanUnavailableException("Unexpected reply from virus scanner: " + reply, null);
    }
}
