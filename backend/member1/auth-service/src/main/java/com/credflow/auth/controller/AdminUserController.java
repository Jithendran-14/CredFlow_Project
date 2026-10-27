
package com.credflow.auth.controller;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.credflow.auth.dto.CreateUserRequest;
import com.credflow.auth.service.AdminUserService;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createUser(
            @Valid @RequestBody CreateUserRequest request) {

        String publicUserId = adminUserService.createUser(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                    "userId", publicUserId,
                    "message", "User created successfully"
                ));
    }
}
