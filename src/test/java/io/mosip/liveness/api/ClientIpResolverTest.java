package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Contract for the shared client-IP resolution, now that two callers depend on
 * it: {@link RateLimitFilter} keys its per-IP budget on the answer and
 * {@link ClientIpFilter} publishes it into the config audit trail. If they
 * disagree, a rate limit and an audit entry can name different hosts for one
 * request.
 *
 * <p>Most of the trust semantics are exercised end-to-end in
 * {@link RateLimitFilterTest}; these cover the pieces that belong to the shared
 * component itself — the published attribute and the no-DNS guarantee.</p>
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolverTrusting(String trustedProxies) {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustedProxies", List.of(trustedProxies.split(",")));
        return resolver;
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/config/RESIDENT");
        request.setRemoteAddr(peer);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        return request;
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void withNoTrustedProxies_thePeerAddressIsTheAnswer() {
        ClientIpResolver resolver = resolverTrusting("");
        assertEquals("172.18.0.2", resolver.resolve(request("172.18.0.2", "203.0.113.1", null)));
    }

    @Test
    void anUntrustedPeerCannotNameTheClient() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/8");
        assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", "203.0.113.1", "203.0.113.2")));
    }

    @Test
    void theChainIsWalkedFromTheProxyBackTowardsTheClient() {
        // edge proxy (trusted) -> inner proxy (trusted) -> client
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16, 10.8.0.0/24");
        assertEquals("203.0.113.7",
                resolver.resolve(request("10.8.0.4", "203.0.113.7, 172.18.0.9", null)));
        assertEquals("198.51.100.7",
                resolver.resolve(request("10.8.0.4", "198.51.100.7, 172.18.0.9", null)));
    }

    @Test
    void xRealIpDecidesWhenForwardedForIsAbsentOrAllTrusted() {
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("203.0.113.5", resolver.resolve(request("172.18.0.2", null, "203.0.113.5")));
        // An XFF listing only trusted hops names no client, so X-Real-IP wins.
        assertEquals("203.0.113.8", resolver.resolve(request("172.18.0.2", "172.18.0.7", "203.0.113.8")));
    }

    @Test
    void oneClientWrittenTwoWaysResolvesToTheSameAddress() {
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.2", "203.0.113.9:51234", null)));
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.2", "203.0.113.9", null)));
    }

    @Test
    void hostnamesArePassedThroughWithoutEverBeingResolved() {
        // Nothing here may hit DNS: that would put a lookup in the request path
        // (latency, and a failure a caller could induce). The resolver only ever
        // parses literals, so a hostname is carried through as text.
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("client.example.invalid",
                resolver.resolve(request("172.18.0.2", "client.example.invalid", null)));
        // …and it can never match a trust entry, so it cannot open a chain of
        // trusted hops and smuggle in a later hop.
        // The rightmost entry is the client; a hostname in a trusted-hop
        // chain does not promote a later hop to become the client.
        assertEquals("client.example.invalid",
                resolver.resolve(request("172.18.0.2", "203.0.113.4, client.example.invalid", null)));
    }

    // ------------------------------------------------------------------ the published attribute

    @Test
    void theFilterPublishesTheResolvedAddressForHandlersToRead() throws Exception {
        ClientIpFilter filter = new ClientIpFilter(resolverTrusting("172.18.0.0/16"));
        MockHttpServletRequest request = request("172.18.0.2", "203.0.113.42", null);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("203.0.113.42", request.getAttribute(ClientIpResolver.ATTRIBUTE));
        assertNotNull(chain.getRequest(), "the filter must not short-circuit the chain");
    }

    @Test
    void anAbsentResolverFallsBackToTrustingNobody() throws Exception {
        // The @WebMvcTest fallback: an ordinary component is not in a slice, and
        // the filter must still publish something rather than fail to start.
        ClientIpFilter filter = new ClientIpFilter((ClientIpResolver) null);
        MockHttpServletRequest request = request("172.18.0.2", "203.0.113.42", null);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("172.18.0.2", request.getAttribute(ClientIpResolver.ATTRIBUTE));
    }

    @Test
    void theAttributeNameIsNamespaced() {
        // A bare "clientIp" would collide with anything a container or library
        // parks on the request.
        assertEquals(ClientIpResolver.class.getName() + ".clientIp", ClientIpResolver.ATTRIBUTE);
    }
}