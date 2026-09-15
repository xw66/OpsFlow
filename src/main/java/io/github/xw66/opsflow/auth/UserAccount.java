package io.github.xw66.opsflow.auth;

public record UserAccount(long id, String username, String passwordHash, String displayName, boolean enabled) { }
