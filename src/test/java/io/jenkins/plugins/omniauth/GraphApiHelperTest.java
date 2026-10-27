package io.jenkins.plugins.omniauth;

import io.jenkins.plugins.omniauth.util.GraphApiHelper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the SSRF guard on Graph API pagination in GraphApiHelper#getGroupMemberships.
 *
 * The pagination link (@odata.nextLink) is followed ONLY when it starts with the Graph
 * base URL (https://graph.microsoft.com/v1.0). A link to any other host must be rejected,
 * so no second HTTP request is ever issued to an attacker-controlled URL even if the
 * response body were tampered with.
 *
 * No real network I/O: a fake HttpClient subclass (NOT a Jenkins class — safe to subclass)
 * returns canned responses from a queue and records every requested URI. The fake is
 * injected into GraphApiHelper's private httpClient field by reflection.
 */
public class GraphApiHelperTest {

    // ─── Fake HttpResponse ────────────────────────────────────────────────────

    private static final class FakeResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        FakeResponse(int status, String body) { this.status = status; this.body = body; }
        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    // ─── Fake HttpClient ────────────────────────────────────────────────────

    private static final class FakeHttpClient extends HttpClient {
        final List<String> requestedUris = new ArrayList<>();
        private final List<FakeResponse> queued;
        private int idx = 0;

        FakeHttpClient(List<FakeResponse> responses) { this.queued = responses; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requestedUris.add(request.uri().toString());
            FakeResponse r = queued.get(Math.min(idx, queued.size() - 1));
            idx++;
            return (HttpResponse<T>) r;
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { return null; } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
    }

    private static GraphApiHelper helperWith(FakeHttpClient fake) throws Exception {
        GraphApiHelper helper = new GraphApiHelper();
        Field f = GraphApiHelper.class.getDeclaredField("httpClient");
        f.setAccessible(true);
        f.set(helper, fake);
        return helper;
    }

    private static String pageBody(String nextLink, String... groups) {
        StringBuilder sb = new StringBuilder("{\"value\":[");
        for (int i = 0; i < groups.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"id-").append(i).append("\",\"displayName\":\"").append(groups[i]).append("\"}");
        }
        sb.append("]");
        if (nextLink != null) sb.append(",\"@odata.nextLink\":\"").append(nextLink).append("\"");
        sb.append("}");
        return sb.toString();
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    public void nextLinkToDifferentHost_isRejected_noSecondRequest() throws Exception {
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new FakeResponse(200, pageBody("https://evil.com/steal?token=1", "Group-A"))));
        helperWith(fake).getGroupMemberships("fake-token");

        assertEquals(1, fake.requestedUris.size(), "a nextLink to another host must NOT be followed");
        assertFalse(fake.requestedUris.get(0).contains("evil.com"), "must never request the attacker host");
    }

    @Test
    public void nextLinkToLookalikeHost_isRejected() throws Exception {
        // https://graph.microsoft.com.evil.com/... does NOT start with https://graph.microsoft.com/v1.0
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new FakeResponse(200, pageBody(
                        "https://graph.microsoft.com.evil.com/v1.0/me/memberOf", "Group-A"))));
        helperWith(fake).getGroupMemberships("fake-token");

        assertEquals(1, fake.requestedUris.size(), "a look-alike host (suffix-attached) must be rejected");
        for (String uri : fake.requestedUris) {
            assertFalse(uri.contains("evil.com"), "must never request the look-alike host");
        }
    }

    @Test
    public void legitimateGraphNextLink_isFollowed() throws Exception {
        String legitNext = "https://graph.microsoft.com/v1.0/me/memberOf?$skiptoken=XYZ";
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new FakeResponse(200, pageBody(legitNext, "Group-Page1")),
                new FakeResponse(200, pageBody(null, "Group-Page2"))));

        List<EntraGroupDetails> groups = helperWith(fake).getGroupMemberships("fake-token");

        assertEquals(2, fake.requestedUris.size(), "a legitimate graph.microsoft.com/v1.0 nextLink must be followed");
        assertEquals(legitNext, fake.requestedUris.get(1), "the second request must target the graph nextLink");
        assertEquals(2, groups.size(), "both pages of groups must be collected");
    }

    @Test
    public void firstRequestAlwaysTargetsGraphBase() throws Exception {
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new FakeResponse(200, pageBody(null, "Group-A"))));
        helperWith(fake).getGroupMemberships("fake-token");

        assertTrue(fake.requestedUris.get(0).startsWith("https://graph.microsoft.com/v1.0/me/memberOf"), "the initial request must go to the Graph base");
    }

    @Test
    public void groupNodeMissingDisplayName_isSkipped() throws Exception {
        // One well-formed node and one missing displayName — only the complete one is kept.
        String body = "{\"value\":["
                + "{\"id\":\"ok\",\"displayName\":\"Good-Group\"},"
                + "{\"id\":\"bad\"}"  // no displayName
                + "]}";
        FakeHttpClient fake = new FakeHttpClient(List.of(new FakeResponse(200, body)));

        List<EntraGroupDetails> groups = helperWith(fake).getGroupMemberships("fake-token");

        assertEquals(1, groups.size(), "a group node without a displayName must be skipped");
    }

    @Test
    public void nonSuccessStatus_stopsPaginationGracefully() throws Exception {
        FakeHttpClient fake = new FakeHttpClient(List.of(new FakeResponse(403, "Forbidden")));

        List<EntraGroupDetails> groups = helperWith(fake).getGroupMemberships("fake-token");

        assertTrue(groups.isEmpty(), "an HTTP error yields no groups and no further requests");
        assertEquals(1, fake.requestedUris.size());
    }
}
