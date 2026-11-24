package boundary;

import entity.Ticket;
import exception.JiraException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Legge i ticket di bug da uno snapshot locale. Se lo snapshot non esiste esegue la ricerca JQL
 * sull'API REST di Jira (a pagine) e salva tutti gli issue trovati in un unico array JSON, cosi' le
 * esecuzioni successive lavorano sugli stessi dati.
 */
public final class JiraTicketsSource {

    private static final String SEARCH_URL = "https://issues.apache.org/jira/rest/api/2/search";
    private static final String FIELDS = "created,resolutiondate,versions,fixVersions";
    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter JIRA_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private JiraTicketsSource() {
    }

    public static List<Ticket> load(Path snapshot, String jql) throws JiraException {
        if (!Files.exists(snapshot)) {
            download(jql, snapshot);
        }
        try {
            return parse(Files.readString(snapshot, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JiraException("Impossibile leggere lo snapshot " + snapshot + ": " + e.getMessage(), e);
        }
    }

    static List<Ticket> parse(String json) throws JiraException {
        try {
            JSONArray issues = new JSONArray(json);
            List<Ticket> tickets = new ArrayList<>(issues.length());
            for (int i = 0; i < issues.length(); i++) {
                JSONObject issue = issues.getJSONObject(i);
                JSONObject f = issue.getJSONObject("fields");
                tickets.add(new Ticket(
                        issue.getString("key"),
                        OffsetDateTime.parse(f.getString("created"), JIRA_DATE),
                        f.isNull("resolutiondate") ? null : OffsetDateTime.parse(f.getString("resolutiondate"), JIRA_DATE),
                        names(f.optJSONArray("versions")),
                        names(f.optJSONArray("fixVersions"))));
            }
            return tickets;
        } catch (JSONException | DateTimeParseException e) {
            throw new JiraException("Formato dei ticket Jira non valido: " + e.getMessage(), e);
        }
    }

    private static List<String> names(JSONArray versions) {
        List<String> names = new ArrayList<>();
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                names.add(versions.getJSONObject(i).getString("name"));
            }
        }
        return names;
    }

    private static void download(String jql, Path snapshot) throws JiraException {
        JSONArray all = new JSONArray();
        try (HttpClient client = HttpClient.newHttpClient()) {
            int startAt = 0;
            int total;
            do {
                JSONObject page = fetchPage(client, jql, startAt);
                JSONArray issues = page.getJSONArray("issues");
                for (int i = 0; i < issues.length(); i++) {
                    all.put(issues.getJSONObject(i));
                }
                total = page.getInt("total");
                if (issues.isEmpty()) {
                    break;
                }
                startAt += issues.length();
            } while (startAt < total);

            if (all.length() != total) {
                throw new JiraException("Scaricati " + all.length() + " ticket su " + total + " attesi");
            }
            Path parent = snapshot.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(snapshot, all.toString(1), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JiraException("Errore durante il download dei ticket: " + e.getMessage(), e);
        } catch (JSONException e) {
            throw new JiraException("Risposta di Jira non valida: " + e.getMessage(), e);
        }
    }

    private static JSONObject fetchPage(HttpClient client, String jql, int startAt) throws JiraException, IOException {
        String url = SEARCH_URL
                + "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                + "&fields=" + FIELDS
                + "&startAt=" + startAt
                + "&maxResults=" + PAGE_SIZE;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new JiraException("HTTP " + response.statusCode() + " dalla ricerca Jira (startAt=" + startAt + ")");
            }
            return new JSONObject(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraException("Download dei ticket interrotto", e);
        }
    }
}