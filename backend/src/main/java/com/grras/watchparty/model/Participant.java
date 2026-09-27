package com.grras.watchparty.model;

import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

public class Participant {
    private final String userId;
    private final String username;
    private String role;
    private final WebSocketSession session;
    private final long joinedAt;

    public Participant(String userId, String username, String role, WebSocketSession session) {
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.session = session;
        this.joinedAt = Instant.now().toEpochMilli();
    }

    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public WebSocketSession getSession() { return session; }
    public long getJoinedAt() { return joinedAt; }
}
