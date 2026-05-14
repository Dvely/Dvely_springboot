package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.DnsLookupPort;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

@Component
public class DnsLookupClient implements DnsLookupPort {

    @Override
    public boolean hasCname(String hostname, String expectedTarget) {
        String expected = normalizeDnsName(expectedTarget);
        return lookupRecords(hostname, "CNAME").stream()
                .map(this::normalizeDnsName)
                .anyMatch(expected::equals);
    }

    @Override
    public boolean hasAddressRecordMatching(String hostname, String expectedTarget) {
        Set<String> actualAddresses = resolveAddresses(hostname);
        Set<String> expectedAddresses = resolveAddresses(expectedTarget);
        if (actualAddresses.isEmpty() || expectedAddresses.isEmpty()) {
            return false;
        }
        return actualAddresses.stream().anyMatch(expectedAddresses::contains);
    }

    private List<String> lookupRecords(String hostname, String recordType) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        try {
            Attributes attributes = new InitialDirContext(env).getAttributes(hostname, new String[]{recordType});
            Attribute attribute = attributes.get(recordType);
            if (attribute == null) {
                return List.of();
            }
            NamingEnumeration<?> values = attribute.getAll();
            Stream.Builder<String> builder = Stream.builder();
            while (values.hasMore()) {
                builder.add(String.valueOf(values.next()));
            }
            return builder.build().toList();
        } catch (NamingException e) {
            return List.of();
        }
    }

    private Set<String> resolveAddresses(String hostname) {
        try {
            return Stream.of(InetAddress.getAllByName(hostname))
                    .map(InetAddress::getHostAddress)
                    .collect(Collectors.toSet());
        } catch (UnknownHostException e) {
            return Set.of();
        }
    }

    private String normalizeDnsName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.endsWith(".")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
