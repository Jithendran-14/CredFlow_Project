package com.credflow.auth.recovery;

import java.io.IOException;
import jakarta.mail.MessagingException;
import java.util.Properties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Local capture only. No caller-controlled SMTP host, sender or recipient. */
@Component
public class RecoveryMail {
	private final JavaMailSenderImpl sender;

	public RecoveryMail() {
		sender = new JavaMailSenderImpl();
		sender.setHost("127.0.0.1");
		sender.setPort(1025);
		sender.setDefaultEncoding("UTF-8");
		Properties p = sender.getJavaMailProperties();
		p.setProperty("mail.smtp.connectiontimeout", "3000");
		p.setProperty("mail.smtp.timeout", "3000");
		p.setProperty("mail.smtp.writetimeout", "3000");
	}

	public void available() throws IOException {
		try {
			sender.testConnection();
		} catch (MessagingException ex) {
			throw new IOException("The local mail service is unavailable.");
		}
	}

	public void send(String recipient, String link) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom("CredFlow <no-reply@credflow.test>");
		message.setTo(recipient);
		message.setSubject("Reset your CredFlow password");
		message.setText("A password reset was requested for your CredFlow account.\n\n"
				+ "Open this link to choose a new password:\n" + link
				+ "\n\nThis link expires in 15 minutes and can be used once. A newer reset request replaces older links."
				+ "\nIf you did not request this, ignore this email; your password has not changed."
				+ "\n\nLocal testing only: this message is captured by Mailpit, not sent externally.");
		sender.send(message);
	}
}
