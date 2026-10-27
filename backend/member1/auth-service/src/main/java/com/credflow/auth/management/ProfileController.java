package com.credflow.auth.management;
import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import com.credflow.auth.service.SellerRegistrationService;
@RestController
public class ProfileController {
 private final ProfileManagement profiles;private final SellerRegistrationService sellers;public ProfileController(ProfileManagement profiles,SellerRegistrationService sellers){this.profiles=profiles;this.sellers=sellers;}
 @PutMapping("/api/v1/seller/profile")public Object seller(@Valid @RequestBody SellerProfileInput input,@AuthenticationPrincipal Jwt jwt){profiles.seller(input,jwt);return sellers.profile(jwt);}
 @GetMapping("/api/v1/account/profile")public Object account(@AuthenticationPrincipal Jwt jwt){return profiles.read(jwt);}
 @PutMapping("/api/v1/account/profile")public Object account(@Valid @RequestBody ProfileManagement.AccountInput input,@AuthenticationPrincipal Jwt jwt){return profiles.account(input,jwt);}
}
