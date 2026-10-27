package com.credflow.entity;

import jakarta.persistence.*;

@Entity(name = "CfWorkflowIdempotency")
@Table(name = "CF_WORKFLOW_IDEMPOTENCY")
public class IdempotencyRow {

    public IdempotencyRow() {
    }

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Lob
    private String responseJson;

    private int httpStatus;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }
}
