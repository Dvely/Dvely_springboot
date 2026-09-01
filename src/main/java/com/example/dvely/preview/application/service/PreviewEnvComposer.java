package com.example.dvely.preview.application.service;

import com.example.dvely.environment.application.port.in.EnvironmentValueResolver;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 서버형 프리뷰 프로세스에 넣을 환경변수를 조립한다({@code KEY=VALUE} 리스트).
 *
 * 이 리스트는 명령 문자열이 아니라 exec 의 env 로만 전달돼야 한다(DockerContainerService.execWithEnv).
 * 그래야 DB_PASSWORD·DATABASE_URL 같은 값이 로그·예외에 안 남는다.
 *
 * 병합 우선순위:
 * <ol>
 *   <li>DB 자동값(DB_HOST 등)을 바닥에 깐다.
 *   <li>사용자 PREVIEW env 로 덮는다 — 사용자가 같은 키를 정했으면 그게 이긴다.
 *   <li>PORT=3000 을 마지막에 강제한다 — 게이트웨이가 3000 을 프록시하므로 서버는 반드시 3000 에 붙어야 한다.
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class PreviewEnvComposer {

    private final EnvironmentValueResolver environmentValueResolver;

    public List<String> compose(Long projectId, PreviewDbConnection db) {
        Map<String, String> env = new LinkedHashMap<>();

        if (db != null) {
            env.put("DB_HOST", db.host());
            env.put("DB_PORT", String.valueOf(db.port()));
            env.put("DB_NAME", db.database());
            env.put("DB_USER", db.username());
            env.put("DB_PASSWORD", db.password());
            env.put("DATABASE_URL", databaseUrl(db));
        }

        env.putAll(environmentValueResolver.resolve(projectId, EnvironmentScope.PREVIEW));

        // 게이트웨이는 3000 만 프록시한다. 사용자가 PORT 를 다른 값으로 정했더라도 서버가 3000 에
        // 붙지 않으면 프리뷰가 닿지 않으므로, PORT 는 여기서 3000 으로 강제한다.
        env.put("PORT", "3000");

        List<String> pairs = new ArrayList<>(env.size());
        env.forEach((k, v) -> pairs.add(k + "=" + (v == null ? "" : v)));
        return pairs;
    }

    private String databaseUrl(PreviewDbConnection db) {
        String scheme = "MYSQL".equals(db.engine()) ? "mysql" : "postgresql";
        return scheme + "://" + db.username() + ":" + db.password()
                + "@" + db.host() + ":" + db.port() + "/" + db.database();
    }
}
