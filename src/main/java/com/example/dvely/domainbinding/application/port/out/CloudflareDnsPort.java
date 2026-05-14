package com.example.dvely.domainbinding.application.port.out;

public interface CloudflareDnsPort {

    String createCnameRecord(String hostname, String target);

    boolean recordExists(String hostname, String recordId);

    void deleteRecord(String hostname, String recordId);
}
