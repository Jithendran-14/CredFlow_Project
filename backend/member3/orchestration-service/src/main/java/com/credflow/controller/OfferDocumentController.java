package com.credflow.controller;

import com.credflow.service.OfferDocuments;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1")
public class OfferDocumentController {
	private final OfferDocuments documents;

	public OfferDocumentController(OfferDocuments documents) {
		this.documents = documents;
	}

	@PostMapping(value = "/submissions/{submission}/offers/{offer}/terms-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Object upload(@PathVariable UUID submission, @PathVariable UUID offer,
			@RequestParam("termsReference") String reference, @RequestParam("file") MultipartFile file,
			@AuthenticationPrincipal Jwt jwt) throws IOException {
		return documents.upload(submission, offer, reference, file.getOriginalFilename(), file.getBytes(), jwt);
	}

	@GetMapping("/offers/{id}/terms-document/metadata")
	public Object metadata(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return documents.metadata(id, jwt);
	}

	@GetMapping(value = "/offers/{id}/terms-document", produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> read(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		var d = documents.read(id, jwt);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(d.getFileSize())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(d.getFileName(), StandardCharsets.UTF_8).build()
								.toString())
				.header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(d.getContent());
	}
}
