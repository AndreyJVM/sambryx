package sambryx.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BrowserState {
    record Call(String method, String path, URI url, JsonNode body, Map<String, String> headers) {}

    static final ObjectMapper JSON = new ObjectMapper();
    boolean authenticated = true, renewalDenied, loginFailure, loginRejected, failShares, failDashboard, failConfig;
    long sessionRemaining = 15 * 60_000;
    String workgroup = "TEAM";
    final List<Call> requests = new ArrayList<>();
    final List<String> unexpected = new ArrayList<>();
    final List<String> browserErrors = new ArrayList<>();
    JsonNode shares = json(
            """
            [{"name":"team","path":"/srv/samba/team","comment":"Project documents","readOnly":false,"guestOk":false,"browseable":true},
             {"name":"archive","path":"/srv/samba/archive","comment":"Past reports","readOnly":true,"guestOk":false,"browseable":true}]
            """);
    JsonNode backups = json("[]");
    final JsonNode users = json(
            "[{\"username\":\"alice\",\"fullName\":\"Alice Example\"},{\"username\":\"bob\",\"fullName\":\"Bob Example\"}]");
    Restore restore;
    Route pendingRestore;
    final String origin;

    static final class Restore {
        final String filename;
        boolean paused, abort, failRefresh;
        int status;
        String message = "";

        Restore(String filename) {
            this.filename = filename;
        }
    }

    BrowserState(String origin) {
        this.origin = origin;
    }

    void route(Route route) {
        Request request = route.request();
        URI url = URI.create(request.url());
        if (!request.url().startsWith(origin + "/")) {
            unexpected.add("External request: " + url.getHost());
            route.abort("blockedbyclient");
            return;
        }
        String path = url.getPath();
        if (!path.startsWith("/api/")) {
            route.resume();
            return;
        }
        String method = request.method();
        requests.add(new Call(
                method, path, url, request.postData() == null ? null : json(request.postData()), request.headers()));
        if (path.equals("/api/auth/environment") && method.equals("GET")) {
            route.fulfill(new Route.FulfillOptions()
                    .setContentType("application/json")
                    .setHeaders(Map.of("set-cookie", "XSRF-TOKEN=browser-fixture; Path=/; SameSite=Lax"))
                    .setBody("{\"success\":true,\"data\":{}}"));
            return;
        }
        if (path.equals("/api/auth/login") && method.equals("POST")) {
            if (loginRejected) {
                reply(
                        route,
                        null,
                        401,
                        "Authentication failed: SSH connection failed. Check the connection details and credentials.");
                return;
            }
            if (loginFailure) {
                reply(
                        route,
                        Map.of("code", "SSH_HOST_KEY_CHANGED", "fingerprint", "SHA256:synthetic-browser-fixture"),
                        409,
                        "Server identity changed");
                return;
            }
            authenticated = true;
            reply(route, null);
            return;
        }
        if (path.equals("/api/auth/me") && method.equals("GET")) {
            reply(
                    route,
                    authenticated ? Map.of("host", "files.example.test", "user", "operator") : null,
                    authenticated ? 200 : 401,
                    "Not authenticated");
            return;
        }
        if (path.equals("/api/auth/session") && method.equals("GET")) {
            session(route);
            return;
        }
        if (path.equals("/api/auth/activity") && method.equals("POST")) {
            if (!authenticated || renewalDenied) {
                reply(route, null, 401, "");
                return;
            }
            sessionRemaining = 15 * 60_000;
            session(route);
            return;
        }
        if (path.equals("/api/auth/logout") && method.equals("POST")) {
            authenticated = false;
            reply(route, null);
            return;
        }
        if (method.equals("GET")) {
            switch (path) {
                case "/api/shares" -> {
                    reply(route, shares, failShares ? 503 : 200, "Fixture unavailable");
                    return;
                }
                case "/api/users" -> {
                    reply(route, users);
                    return;
                }
                case "/api/users/system" -> {
                    reply(route, List.of("alice", "bob"));
                    return;
                }
                case "/api/groups" -> {
                    reply(route, List.of(Map.of("name", "engineering", "members", List.of("alice", "bob"))));
                    return;
                }
                case "/api/monitoring/dashboard" -> {
                    reply(
                            route,
                            Map.of(
                                    "isRunning",
                                    true,
                                    "connections",
                                    List.of(Map.of("pid", "100", "user", "alice", "machine", "192.0.2.10")),
                                    "openFiles",
                                    List.of()),
                            failDashboard ? 503 : 200,
                            "Fixture unavailable");
                    return;
                }
                case "/api/fs/disk-usage" -> {
                    reply(
                            route,
                            Map.of(
                                    "total",
                                    "200 GB",
                                    "used",
                                    "84 GB",
                                    "available",
                                    "116 GB",
                                    "usePercent",
                                    42,
                                    "mountPoint",
                                    "/"));
                    return;
                }
                case "/api/fs/browse" -> {
                    String current = url.getRawQuery() == null
                            ? "/"
                            : URLDecoder.decode(url.getRawQuery().replaceFirst("^path=", ""), StandardCharsets.UTF_8);
                    var data = JSON.createObjectNode().put("currentPath", current);
                    if (current.equals("/")) data.putNull("parentPath");
                    else data.put("parentPath", "/");
                    data.set(
                            "directories",
                            json("[{\"name\":\"team\",\"fullPath\":\"/srv/samba/team\",\"type\":\"dir\"}]"));
                    reply(route, data);
                    return;
                }
                case "/api/config/global" -> {
                    var data = json(
                            """
                            {"workgroup":"TEAM","netbiosName":"FILES","serverString":"Fixture","security":"user","mapToGuest":"never","interfaces":"","bindInterfacesOnly":false,"minProtocol":"SMB2","maxProtocol":"SMB3","disableNetbios":true,"loadPrinters":false}
                            """);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("workgroup", workgroup);
                    reply(route, data, failConfig ? 503 : 200, "synthetic-sensitive-refresh-output");
                    return;
                }
                case "/api/config/backups" -> {
                    reply(route, backups);
                    return;
                }
                case "/api/ad/status" -> {
                    reply(
                            route,
                            Map.of(
                                    "isJoined",
                                    false,
                                    "domainName",
                                    "",
                                    "dnsReachable",
                                    true,
                                    "kerberosWorking",
                                    false,
                                    "winbindWorking",
                                    true));
                    return;
                }
                case "/api/fstec/check" -> {
                    reply(
                            route,
                            Map.of(
                                    "checkedAt",
                                    "2026-01-01T12:00:00Z",
                                    "compliancePercent",
                                    100,
                                    "okCount",
                                    0,
                                    "failedCount",
                                    0,
                                    "manualCount",
                                    0,
                                    "notApplicableCount",
                                    0,
                                    "rules",
                                    List.of()));
                    return;
                }
            }
        }
        if (method.equals("POST")
                && restore != null
                && path.equals("/api/config/backups/" + restore.filename + "/restore")) {
            if (restore.paused) pendingRestore = route;
            else completeRestore(route);
            return;
        }
        unexpected.add(method + " " + path);
        reply(route, null, 501, "Unexpected API request in browser fixture");
    }

    void finishRestore() {
        if (pendingRestore != null) {
            Route route = pendingRestore;
            pendingRestore = null;
            completeRestore(route);
        }
    }

    private void completeRestore(Route route) {
        if (restore.abort) {
            route.abort("failed");
            return;
        }
        if (restore.failRefresh) failConfig = true;
        if (restore.status != 0) {
            reply(route, null, restore.status, restore.message);
            return;
        }
        workgroup = "RESTORED";
        ((com.fasterxml.jackson.databind.node.ArrayNode) backups)
                .add(
                        json(
                                "{\"filename\":\"smb.conf.backup_20261025_140000\",\"createdAt\":\"2026-10-25 14:00:00\",\"size\":\"2 KB\"}"));
        reply(route, null);
    }

    private void session(Route route) {
        long now = System.currentTimeMillis();
        reply(route, Map.of("serverTime", now, "deadline", now + sessionRemaining), authenticated ? 200 : 401, "");
    }

    long count(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    List<Call> mutations() {
        return requests.stream()
                .filter(r -> !List.of("GET", "HEAD").contains(r.method())
                        && !r.path().equals("/api/auth/activity"))
                .toList();
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid synthetic JSON fixture", error);
        }
    }

    static void reply(Route route, Object data) {
        reply(route, data, 200, "");
    }

    static void reply(Route route, Object data, int status, String message) {
        var value = JSON.createObjectNode().put("success", status < 400).put("message", message);
        value.set("data", JSON.valueToTree(data));
        route.fulfill(new Route.FulfillOptions()
                .setStatus(status)
                .setContentType("application/json")
                .setBody(value.toString()));
    }
}
