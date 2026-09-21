package com.aura.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint a service publishes is reachable through the gateway.
 *
 * <p>This exists because it was not true. search-service has offered {@code POST
 * /api/control/search/reindex} since it was written, and the gateway had no route for it — so the
 * only way to rebuild the search index was unreachable, and had been all along. Nothing failed
 * loudly: {@code /api/control/**} clears the audience check in {@link
 * com.aura.gateway.config.GatewaySecurityConfig} and then matches no route, so an authorised admin
 * gets a 404 that reads like a missing endpoint rather than a missing line of configuration.
 *
 * <p>The check is deliberately made against the <em>services' own source</em> rather than a list of
 * expected route ids kept here. A hardcoded list is a second copy of the routing table: it passes
 * whenever it agrees with the gateway, including when both are missing the same route. Reading the
 * controllers is what makes this able to fail for a new endpoint nobody remembered to route.
 */
class RouteCoverageTest {

    /**
     * Absolute request paths as written in a mapping annotation.
     *
     * <p>Only paths starting with {@code /api} are of interest, which conveniently excludes
     * method-level fragments like {@code "/{userId}"} — those are relative to a class-level prefix
     * that is itself matched, so the prefix alone settles reachability.
     */
    private static final Pattern MAPPING = Pattern.compile(
        "@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\(\\s*(?:value\\s*=\\s*)?\"(/api/[^\"]*)\"");

    private static final Pattern ROUTE_PATH = Pattern.compile("^Path=(.+)$");

    /**
     * Service-to-service endpoints, published on the service port and never at the edge.
     *
     * <p>Their unreachability through the gateway is the point: {@code /api/internal/catalog/stock}
     * is how order-service reserves stock, and routing it would put inventory writes one
     * unauthenticated request away from the open internet.
     */
    private static final String INTERNAL = "/api/internal/";

    @Test
    @DisplayName("every /api endpoint a service publishes has a gateway route")
    void everyPublishedEndpointIsRouted() throws IOException {
        List<String> routes = gatewayRoutePatterns();
        // A guard on the reader itself. If the YAML structure moves again — as it did when Spring
        // Cloud Gateway 5 inserted `server.webflux` and silently ignored the old path — an empty
        // list would otherwise make every assertion below vacuously true.
        assertThat(routes)
            .describedAs("route patterns read from application.yml")
            .isNotEmpty();

        AntPathMatcher matcher = new AntPathMatcher();
        Map<String, String> unroutable = new TreeMap<>();

        publishedEndpoints().forEach((endpoint, source) -> {
            if (endpoint.startsWith(INTERNAL)) return;
            boolean routed = routes.stream().anyMatch(pattern -> matcher.match(pattern, endpoint));
            if (!routed) unroutable.put(endpoint, source);
        });

        assertThat(unroutable)
            .describedAs("endpoints with no gateway route (declared in the service, unreachable at "
                + "the edge). Add a route to gateway-service's application.yml, or move the "
                + "endpoint under %s if it is service-to-service.", INTERNAL)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan actually finds controllers")
    void theScanFindsSomething() throws IOException {
        // Without this, a wrong module path or a change in annotation style turns the test above
        // into one that passes by finding nothing at all.
        assertThat(publishedEndpoints())
            .describedAs("endpoints discovered across sibling service modules")
            .hasSizeGreaterThan(20);
    }

    /** {@code Path=} predicates from the shipped configuration, not a copy of them. */
    private List<String> gatewayRoutePatterns() throws IOException {
        Map<String, Object> yaml;
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            yaml = new Yaml().load(in);
        }

        List<String> patterns = new ArrayList<>();
        for (Map<String, Object> route : routeList(yaml)) {
            Object predicates = route.get("predicates");
            if (!(predicates instanceof List<?> list)) continue;

            for (Object predicate : list) {
                Matcher matcher = ROUTE_PATH.matcher(String.valueOf(predicate));
                if (matcher.matches()) patterns.add(matcher.group(1).trim());
            }
        }
        return patterns;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routeList(Map<String, Object> yaml) {
        Object node = yaml;
        for (String key : List.of("spring", "cloud", "gateway", "server", "webflux", "routes")) {
            if (!(node instanceof Map<?, ?> map)) return List.of();
            node = ((Map<String, Object>) map).get(key);
        }
        return node instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** Endpoint path to the file that declares it, so a failure names the culprit. */
    private Map<String, String> publishedEndpoints() throws IOException {
        Map<String, String> endpoints = new LinkedHashMap<>();

        try (Stream<Path> modules = Files.list(Path.of("..").toAbsolutePath().normalize())) {
            List<Path> sources = modules
                .map(module -> module.resolve("src/main/java"))
                .filter(Files::isDirectory)
                .toList();

            for (Path source : sources) {
                try (Stream<Path> files = Files.walk(source)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Matcher matcher = MAPPING.matcher(Files.readString(file));
                        while (matcher.find()) {
                            endpoints.putIfAbsent(matcher.group(1), file.getFileName().toString());
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    @Test
    @DisplayName("internal endpoints stay off the edge")
    void internalEndpointsAreNotRouted() throws IOException {
        AntPathMatcher matcher = new AntPathMatcher();
        List<String> routes = gatewayRoutePatterns();

        Set<String> exposed = publishedEndpoints().keySet().stream()
            .filter(endpoint -> endpoint.startsWith(INTERNAL))
            .filter(endpoint -> routes.stream().anyMatch(p -> matcher.match(p, endpoint)))
            .collect(java.util.stream.Collectors.toSet());

        // The other half of the exclusion above: skipping these when checking coverage is only
        // safe while nothing routes them. A wildcard broad enough to catch /api/internal/** would
        // otherwise publish stock and variant writes to the internet, and this test would be the
        // reason nobody noticed.
        assertThat(exposed)
            .describedAs("service-to-service endpoints reachable through the gateway")
            .isEmpty();
    }
}
