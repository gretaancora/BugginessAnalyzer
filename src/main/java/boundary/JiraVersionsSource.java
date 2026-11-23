package boundary;

import entity.JiraVersion;
import exception.JiraException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Legge le versioni di un progetto Jira da uno snapshot locale.
 * Se lo snapshot non esiste lo scarica (REST API /project/{key}/versions) e lo salva,
 * cosi' le esecuzioni successive lavorano sempre sugli stessi dati.
 */
public final class JiraVersionsSource {

    private static final String VERSIONS_URL = "https://issues.apache.org/jira/rest/api/2/project/%s/versions";

    private JiraVersionsSource() {
    }

    public static List<JiraVersion> load(Path snapshot, String projectKey) throws JiraException {
        if (!Files.exists(snapshot)) {
            download(projectKey, snapshot);
        }
        try {
            return parse(Files.readString(snapshot, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JiraException("Impossibile leggere lo snapshot " + snapshot + ": " + e.getMessage(), e);
        }
    }

    static List<JiraVersion> parse(String json) throws JiraException {
        try {
            JSONArray array = new JSONArray(json);
            List<JiraVersion> versions = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject v = array.getJSONObject(i);
                LocalDate date = v.has("releaseDate") ? LocalDate.parse(v.getString("releaseDate")) : null;
                versions.add(new JiraVersion(
                        v.getString("id"),
                        v.getString("name"),
                        v.optBoolean("released", false),
                        date));
            }
            return versions;
        } catch (JSONException | DateTimeParseException e) {
            throw new JiraException("Formato delle versioni Jira non valido: " + e.getMessage(), e);
        }
    }

    private static void download(String projectKey, Path snapshot) throws JiraException {
        String url = String.format(VERSIONS_URL, projectKey);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new JiraException("HTTP " + response.statusCode() + " da " + url);
            }
            Path parent = snapshot.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(snapshot, response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JiraException("Errore durante il download da " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraException("Download interrotto: " + url, e);
        }
    }
}