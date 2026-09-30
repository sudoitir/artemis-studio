package io.github.sudoitir.artemisstudio.kernel.security.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes {@code getRemoteAddr()} answer the client's address in its shortest form (RFC 5952), so a
 * session, a trusted device, an audit event and a limiter key all name one client the same way.
 * Tomcat spells IPv6 loopback {@code 0:0:0:0:0:0:0:1} and passes a forwarded address through as the
 * proxy wrote it; this is the one place that is settled, ahead of every reader.
 */
public class ClientAddressFilter extends OncePerRequestFilter {

    /** Only what an IP literal is made of: nothing else may reach {@link InetAddress#getByName}, which would resolve it. */
    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = normalise(request.getRemoteAddr());
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public String getRemoteAddr() {
                        return address;
                    }
                },
                response);
    }

    /** An IPv6 literal compacted (an IPv4-mapped one as IPv4); anything else, unchanged. */
    static String normalise(String address) {
        if (address == null
                || address.indexOf(':') < 0
                || !LITERAL.matcher(address).matches()) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return parsed instanceof Inet4Address ? parsed.getHostAddress() : compress(parsed.getAddress());
        } catch (UnknownHostException _) {
            return address;
        }
    }

    private static String compress(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int[] run = longestZeroRun(groups);
        if (run[0] < 0) {
            return join(groups, 0, 8);
        }
        return join(groups, 0, run[0]) + "::" + join(groups, run[0] + run[1], 8);
    }

    private static String join(int[] groups, int from, int to) {
        return IntStream.range(from, to)
                .mapToObj(i -> Integer.toHexString(groups[i]))
                .collect(Collectors.joining(":"));
    }

    /** The start and length of the longest run of zero groups; start -1 when there is none longer than one. */
    private static int[] longestZeroRun(int[] groups) {
        int runStart = -1;
        int runLength = 1; // a lone zero group is not compressed
        int i = 0;
        while (i < 8) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < 8 && groups[end] == 0) {
                end++;
            }
            if (end - i > runLength) {
                runStart = i;
                runLength = end - i;
            }
            i = end;
        }
        return new int[] {runStart, runLength};
    }
}
