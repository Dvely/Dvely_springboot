package com.example.dvely.domainbinding.application.port.out;

public interface DnsLookupPort {

    boolean hasCname(String hostname, String expectedTarget);

    boolean hasAddressRecordMatching(String hostname, String expectedTarget);
}
