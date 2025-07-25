package org.yourcompany.yourproject;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IConfidentialClientApplication;

public class bgkcraper {

    // --- Klasa pomocnicza do przechowywania stanu z SharePoint ---
    private static class SharePointItem {
        String id;
        String hash;

        SharePointItem(String id, String hash) {
            this.id = id;
            this.hash = hash;
        }
    }

    // --- Konfiguracja ---
    private static final String CLIENT_ID = "TWOJ_CLIENT_ID";
    private static final String CLIENT_SECRET = "TWOJ_CLIENT_SECRET";
    private static final String TENANT_ID = "TWOJ_TENANT_ID";
    private static final String AUTHORITY = "https://login.microsoftonline.com/" + TENANT_ID;
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    private static final String BASE_URL = "https://bgk.pl";
    private static final String SHAREPOINT_SITE_ID = "TWOJE_SITE_ID";
    private static final String SHAREPOINT_LIST_ID = "TWOJE_LIST_ID";
    
    // Zmień nazwy pól, jeśli na Twojej liście SharePoint nazywają się inaczej
    private static final String FIELD_URL = "Url"; 
    private static final String FIELD_HASH = "Hash";
    private static final String FIELD_CONTENT = "Content";

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        System.out.println("Rozpoczynam proces synchronizacji z SharePoint...");
        String accessToken = getAccessToken().get();

        if (accessToken == null || accessToken.isBlank()) {
            System.err.println("Błąd: Nie udało się uzyskać tokena dostępu. Zakończono.");
            return;
        }

        Map<String, SharePointItem> sharepointState = getSharePointState(accessToken);
        crawlAndSync(BASE_URL, sharepointState, accessToken);

        System.out.println("Synchronizacja zakończona.");
    }

    private static CompletableFuture<String> getAccessToken() throws Exception {
        IConfidentialClientApplication app = ConfidentialClientApplication.builder(
                CLIENT_ID,
                ClientCredentialFactory.createFromSecret(CLIENT_SECRET))
                .authority(AUTHORITY)
                .build();
        ClientCredentialParameters parameters = ClientCredentialParameters.builder(Collections.singleton(GRAPH_SCOPE)).build();
        return app.acquireToken(parameters).thenApply(IAuthenticationResult::accessToken);
    }

    private static void crawlAndSync(String startUrl, Map<String, SharePointItem> sharepointState, String accessToken) {
        Set<String> visited = new HashSet<>();
        Set<String> toVisit = new HashSet<>(Collections.singleton(startUrl));

        while (!toVisit.isEmpty()) {
            String currentUrl = toVisit.iterator().next();
            toVisit.remove(currentUrl);
            if (visited.contains(currentUrl)) continue;
            visited.add(currentUrl);

            System.out.println("Przetwarzam: " + currentUrl);

            try {
                Document doc = Jsoup.connect(currentUrl).get();
                String html = doc.html();
                String hash = toSha256(html);

                SharePointItem existingItem = sharepointState.get(currentUrl);

                if (existingItem == null) {
                    System.out.println("-> NOWY ELEMENT. Tworzenie wpisu w SharePoint...");
                    createSharePointItem(currentUrl, doc.title(), doc.body().text(), hash, accessToken);
                } else if (!existingItem.hash.equals(hash)) {
                    System.out.println("-> ZMIANA. Aktualizowanie wpisu w SharePoint...");
                    updateSharePointItem(existingItem.id, doc.title(), doc.body().text(), hash, accessToken);
                } else {
                    System.out.println("-> BEZ ZMIAN.");
                }

                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String href = link.absUrl("href");
                    if (href.startsWith(BASE_URL) && !visited.contains(href)) {
                        toVisit.add(href);
                    }
                }
            } catch (Exception e) { // Zmieniono z IOException na ogólny Exception
                System.err.println("Błąd odczytu lub przetwarzania URL " + currentUrl + ": " + e.getMessage());
            }
        }
    }

    private static String toSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, digest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 64) hashtext = "0" + hashtext;
            return hashtext;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, SharePointItem> getSharePointState(String accessToken) throws Exception {
        Map<String, SharePointItem> state = new HashMap<>();
        String graphUrl = "https://graph.microsoft.com/v1.0/sites/" + SHAREPOINT_SITE_ID + "/lists/" + SHAREPOINT_LIST_ID + "/items?expand=fields(select=id," + FIELD_URL + "," + FIELD_HASH + ")&top=999";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(graphUrl))
                .header("Authorization", "Bearer " + accessToken)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray items = body.getAsJsonArray("value");

        for (JsonElement item : items) {
            JsonObject fields = item.getAsJsonObject().getAsJsonObject("fields");
            if (fields.has(FIELD_URL) && fields.has(FIELD_HASH)) {
                String id = fields.get("id").getAsString();
                String url = fields.get(FIELD_URL).getAsString();
                String hash = fields.get(FIELD_HASH).getAsString();
                state.put(url, new SharePointItem(id, hash));
            }
        }
        System.out.println("Pobrano stan " + state.size() + " elementów z SharePoint.");
        return state;
    }
    
    private static void createSharePointItem(String url, String title, String content, String hash, String accessToken) throws Exception {
        String graphEndpoint = "https://graph.microsoft.com/v1.0/sites/" + SHAREPOINT_SITE_ID + "/lists/" + SHAREPOINT_LIST_ID + "/items";
        
        JsonObject fields = new JsonObject();
        fields.addProperty("Title", title);
        fields.addProperty(FIELD_URL, url);
        fields.addProperty(FIELD_CONTENT, content);
        fields.addProperty(FIELD_HASH, hash);

        JsonObject root = new JsonObject();
        root.add("fields", fields);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(graphEndpoint))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(root)))
                .build();
        
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void updateSharePointItem(String itemId, String title, String content, String hash, String accessToken) throws Exception {
        String graphEndpoint = "https://graph.microsoft.com/v1.0/sites/" + SHAREPOINT_SITE_ID + "/lists/" + SHAREPOINT_LIST_ID + "/items/" + itemId + "/fields";

        JsonObject fields = new JsonObject();
        fields.addProperty("Title", title);
        fields.addProperty(FIELD_CONTENT, content);
        fields.addProperty(FIELD_HASH, hash);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(graphEndpoint))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(gson.toJson(fields)))
                .build();
        
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}