package com.grras.watchparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.grras.watchparty.model.Participant;
import com.grras.watchparty.model.Room;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

@Component
public class WatchPartyWebSocketHandler extends TextWebSocketHandler {

    private final WatchPartyService service;

    public WatchPartyWebSocketHandler(WatchPartyService service) {
        this.service = service;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put("userId", service.newUserId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = (String) session.getAttributes().get("userId");

        try {
            JsonNode first = service.mapper().readTree(message.getPayload());

            if (session.getAttributes().get("roomId") == null) {
                handleJoin(session, userId, first);
                return;
            }

            handleEvent(session, userId, first);
        } catch (Exception e) {
            sendError(session, "Invalid JSON message.");
        }
    }

    private void handleJoin(WebSocketSession session, String userId, JsonNode first) {
        if (!"join_room".equals(first.path("type").asText())) {
            sendError(session, "First event must be join_room.");
            closeQuietly(session);
            return;
        }

        String roomId = WatchPartyService.normalizeRoomId(text(first, "roomId"));
        String username = WatchPartyService.normalizeUsername(text(first, "username"));

        if (roomId.isEmpty()) {
            sendError(session, "Room code is required.");
            closeQuietly(session);
            return;
        }

        WatchPartyService.JoinResult result = service.join(roomId, username, userId, session);
        session.getAttributes().put("roomId", roomId);

        service.send(session, result.snapshot());

        Map<String, Object> joined = new LinkedHashMap<>();
        joined.put("type", "user_joined");
        joined.put("username", username);
        joined.put("userId", userId);
        joined.put("role", result.participant().getRole());
        joined.put("participants", result.room().participantList());
        service.broadcast(result.room(), joined, userId);
    }

    private void handleEvent(WebSocketSession session, String userId, JsonNode message) {
        String roomId = (String) session.getAttributes().get("roomId");
        Room room = service.getRoom(roomId);
        if (room == null) {
            sendError(session, "Room does not exist.");
            return;
        }

        Participant user = room.get(userId);
        if (user == null) {
            sendError(session, "You are not a member of this room.");
            return;
        }

        String event = message.path("type").asText("");

        switch (event) {
            case "leave_room" -> closeQuietly(session);
            case "play", "pause" -> handlePlayPause(session, room, user, event, message);
            case "seek" -> handleSeek(session, room, user, message);
            case "change_video" -> handleChangeVideo(session, room, user, message);
            case "assign_role" -> handleAssignRole(session, room, user, message);
            case "remove_participant" -> handleRemove(session, room, user, message);
            case "transfer_host" -> handleTransferHost(session, room, user, message);
            case "request_control" -> handleControlRequest(session, room, user, message);
            default -> sendError(session, "Unknown event: " + event);
        }
    }

    private void handlePlayPause(WebSocketSession session, Room room, Participant user, String event, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), event)) {
            sendError(session, event + " requires Host or Moderator permission.");
            return;
        }
        double time = numberOr(message, "time", room.effectiveCurrentTime());
        synchronized (room) {
            room.setPlayback(time, "play".equals(event) ? "playing" : "paused");
        }
        broadcastSync(room, user.getUserId(), event);
    }

    private void handleSeek(WebSocketSession session, Room room, Participant user, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), "seek")) {
            sendError(session, "Seek requires Host or Moderator permission.");
            return;
        }
        if (!message.has("time") || !message.get("time").isNumber()) {
            sendError(session, "Invalid seek time.");
            return;
        }
        synchronized (room) {
            room.seek(Math.max(0, message.get("time").asDouble()));
        }
        broadcastSync(room, user.getUserId(), "seek");
    }

    private void handleChangeVideo(WebSocketSession session, Room room, Participant user, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), "change_video")) {
            sendError(session, "Changing the video requires Host or Moderator permission.");
            return;
        }
        String videoId = WatchPartyService.extractVideoId(text(message, "videoId"));
        if (videoId == null) {
            sendError(session, "Enter a valid YouTube URL or 11-character video ID.");
            return;
        }
        synchronized (room) {
            room.changeVideo(videoId);
        }
        broadcastSync(room, user.getUserId(), "change_video");
    }

    private void handleAssignRole(WebSocketSession session, Room room, Participant user, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), "assign_role")) {
            sendError(session, "Only the Host can assign roles.");
            return;
        }

        String targetId = text(message, "userId");
        String targetRole = text(message, "role").toLowerCase(Locale.ROOT);
        Participant target = room.get(targetId);

        if (target == null) { sendError(session, "Participant not found."); return; }
        if (targetId.equals(user.getUserId())) { sendError(session, "Host cannot change their own role."); return; }
        if (!Set.of("participant", "moderator").contains(targetRole)) {
            sendError(session, "Role must be participant or moderator.");
            return;
        }

        synchronized (room) {
            target.setRole(targetRole);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "role_assigned");
        out.put("userId", target.getUserId());
        out.put("username", target.getUsername());
        out.put("role", target.getRole());
        out.put("participants", room.participantList());
        service.broadcast(room, out, null);
    }

    private void handleRemove(WebSocketSession session, Room room, Participant user, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), "remove_participant")) {
            sendError(session, "Only the Host can remove participants.");
            return;
        }

        String targetId = text(message, "userId");
        Participant target = room.get(targetId);
        if (target == null) { sendError(session, "Participant not found."); return; }
        if (targetId.equals(user.getUserId())) { sendError(session, "Host cannot remove themselves."); return; }

        Map<String, Object> removed = new LinkedHashMap<>();
        removed.put("type", "removed");
        removed.put("message", "You were removed from the room by the Host.");
        service.send(target.getSession(), removed);

        synchronized (room) {
            room.removeParticipant(targetId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "participant_removed");
        out.put("userId", targetId);
        out.put("participants", room.participantList());
        service.broadcast(room, out, null);
        closeQuietly(target.getSession());
    }

    private void handleTransferHost(WebSocketSession session, Room room, Participant user, JsonNode message) {
        if (!room.hasPermission(user.getUserId(), "transfer_host")) {
            sendError(session, "Only the Host can transfer the Host role.");
            return;
        }

        String targetId = text(message, "userId");
        Participant target = room.get(targetId);
        if (target == null || targetId.equals(user.getUserId())) {
            sendError(session, "Invalid host transfer target.");
            return;
        }

        synchronized (room) {
            user.setRole("participant");
            target.setRole("host");
        }
        sendRoleUpdate(room, target);
        sendRoleUpdate(room, user);
    }

    private void handleControlRequest(WebSocketSession session, Room room, Participant user, JsonNode message) {
        String action = text(message, "action");
        if (!Set.of("play", "pause", "seek", "change_video").contains(action)) {
            sendError(session, "Unsupported control request.");
            return;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "control_request");
        request.put("requestId", UUID.randomUUID().toString());
        request.put("userId", user.getUserId());
        request.put("username", user.getUsername());
        request.put("action", action);
        request.put("time", nullableValue(message, "time"));
        request.put("videoId", nullableValue(message, "videoId"));

        for (Participant approver : room.getParticipants().values()) {
            if (!approver.getUserId().equals(user.getUserId()) &&
                    Set.of("host", "moderator").contains(approver.getRole())) {
                service.send(approver.getSession(), request);
            }
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", "request_status");
        status.put("message", "Request sent to Host/Moderator.");
        service.send(session, status);
    }

    private void broadcastSync(Room room, String sourceUserId, String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "sync_state");
        out.putAll(room.statePayload());
        out.put("sourceUserId", sourceUserId);
        out.put("action", action);
        service.broadcast(room, out, null);
    }

    private void sendRoleUpdate(Room room, Participant participant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "role_assigned");
        out.put("userId", participant.getUserId());
        out.put("username", participant.getUsername());
        out.put("role", participant.getRole());
        out.put("participants", room.participantList());
        service.broadcast(room, out, null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = (String) session.getAttributes().get("roomId");
        String userId = (String) session.getAttributes().get("userId");
        if (roomId == null || userId == null) return;

        WatchPartyService.DisconnectResult result = service.disconnect(roomId, userId);
        if (result.room() == null || result.leaving() == null || result.empty()) return;

        if (result.newHost() != null) {
            Map<String, Object> transfer = new LinkedHashMap<>();
            transfer.put("type", "host_transferred");
            transfer.put("userId", result.newHost().getUserId());
            transfer.put("username", result.newHost().getUsername());
            transfer.put("participants", result.room().participantList());
            transfer.put("message", "The previous Host disconnected. Host role was transferred.");
            service.broadcast(result.room(), transfer, null);
        }

        Map<String, Object> left = new LinkedHashMap<>();
        left.put("type", "user_left");
        left.put("username", result.leaving().getUsername());
        left.put("userId", result.leaving().getUserId());
        left.put("participants", result.room().participantList());
        service.broadcast(result.room(), left, null);
    }

    private void sendError(WebSocketSession session, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("message", message);
        service.send(session, error);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Object nullableValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isNumber() ? value.numberValue() : value.asText();
    }

    private static double numberOr(JsonNode node, String field, double fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Math.max(0.0, value.asDouble()) : fallback;
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception ignored) {
        }
    }
}
