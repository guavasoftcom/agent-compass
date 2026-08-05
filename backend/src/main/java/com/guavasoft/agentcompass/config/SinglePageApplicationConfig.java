package com.guavasoft.agentcompass.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * Serves the React frontend, wherever it happens to live, and gives it history-mode routing.
 *
 * <p>Locations come from Spring Boot's own {@code spring.web.resources.static-locations}, so a
 * plain {@code ./mvnw spring-boot:run} keeps the classpath defaults (and, with no frontend built
 * into the backend, serves nothing) while the Docker image points the property at the Vite bundle
 * it copied in next to the jar.
 *
 * <p>React Router uses history-mode URLs ({@code /traces/{traceId}}) that match no file on disk,
 * so any unresolved path falls back to {@code index.html} and lets the client-side router take
 * over. Paths owned by the server — the JSON API, the OpenAPI docs — keep returning 404 instead,
 * so a mistyped endpoint does not quietly answer with an HTML page. When no {@code index.html} is
 * present at all, every request behaves exactly as it did before this class existed.
 */
@Configuration
public class SinglePageApplicationConfig implements WebMvcConfigurer {

    private static final String SINGLE_PAGE_ENTRY_POINT = "index.html";

    /** Path prefixes handled by controllers; never rewritten to the SPA entry point. */
    private static final List<String> SERVER_ROUTED_PREFIXES = List.of(
            "api/",
            "v3/api-docs",
            "swagger-ui",
            "actuator/");

    /**
     * Same property — and same default list — Spring Boot's own static resource handling uses.
     * Read explicitly because registering a handler for {@code /**} replaces the auto-configured
     * one, which would otherwise silently drop any customization of this property.
     */
    private final String[] staticResourceLocations;

    public SinglePageApplicationConfig(
            @Value("${spring.web.resources.static-locations:"
                    + "classpath:/META-INF/resources/,classpath:/resources/,"
                    + "classpath:/static/,classpath:/public/}") String[] staticResourceLocations) {
        this.staticResourceLocations = staticResourceLocations.clone();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // resourceChain(false) — no CachingResourceResolver. The fallback below
        // resolves successfully for any unknown history-mode path, so a cache
        // would gain a permanent entry per distinct URL requested, with no
        // eviction and no bound.
        registry.addResourceHandler("/**")
                .addResourceLocations(staticResourceLocations)
                .resourceChain(false)
                .addResolver(new SinglePageEntryPointResolver());
    }

    /** Resolves real files first and rewrites unmatched history-mode routes to the SPA entry point. */
    private static final class SinglePageEntryPointResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requestedResource = super.getResource(resourcePath, location);
            if (requestedResource != null || isServerRouted(resourcePath) || looksLikeFileRequest(resourcePath)) {
                return requestedResource;
            }
            Resource entryPoint = super.getResource(SINGLE_PAGE_ENTRY_POINT, location);
            return entryPoint != null && entryPoint.exists() ? entryPoint : null;
        }

        private boolean isServerRouted(String resourcePath) {
            return SERVER_ROUTED_PREFIXES.stream().anyMatch(resourcePath::startsWith);
        }

        /**
         * A missing asset must 404 rather than fall back. Answering a stale hashed chunk request
         * (common right after an upgrade, when a loaded page requests the previous build's files)
         * with {@code index.html} would return 200 {@code text/html}, which surfaces to the browser
         * as an opaque MIME-type error on a lazily imported route instead of a plain missing file.
         * React Router's own paths carry no extension, so the last segment's dot separates the two.
         */
        private boolean looksLikeFileRequest(String resourcePath) {
            int lastSeparatorIndex = resourcePath.lastIndexOf('/');
            return resourcePath.indexOf('.', lastSeparatorIndex + 1) >= 0;
        }
    }
}
