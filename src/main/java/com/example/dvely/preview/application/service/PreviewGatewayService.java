package com.example.dvely.preview.application.service;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class PreviewGatewayService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ResponseEntity<byte[]> proxy(PreviewSessionInfo session,
                                        String gatewayPrefix,
                                        String path,
                                        String query) {
        try {
            String safePath = sanitizePath(path);
            String target = "http://127.0.0.1:" + session.hostPort() + "/" + safePath;
            if (query != null && !query.isBlank()) {
                target += "?" + query;
            }
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(target)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            String contentType = response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            byte[] body = response.body();
            if (contentType.contains(MediaType.TEXT_HTML_VALUE)) {
                body = rewriteHtml(body, gatewayPrefix);
            }
            return ResponseEntity.status(response.statusCode())
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private byte[] rewriteHtml(byte[] body, String gatewayPrefix) {
        String html = new String(body, StandardCharsets.UTF_8)
                .replace("src=\"/", "src=\"" + gatewayPrefix)
                .replace("href=\"/", "href=\"" + gatewayPrefix)
                .replace("src='/", "src='" + gatewayPrefix)
                .replace("href='/", "href='" + gatewayPrefix);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid preview path");
        }
        return normalized;
    }
}
