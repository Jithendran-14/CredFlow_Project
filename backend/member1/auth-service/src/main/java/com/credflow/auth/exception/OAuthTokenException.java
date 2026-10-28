package com.credflow.auth.exception;

import org.springframework.http.HttpStatus;

public class OAuthTokenException extends RuntimeException {

	private final HttpStatus status;
	private final String errorCode;

	public OAuthTokenException(HttpStatus status, String errorCode) {

		super(errorCode);
		this.status = status;
		this.errorCode = errorCode;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getErrorCode() {
		return errorCode;
	}
}