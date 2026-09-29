package com.fakejira.task;

public enum LinkType {
    BLOCKS("blocks", "is blocked by"),
    RELATES("relates to", "relates to"),
    DUPLICATES("duplicates", "is duplicated by");

    private final String outward;
    private final String inward;

    LinkType(String outward, String inward) {
        this.outward = outward;
        this.inward = inward;
    }

    public String outward() {
        return outward;
    }

    public String inward() {
        return inward;
    }
}
