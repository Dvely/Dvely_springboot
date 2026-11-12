package com.example.dvely.deviceshare;

import static com.example.dvely.support.JdbcRows.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.deviceshare.infrastructure.web.ShareHostFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 공유 링크 API 와 공유 오리진 분기(#652)를 보안 체인·실 MySQL 까지 본다.
 */
@SpringBootTest
class DeviceShareEndpointTest {

    private static final String MARK = "ds652-";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ShareHostFilter shareHostFilter;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(shareHostFilter, springSecurityFilterChain).build();
        userId = insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        bearer = "Bearer " + jwtProvider.createToken(userId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from device_share_links where user_id = ?", userId);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    @Test
    void 만들면_보기_링크_제어_링크_터널_주소가_나오고_DB에는_해시만_남는다() throws Exception {
        JsonNode data = create(30);
        String viewUrl = data.path("viewUrl").asText();
        String controlUrl = data.path("controlUrl").asText();
        assertThat(viewUrl).matches("http://[a-z2-7]{26}\\.share\\.localhost:8080/");
        assertThat(controlUrl).startsWith(viewUrl + "#c=");
        assertThat(data.path("tunnelUrl").asText())
                .matches("ws://localhost(:\\d+)?/api/v1/device-shares/" + data.path("shareId").asLong() + "/tunnel\\?v=1");

        String token = URI.create(viewUrl).getHost().split("\\.")[0];
        String secret = controlUrl.substring(controlUrl.indexOf("#c=") + 3);
        String row = jdbc.queryForObject(
                "select concat(token_hash, '|', control_secret_hash, '|', token_prefix) from device_share_links where share_id = ?",
                String.class, data.path("shareId").asLong());
        assertThat(row).doesNotContain(token).doesNotContain(secret);
        assertThat(row).endsWith("|" + token.substring(0, 6));
    }

    @Test
    void 목록에는_상태가_보이고_회수하면_공유_오리진이_410() throws Exception {
        JsonNode data = create(null);
        String host = URI.create(data.path("viewUrl").asText()).getHost();

        mvc.perform(get("/api/v1/device-shares").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].deviceConnected").value(false));

        mvc.perform(shareHost(get("/"), host))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/__qeploy/view"));

        mvc.perform(delete("/api/v1/device-shares/" + data.path("shareId").asLong()).header("Authorization", bearer))
                .andExpect(status().isNoContent());

        mvc.perform(shareHost(get("/__qeploy/view"), host)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/device-shares").header("Authorization", bearer))
                .andExpect(jsonPath("$.data[0].status").value("REVOKED"));
    }

    @Test
    void 공유_오리진에서는_API_컨트롤러에_닿지_않는다() throws Exception {
        JsonNode data = create(null);
        String host = URI.create(data.path("viewUrl").asText()).getHost();

        mvc.perform(shareHost(get("/api/v1/device-shares"), host).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(shareHost(get("/api/v1/projects"), host).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(shareHost(get("/__qeploy/view"), "abc.share.localhost")).andExpect(status().isNotFound());
        mvc.perform(shareHost(get("/__qeploy/view"), "aaaaaaaaaaaaaaaaaaaaaaaaaa.share.localhost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 뷰어_페이지는_엄격한_CSP와_함께_나간다() throws Exception {
        JsonNode data = create(null);
        String host = URI.create(data.path("viewUrl").asText()).getHost();
        mvc.perform(shareHost(get("/__qeploy/view"), host))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("connect-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void API_호스트의_뷰어_경로는_공유_필터를_타지_않는다() throws Exception {
        mvc.perform(get("/__qeploy/view").header("Authorization", bearer)).andExpect(status().isNotFound());
    }

    @Test
    void 사용자당_살아_있는_링크는_5개까지() throws Exception {
        for (int i = 0; i < 5; i++) {
            create(5);
        }
        mvc.perform(post("/api/v1/device-shares").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEB\",\"targetLabel\":\"http://localhost:5173\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEVICE_SHARE_LIMIT_EXCEEDED"));
    }

    @Test
    void 잘못된_요청은_400() throws Exception {
        mvc.perform(post("/api/v1/device-shares").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEB\",\"targetLabel\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/device-shares").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEB\",\"targetLabel\":\"http://localhost:5173\",\"ttlMinutes\":5000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/device-shares").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ANDROID\",\"targetLabel\":\"emulator-5554\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 남의_공유는_회수할_수_없다() throws Exception {
        JsonNode data = create(null);
        long other = insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)", MARK + "o" + System.nanoTime(), MARK + "o");
        try {
            mvc.perform(delete("/api/v1/device-shares/" + data.path("shareId").asLong())
                            .header("Authorization", "Bearer " + jwtProvider.createToken(other)))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("delete from users where user_id = ?", other);
        }
    }

    private JsonNode create(Integer ttl) throws Exception {
        String body = "{\"kind\":\"WEB\",\"targetLabel\":\"http://localhost:5173\"" + (ttl == null ? "" : ",\"ttlMinutes\":" + ttl) + "}";
        String response = mvc.perform(post("/api/v1/device-shares").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }

    private static MockHttpServletRequestBuilder shareHost(MockHttpServletRequestBuilder builder, String host) {
        return builder.with(request -> {
            request.setServerName(host);
            return request;
        });
    }
}
