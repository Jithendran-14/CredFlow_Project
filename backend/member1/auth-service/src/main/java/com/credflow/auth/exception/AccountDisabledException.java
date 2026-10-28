
package com.credflow.auth.exception;

public class AccountDisabledException extends RuntimeException {

	public AccountDisabledException() {
		super("This account is not active.");
	}
}