package com.fakejira.admin;

import java.util.Map;

/** Exposes the package-private SigV4 signer to tests in other packages. */
public final class OffsiteBackupAccess {

    private OffsiteBackupAccess() {
    }

    public static String signature(String method, String uri, String query, Map<String, String> headers, String payloadHash,
                                   String amzDate, String region, String service, String secretKey) {
        return OffsiteBackup.signature(method, uri, query, headers, payloadHash, amzDate, region, service, secretKey);
    }
}
