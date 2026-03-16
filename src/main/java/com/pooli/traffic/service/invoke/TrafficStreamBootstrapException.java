package com.pooli.traffic.service.invoke;

public class TrafficStreamBootstrapException extends IllegalStateException {

    public TrafficStreamBootstrapException(String message) {
        super(message);
    }

    public TrafficStreamBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
