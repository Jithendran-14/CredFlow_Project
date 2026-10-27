package com.credflow.auth.controller;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AccountDirectoryController {
 private final JdbcTemplate jdbc;
 public AccountDirectoryController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
  if(page<0||size<1||size>100)throw new IllegalArgumentException("Invalid pagination.");
  String filter=" FROM CF_AUTH_USERS WHERE USER_ROLE IN ('SELLER','MARKETPLACE','LENDER_OFFICER','ADMIN') AND USER_ID NOT IN (SELECT USER_ID FROM CF_AUTH_SERVICE_CLIENTS)";
  return Map.of("items",jdbc.queryForList("SELECT PUBLIC_USER_ID AS \"userId\", USERNAME AS \"username\", EMAIL_ADDRESS AS \"emailAddress\", USER_ROLE AS \"role\", ACCOUNT_STATUS AS \"status\""+filter+" ORDER BY CREATED_AT DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",(long)page*size,size),"totalElements",jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class));
 }
}
