package com.example.dvely.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 하나에 ID 를 붙여 그 요청이 남긴 로그를 나중에 한 줄로 꿸 수 있게 한다.
 *
 * <p>지금은 요청 상관관계 ID 가 없어서, 동시에 들어온 요청들의 로그가 뒤섞이면 어느 줄이 어느 요청의
 * 것인지 되짚을 방법이 없다. 특히 프리뷰·배포처럼 한 요청이 여러 단계를 거치며 로그를 흩뿌리는 경로에서
 * 장애를 재구성하기 어렵다.</p>
 *
 * <p>필터 순서를 가장 앞으로 둔 이유는 보안 필터 체인이 남기는 로그(인증 거부 등)까지 같은 ID 를
 * 달아야 하기 때문이다 — 인증에서 끊긴 요청이야말로 추적이 필요한 요청이다.</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /**
     * 클라이언트가 보낸 값을 그대로 쓰지 않고 이 모양일 때만 받는다.
     *
     * <p>MDC 값은 로그 한 줄에 그대로 박히므로, 개행이 섞인 값을 받으면 로그 줄을 통째로 위조할 수 있다
     * (로그 인젝션). 길이 제한이 없으면 한 요청으로 로그를 부풀릴 수도 있다. 모양이 어긋나면 조용히
     * 새로 만든다 — 거절해서 요청을 죽일 만한 사안이 아니고, 그 값을 로그에 남겨 경위를 설명하는 것은
     * 막으려는 바로 그 인젝션을 허용하는 셈이 된다.</p>
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));

        MDC.put(MDC_KEY, requestId);
        // 응답에도 실어 보낸다. 사용자가 "이 요청이 실패했다"고 가져온 ID 로 서버 로그를 바로 집을 수 있다.
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드는 풀로 돌아가 다음 요청을 받는다. 지우지 않으면 다음 요청 로그에 남의 ID 가 붙는다.
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String inbound) {
        if (inbound != null && ACCEPTABLE.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
