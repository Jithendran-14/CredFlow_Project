package com.credflow.consent.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ConsentHistoryId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "CONSENT_ID", length = 36, nullable = false)
	private String consentId;

	@Column(name = "CONSENT_VERSION", nullable = false)
	private Integer consentVersion;

	protected ConsentHistoryId() {
		// Required by JPA.
	}

	public ConsentHistoryId(String consentId, Integer consentVersion) {

		this.consentId = consentId;
		this.consentVersion = consentVersion;
	}

	public String getConsentId() {
		return consentId;
	}

	public Integer getConsentVersion() {
		return consentVersion;
	}

	@Override
	public boolean equals(Object other) {

		if (this == other) {
			return true;
		}

		if (!(other instanceof ConsentHistoryId that)) {
			return false;
		}

		return Objects.equals(consentId, that.consentId) && Objects.equals(consentVersion, that.consentVersion);
	}

	@Override
	public int hashCode() {
		return Objects.hash(consentId, consentVersion);
	}
}