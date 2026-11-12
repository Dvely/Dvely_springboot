package com.example.dvely.deviceshare.infrastructure.web;

import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공유 오리진({@code <토큰>.share.…})으로 온 요청을 끝까지 처리한다(설계 §3.2).
 *
 * <p>보안 체인보다 앞에서 돌고, 뷰어 소켓({@code /__qeploy/ws}) 말고는 체인으로 넘기지 않는다. 그래서 공유 오리진에서
 * {@code /api/v1/**} 컨트롤러에 닿을 길이 구조적으로 없다. API 호스트 요청은 손대지 않고 넘긴다.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ShareHostFilter extends OncePerRequestFilter {

    static final String VIEW_PATH = "/__qeploy/view";
    static final String SOCKET_PATH = "/__qeploy/ws";

    /** 뷰어는 같은 오리진 소켓만 연다. 인라인 스크립트·스타일 하나짜리 페이지다. */
    static final String VIEWER_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "img-src blob: data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final ShareHost shareHost;
    private final DeviceShareService service;
    private volatile byte[] viewerHtml;

    public ShareHostFilter(ShareHost shareHost, DeviceShareService service) {
        this.shareHost = shareHost;
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        if (!shareHost.isShareDomain(host)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> token = shareHost.tokenOf(host);
        if (token.isEmpty()) {
            plain(response, HttpServletResponse.SC_NOT_FOUND, "공유를 찾을 수 없습니다.");
            return;
        }
        String path = request.getRequestURI();
        if (SOCKET_PATH.equals(path)) {
            chain.doFilter(request, response); // 핸드셰이크 인터셉터가 토큰·상태를 다시 본다
            return;
        }
        Optional<DeviceShareLink> link = service.findByViewToken(token.get());
        boolean viewPage = VIEW_PATH.equals(path) && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
        if (link.isEmpty() || !link.get().isActive(service.now())) {
            int status = link.isEmpty() ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_GONE;
            if (viewPage) {
                viewer(request, response, status); // 뷰어가 응답 상태를 읽고 "공유가 끝났습니다" 를 띄운다
            } else {
                plain(response, status, link.isEmpty() ? "공유를 찾을 수 없습니다." : "끝났거나 만료된 공유입니다.");
            }
            return;
        }
        if ("/".equals(path)) {
            securityHeaders(response);
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", VIEW_PATH);
            return;
        }
        if (viewPage) {
            viewer(request, response, HttpServletResponse.SC_OK);
            return;
        }
        plain(response, HttpServletResponse.SC_NOT_FOUND, "없는 경로입니다.");
    }

    private void viewer(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        byte[] html = viewerHtml();
        securityHeaders(response);
        response.setStatus(status);
        response.setHeader("Content-Security-Policy", VIEWER_CSP);
        response.setContentType("text/html;charset=UTF-8");
        response.setContentLength(html.length);
        if ("GET".equals(request.getMethod())) {
            response.getOutputStream().write(html);
        }
    }

    private byte[] viewerHtml() throws IOException {
        byte[] html = viewerHtml;
        if (html == null) {
            try (InputStream in = new ClassPathResource("deviceshare/viewer.html").getInputStream()) {
                html = in.readAllBytes();
            }
            viewerHtml = html;
        }
        return html;
    }

    private static void securityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
    }

    private static void plain(HttpServletResponse response, int status, String message) throws IOException {
        securityHeaders(response);
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
    }
}
