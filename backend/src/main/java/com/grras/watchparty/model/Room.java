package com.grras.watchparty.model;

import java.util.*;
import java.util.stream.Collectors;

public class Room {
    private final String roomId;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private String videoId;
    private String playState = "paused";
    private double currentTime = 0.0;
    private long stateUpdatedAt = System.currentTimeMillis();

    public Room(String roomId, String defaultVideoId) {
        this.roomId = roomId;
        this.videoId = defaultVideoId;
    }

    public String getRoomId() { return roomId; }
    public Map<String, Participant> getParticipants() { return participants; }
    public String getVideoId() { return videoId; }
    public String getPlayState() { return playState; }
    public double getCurrentTime() { return currentTime; }

    public void addParticipant(Participant p) { participants.put(p.getUserId(), p); }
    public Participant get(String userId) { return participants.get(userId); }
    public Participant removeParticipant(String userId) { return participants.remove(userId); }

    public boolean hasPermission(String userId, String action) {
        Participant user = get(userId);
        if (user == null) return false;
        if (Set.of("play", "pause", "seek", "change_video").contains(action)) {
            return Set.of("host", "moderator").contains(user.getRole());
        }
        if (Set.of("assign_role", "remove_participant", "transfer_host").contains(action)) {
            return "host".equals(user.getRole());
        }
        return false;
    }

    public List<Map<String, String>> participantList() {
        return participants.values().stream()
                .sorted(Comparator.comparingLong(Participant::getJoinedAt))
                .map(p -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("userId", p.getUserId());
                    result.put("username", p.getUsername());
                    result.put("role", p.getRole());
                    return result;
                })
                .collect(Collectors.toList());
    }

    public double effectiveCurrentTime() {
        if ("playing".equals(playState)) {
            return Math.max(0.0, currentTime + (System.currentTimeMillis() - stateUpdatedAt) / 1000.0);
        }
        return Math.max(0.0, currentTime);
    }

    public Map<String, Object> statePayload() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("playState", playState);
        state.put("currentTime", effectiveCurrentTime());
        state.put("videoId", videoId);
        state.put("serverTime", System.currentTimeMillis() / 1000.0);
        return state;
    }

    public void setPlayback(double time, String state) {
        this.currentTime = Math.max(0.0, time);
        this.playState = state;
        this.stateUpdatedAt = System.currentTimeMillis();
    }

    public void seek(double time) {
        this.currentTime = Math.max(0.0, time);
        this.stateUpdatedAt = System.currentTimeMillis();
    }

    public void changeVideo(String videoId) {
        this.videoId = videoId;
        this.currentTime = 0.0;
        this.playState = "paused";
        this.stateUpdatedAt = System.currentTimeMillis();
    }
}
