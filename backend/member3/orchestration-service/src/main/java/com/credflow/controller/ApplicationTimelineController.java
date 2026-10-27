package com.credflow.controller;
import com.credflow.service.ApplicationTimeline;import java.util.UUID;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.http.*;
@RestController
public class ApplicationTimelineController {
 private final ApplicationTimeline timeline;public ApplicationTimelineController(ApplicationTimeline timeline){this.timeline=timeline;}
 @GetMapping("/api/v1/applications/{id}/timeline") public ResponseEntity<?> read(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(timeline.read(id,jwt));}
}
