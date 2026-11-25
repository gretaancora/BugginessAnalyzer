package boundary;

import exception.AnalysisException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client minimo della Web API v1 di SonarCloud (autenticazione Bearer con il token dell'utente).
 */
public final class SonarCloudClient implements AutoCloseable {

    private static final int PAGE_SIZE = 500;

    private final String baseUrl;
    private final String organization;
    private final String token;
    private final HttpClient http = HttpClient.newHttpClient();

    public SonarCloudClient(String baseUrl, String organization, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.organization = organization;
        this.token = token;
    }

    /** Verifica il token con {@code api/authentication/validate}: {@code true} se SonarCloud lo riconosce. */
    public boolean tokenIsValid() throws AnalysisException {
        HttpResponse<String> r = get("/api/authentication/validate", Map.of());
        if (r.statusCode() == 401) {
            return false;
        }
        check(r, "verifica del token");
        return json(r).optBoolean("valid", false);
    }

    /** Crea il progetto pubblico se non esiste ancora; restituisce {@code true} se l'ha creato. */
    public boolean ensureProject(String key, String name) throws AnalysisException {
        if (projectExists(key)) {
            return false;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("organization", organization);
        form.put("project", key);
        form.put("name", name);
        form.put("visibility", "public");
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/projects/create"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))));
        if (r.statusCode() == 400 && r.body().contains("already exists")) {
            return false;
        }
        check(r, "creazione del progetto " + key);
        return true;
    }

    private boolean projectExists(String key) throws AnalysisException {
        HttpResponse<String> r = get("/api/components/show", Map.of("component", key));
        if (r.statusCode() == 404) {
            return false;
        }
        check(r, "ricerca del progetto " + key);
        return true;
    }

    /** {@code true} se il progetto ha gia' almeno un'analisi completata. */
    public boolean hasAnalysis(String key) throws AnalysisException {
        HttpResponse<String> r = get("/api/project_analyses/search", Map.of("project", key, "ps", "1"));
        if (r.statusCode() == 404) {
            return false;
        }
        check(r, "analisi del progetto " + key);
        return !json(r).getJSONArray("analyses").isEmpty();
    }

    /** Stato di un'elaborazione: PENDING, IN_PROGRESS, SUCCESS, FAILED o CANCELED. */
    public String taskStatus(String taskId) throws AnalysisException {
        HttpResponse<String> r = get("/api/ce/task", Map.of("id", taskId));
        check(r, "stato dell'elaborazione " + taskId);
        return json(r).getJSONObject("task").getString("status");
    }

    /**
     * Misure di tutti i file del progetto: percorso del file -> (metrica -> valore).
     * Usa api/measures/component_tree a pagine da {@value #PAGE_SIZE}.
     */
    public Map<String, Map<String, String>> fileMeasures(String key, List<String> metricKeys) throws AnalysisException {
        Map<String, Map<String, String>> all = new HashMap<>();
        int page = 1;
        int total;
        do {
            HttpResponse<String> r = get("/api/measures/component_tree", Map.of(
                    "component", key,
                    "metricKeys", String.join(",", metricKeys),
                    "qualifiers", "FIL",
                    "ps", String.valueOf(PAGE_SIZE),
                    "p", String.valueOf(page)));
            check(r, "misure del progetto " + key);
            JSONObject body = json(r);
            all.putAll(parseComponents(body));
            total = body.getJSONObject("paging").getInt("total");
            page++;
        } while ((long) (page - 1) * PAGE_SIZE < total);
        return all;
    }

    static Map<String, Map<String, String>> parseComponents(JSONObject body) {
        Map<String, Map<String, String>> out = new HashMap<>();
        JSONArray components = body.getJSONArray("components");
        for (int i = 0; i < components.length(); i++) {
            JSONObject c = components.getJSONObject(i);
            if (!c.has("path")) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            JSONArray measures = c.optJSONArray("measures");
            if (measures != null) {
                for (int j = 0; j < measures.length(); j++) {
                    JSONObject m = measures.getJSONObject(j);
                    if (m.has("value")) {
                        values.put(m.getString("metric"), m.getString("value"));
                    }
                }
            }
            out.put(c.getString("path"), values);
        }
        return out;
    }

    private HttpResponse<String> get(String path, Map<String, String> params) throws AnalysisException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path + "?" + encode(new LinkedHashMap<>(params)))).GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws AnalysisException {
        HttpRequest request = builder
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AnalysisException("Errore di rete verso SonarCloud: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("Richiesta a SonarCloud interrotta", e);
        }
    }

    private static void check(HttpResponse<String> r, String what) throws AnalysisException {
        if (r.statusCode() / 100 != 2) {
            String hint = r.statusCode() == 401 || r.statusCode() == 403
                    ? " (controlla il token e i permessi sull'organizzazione)" : "";
            throw new AnalysisException("SonarCloud, " + what + ": HTTP " + r.statusCode() + hint + " " + r.body());
        }
    }

    private static JSONObject json(HttpResponse<String> r) throws AnalysisException {
        try {
            return new JSONObject(r.body());
        } catch (JSONException e) {
            throw new AnalysisException("Risposta di SonarCloud non valida: " + e.getMessage(), e);
        }
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    @Override
    public void close() {
        http.close();
    }
}
