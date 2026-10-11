package com.niuqu.chatbubble.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remote chat content must not reach file:/UNC reads or loopback/LAN fetches. */
class MediaUrlPolicyTest {

    @Test
    void allowsPublicHttpAndServerMedia() {
        assertTrue(MediaUrlPolicy.isFetchable("https://example.com/a.gif"));
        assertTrue(MediaUrlPolicy.isFetchable("http://203.0.113.9/a.png"));
        assertTrue(MediaUrlPolicy.isFetchable("http://example.com:8080/a.webp"));
        assertTrue(MediaUrlPolicy.isFetchable("e33chat://media/0123456789abcdef0123456789abcdef"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertFalse(MediaUrlPolicy.isFetchable("file:///C:/Windows/win.ini"));
        assertFalse(MediaUrlPolicy.isFetchable("file://server/share/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("\\\\server\\share\\a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("ftp://example.com/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("javascript:alert(1)"));
    }

    @Test
    void rejectsLocalNames() {
        assertFalse(MediaUrlPolicy.isFetchable("http://localhost/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://LOCALHOST:8080/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://printer.local/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://foo.local./a.gif"));
    }

    @Test
    void rejectsLoopbackPrivateAndLinkLocalIpv4() {
        assertFalse(MediaUrlPolicy.isFetchable("http://127.0.0.1/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://127.1/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://2130706433/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://0x7f000001/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://10.0.0.5/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://172.16.3.4/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://192.168.1.1/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://169.254.1.1/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://100.64.0.1/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://0.0.0.0/a.gif"));
    }

    @Test
    void rejectsIpv6Literals() {
        assertFalse(MediaUrlPolicy.isFetchable("http://[::1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[0:0:0:0:0:0:0:1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[::]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[fe80::1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[fc00::1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[::ffff:127.0.0.1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[::ffff:192.168.0.1]/a.gif"));
        assertFalse(MediaUrlPolicy.isFetchable("http://[ff02::1]/a.gif"));
    }

    @Test
    void keysOnTheRealHostWhenUserinfoIsPresent() {
        assertFalse(MediaUrlPolicy.isFetchable("http://evil.com@127.0.0.1/a.gif"));
        assertTrue(MediaUrlPolicy.isFetchable("http://127.0.0.1@example.com/a.gif"));
    }

    @Test
    void classifiesHostsDirectly() {
        assertTrue(MediaUrlPolicy.isBlockedHost("[::1]"));
        assertTrue(MediaUrlPolicy.isBlockedHost("127.0.0.1"));
        assertTrue(MediaUrlPolicy.isBlockedHost(""));
        assertTrue(MediaUrlPolicy.isBlockedHost("fe80::1%eth0"));
        assertFalse(MediaUrlPolicy.isBlockedHost("example.com"));
        assertFalse(MediaUrlPolicy.isBlockedHost("8.8.8.8"));
        assertFalse(MediaUrlPolicy.isBlockedHost("2001:4860:4860::8888"));
    }
}
