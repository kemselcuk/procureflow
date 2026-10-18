package com.procureflow.worker.config;

/** Fixed durable broker topology shared with the application event contract. */
public final class RabbitTopology {
    public static final String EXCHANGE = "procureflow.events";
    public static final String QUEUE = "procureflow.notifications";
    public static final String BINDING_PATTERN = "request.*";

    private RabbitTopology() {}
}
