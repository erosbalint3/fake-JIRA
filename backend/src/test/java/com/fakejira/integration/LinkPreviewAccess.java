package com.fakejira.integration;

/** Exposes package-private helpers of the integration package to tests in other packages. */
public final class LinkPreviewAccess {

    private LinkPreviewAccess() {
    }

    public static String[] embed(String url) {
        return LinkPreviewController.embed(url);
    }
}
