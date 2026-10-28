package com.credflow.application.exception;

public class ActiveApplicationException extends RuntimeException {
	private final String applicationId;

	public ActiveApplicationException() {
		this(null);
	}

	public ActiveApplicationException(String id) {
		super("An active application already exists for this lending purpose."
				+ (id == null ? "" : " Existing application: " + id));
		this.applicationId = id;
	}

	public String applicationId() {
		return applicationId;
	}
}
