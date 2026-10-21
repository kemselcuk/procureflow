package com.procureflow.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds JSON request bodies before any deserialization, on every port the application serves.
 *
 * <p>A declared {@code Content-Length} above the limit is refused without reading the body. A
 * chunked request (no declared length) is read up to {@code limit + 1} bytes, so an oversized
 * stream is refused before Spring MVC can build a request object from it. Requests at or below
 * the limit are passed through unchanged.
 *
 * <p>Tomcat's {@code maxPostSize}/{@code maxSwallowSize} are deliberately not used: they do not
 * classify JSON bodies reliably and {@code maxSwallowSize} only controls connection reuse. The
 * filter is registered after the security filter chain, so an unauthenticated oversized request
 * still receives 401 before any body byte is read.
 */
public class JsonRequestBodyLimitFilter extends OncePerRequestFilter {
    private static final String JSON_MEDIA_TYPE = "application/json";

    private final int maxBytes;

    public JsonRequestBodyLimitFilter(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isJsonBody(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            reject(response);
            return;
        }
        byte[] body = readAtMost(request.getInputStream());
        if (body == null) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedJsonBodyRequest(request, body), response);
    }

    private static boolean isJsonBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return mediaType.equalsIgnoreCase(JSON_MEDIA_TYPE);
    }

    /** Returns the complete body, or {@code null} as soon as the limit is exceeded. */
    private byte[] readAtMost(ServletInputStream input) throws IOException {
        var buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (buffer.size() + read > maxBytes) return null;
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType("application/problem+json");
        // The remainder of an oversized body is never read; closing keeps the connection state
        // unambiguous instead of relying on a container swallow limit.
        response.setHeader("Connection", "close");
        response.getWriter().write("{\"status\":413,\"title\":\"Payload Too Large\",\"code\":\"payload_too_large\"}");
    }

    private static final class CachedJsonBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedJsonBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return stream.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return stream.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("This application does not use asynchronous request reads");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            if (encoding == null) return StandardCharsets.UTF_8;
            try {
                return Charset.forName(encoding);
            } catch (RuntimeException unsupported) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
