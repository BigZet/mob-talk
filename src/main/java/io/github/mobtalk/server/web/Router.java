package io.github.mobtalk.server.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Method plus path template ({@code /api/players/{uuid}/kick}) to a handler; the first matching route wins. */
public final class Router {
    public interface Handler { Response handle(Request request) throws Exception; }

    private record Route(String method, String[] parts, Handler handler) { }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String template, Handler handler) { return add("GET", template, handler); }
    public Router post(String template, Handler handler) { return add("POST", template, handler); }
    public Router patch(String template, Handler handler) { return add("PATCH", template, handler); }

    public Router add(String method, String template, Handler handler) {
        routes.add(new Route(method, split(template), handler));
        return this;
    }

    /** The handler, with the path parameters put into {@code params}, or null; a path that exists under another method is a 405. */
    public Handler find(Request request, Map<String, String> params) {
        String[] path = split(request.path());
        boolean otherMethod = false;
        for (Route route : routes) {
            Map<String, String> found = bind(route.parts, path);
            if (found == null) continue;
            if (!route.method.equals(request.method())) { otherMethod = true; continue; }
            params.putAll(found);
            return route.handler;
        }
        if (otherMethod) throw new ApiException(405, "method_not_allowed", "Этот адрес не принимает " + request.method() + ".");
        return null;
    }

    private static Map<String, String> bind(String[] template, String[] path) {
        if (template.length != path.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < template.length; i++) {
            String part = template[i];
            if (part.startsWith("{") && part.endsWith("}")) {
                if (path[i].isEmpty()) return null;
                params.put(part.substring(1, part.length() - 1), path[i]);
            } else if (!part.equals(path[i])) return null;
        }
        return params;
    }

    private static String[] split(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/", -1);
    }
}
