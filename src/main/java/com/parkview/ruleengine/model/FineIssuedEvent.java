package com.parkview.ruleengine.model;

import lombok.Data;

import java.util.UUID;

@Data
public class FineIssuedEvent {
    private UUID   fineId;
    private UUID   violationId;
    private String plate;
    private String userId;
    private String zoneId;
    private String zoneAddress;
    private int    amountSek;
    private long   timestamp;
}
