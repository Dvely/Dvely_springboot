package com.example.dvely.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger UI configuration.
 *
 * This class only affects documentation rendering — it never touches request handling, so
 * changes here carry zero runtime-behavior risk. Two responsibilities:
 *   1. {@link #swagger()}: global {@link OpenAPI} metadata (title/description/JWT scheme) shown
 *      in the Swagger UI header, regardless of which domain group is selected.
 *   2. The {@code GroupedOpenApi} beans below: split the 90-endpoint, 14-controller surface into
 *      per-domain tabs in the Swagger UI dropdown so a frontend developer working on, say, the
 *      Environment screen doesn't have to scroll past Deployment/DomainBinding operations to find
 *      what they need.
 *
 * Grouping strategy: {@code packagesToScan} (not {@code pathsToMatch}) is used because it is an
 * exact, non-overlapping partition of the controllers — every controller lives in exactly one
 * {@code <domain>/presentation} package, so a package-based group can never accidentally include
 * another domain's endpoint (which a path-prefix pattern could, e.g. chat's
 * {@code /api/v1/projects/{id}/conversations} nests under the project path prefix). Approval and
 * Change are project sub-resource lifecycles with no dedicated FE screen of their own, so their
 * packages are folded into the "project" group instead of adding two more dropdown entries.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI swagger() {
        Info info = new Info()
                .title("Qeploy API")
                .version("0.0.1")
                .description("""
                        Qeploy backend API — natural-language driven web app generation, GitHub Pages/BYOC \
                        deployment, domain binding, and cloud infrastructure operation.

                        ### Common response envelope
                        Every response (except SSE, redirects, GitHub webhook callback, and the preview proxy) \
                        is wrapped as:
                        ```json
                        { "status": 200, "code": "SUCCESS", "message": "...", "data": { } }
                        ```
                        `data` is omitted (not `null`) on responses with no body (e.g. 204). Frontend clients \
                        should unwrap `data` after checking `response.ok`; see `docs/FRONTEND_API_GUIDE.md` \
                        for the reference `http.ts` implementation.

                        ### Error codes
                        Errors use the same envelope with `data` omitted. `code` is one of: \
                        `BAD_REQUEST`(400) · `UNAUTHORIZED`(401) · `INVALID_TOKEN`(401) · \
                        `EXPIRED_REFRESH_TOKEN`(401) · `REVOKED_REFRESH_TOKEN`(401) · `FORBIDDEN`(403) · \
                        `GITHUB_APP_NOT_INSTALLED`(403) · `NOT_FOUND`(404) · `METHOD_NOT_ALLOWED`(405) · \
                        `CONFLICT`(409) · `INTERNAL_SERVER_ERROR`(500). `message` carries the specific, \
                        human-readable detail — treat `code` as the stable machine-readable discriminator.

                        ### Authentication
                        Bearer JWT (see the **JWT TOKEN** security scheme below). Obtain a token via the \
                        GitHub OAuth flow (`Auth` group), then send `Authorization: Bearer {accessToken}` on \
                        every authenticated request. Access tokens expire in 1 hour; use \
                        `POST /api/v1/auth/refresh` (rotation — both tokens are replaced) before that. \
                        A small set of endpoints are unauthenticated by design: GitHub OAuth/App URL and \
                        callback endpoints, `POST /api/v1/auth/refresh`, and `POST /api/v1/webhook/github` \
                        (GitHub-signed, not JWT).
                        """);

        // "JWT TOKEN" name kept as-is (pre-existing scheme id) so any client/collection already
        // referencing this security scheme name by string continues to resolve.
        String securityScheme = "JWT TOKEN";
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(securityScheme);

        Components components = new Components()
                .addSecuritySchemes(securityScheme, new SecurityScheme()
                        .name(securityScheme)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("Bearer")
                        .bearerFormat("JWT"));

        return new OpenAPI()
                .info(info)
                .addServersItem(new Server().url("/"))
                .addSecurityItem(securityRequirement)
                .components(components);
    }

    @Bean
    public GroupedOpenApi authGroup() {
        return group("auth", "com.example.dvely.auth.presentation");
    }

    @Bean
    public GroupedOpenApi projectGroup() {
        // Approval/Change have no dedicated FE screen — grouped with their parent domain (see class javadoc).
        return group("project",
                "com.example.dvely.project.presentation",
                "com.example.dvely.approval.presentation",
                "com.example.dvely.change.presentation");
    }

    @Bean
    public GroupedOpenApi chatGroup() {
        return group("chat", "com.example.dvely.chat.presentation");
    }

    @Bean
    public GroupedOpenApi agentGroup() {
        return group("agent", "com.example.dvely.agent.presentation");
    }

    @Bean
    public GroupedOpenApi deploymentGroup() {
        return group("deployment", "com.example.dvely.deployment.presentation");
    }

    @Bean
    public GroupedOpenApi domainBindingGroup() {
        return group("domainbinding", "com.example.dvely.domainbinding.presentation");
    }

    @Bean
    public GroupedOpenApi cloudConnectionGroup() {
        return group("cloudconnection", "com.example.dvely.cloudconnection.presentation");
    }

    @Bean
    public GroupedOpenApi previewGroup() {
        return group("preview", "com.example.dvely.preview.presentation");
    }

    @Bean
    public GroupedOpenApi environmentGroup() {
        return group("environment", "com.example.dvely.environment.presentation");
    }

    @Bean
    public GroupedOpenApi webhookGroup() {
        return group("webhook", "com.example.dvely.webhook.presentation");
    }

    @Bean
    public GroupedOpenApi userGroup() {
        return group("user", "com.example.dvely.user.presentation");
    }

    private static GroupedOpenApi group(String name, String... packagesToScan) {
        return GroupedOpenApi.builder()
                .group(name)
                .packagesToScan(packagesToScan)
                .build();
    }
}
