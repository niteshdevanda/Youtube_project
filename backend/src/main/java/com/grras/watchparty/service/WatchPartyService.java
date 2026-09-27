package com.grras.watchparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grras.watchparty.model.Participant;
import com.grras.watchparty.model.Room;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WatchPartyService {
    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile("(?:v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/shorts/)([A-Za-z0-9_-]{11})");

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String defaultVideoId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public WatchPartyService(@Value("${app.default-video-id:M7lc1UVf-VE}") String defaultVideoId) {
        this.defaultVideoId = defaultVideoId;
    }

    public int roomCount() {
        return rooms.size();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String newUserId() {
        return UUID.randomUUID().toString();
    }

    public JoinResult join(String roomId, String username, String userId, WebSocketSession session) {
        lock.writeLock().lock();
        try {
            Room room = rooms.get(roomId);
            String role;
            if (room == null) {
                room = new Room(roomId, defaultVideoId);
                rooms.put(roomId, room);
                role = "host";
            } else {
                role = "participant";
            }

            Participant participant = new Participant(userId, username, role, session);
            room.addParticipant(participant);
            connections.put(userId, session);

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("type", "joined");
            snapshot.put("userId", userId);
            snapshot.put("roomId", roomId);
            snapshot.put("username", username);
            snapshot.put("role", role);
            snapshot.put("state", room.statePayload());
            snapshot.put("participants", room.participantList());

            return new JoinResult(room, participant, snapshot);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void send(WebSocketSession session, Map<String, Object> message) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (IOException ignored) {
        }
    }

    public void broadcast(Room room, Map<String, Object> message, String excludeUserId) {
        String payload;
        try {
            payload = mapper.writeValueAsString(message);
        } catch (Exception e) {
            return;
        }

        lock.readLock().lock();
        try {
            for (Participant participant : new ArrayList<>(room.getParticipants().values())) {
                if (Objects.equals(participant.getUserId(), excludeUserId)) continue;
                WebSocketSession session = participant.getSession();
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage(payload));
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public Participant getParticipant(String roomId, String userId) {
        Room room = rooms.get(roomId);
        return room == null ? null : room.get(userId);
    }

    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public boolean removeParticipant(String roomId, String userId) {
        lock.writeLock().lock();
        try {
            Room room = rooms.get(roomId);
            if (room == null) return false;
            room.removeParticipant(userId);
            connections.remove(userId);
            if (room.getParticipants().isEmpty()) {
                rooms.remove(roomId);
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public DisconnectResult disconnect(String roomId, String userId) {
        lock.writeLock().lock();
        try {
            Room room = rooms.get(roomId);
            if (room == null) return new DisconnectResult(null, null, null, false);

            Participant leaving = room.get(userId);
            if (leaving == null) return new DisconnectResult(room, null, null, false);

            boolean wasHost = "host".equals(leaving.getRole());
            room.removeParticipant(userId);
            connections.remove(userId);

            Participant newHost = null;
            if (wasHost && !room.getParticipants().isEmpty()) {
                newHost = room.getParticipants().values().stream()
                        .min(Comparator.comparingLong(Participant::getJoinedAt))
                        .orElse(null);
                if (newHost != null) newHost.setRole("host");
            }

            boolean empty = room.getParticipants().isEmpty();
            if (empty) rooms.remove(roomId);

            return new DisconnectResult(room, leaving, newHost, empty);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String normalizeRoomId(Object value) {
        String valueString = value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return valueString.replaceAll("[^A-Z0-9]", "").substring(0, Math.min(12, valueString.replaceAll("[^A-Z0-9]", "").length()));
    }

    public static String normalizeUsername(Object value) {
        String username = value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ");
        if (username.length() > 30) username = username.substring(0, 30);
        return username.isEmpty() ? "Guest" : username;
    }

    public static String extractVideoId(Object value) {
        if (value == null) return null;
        String input = String.valueOf(value).trim();
        if (input.isEmpty()) return null;
        if (input.length() == 11 && !input.contains("/") && !input.contains(" ")) return input;
        Matcher matcher = VIDEO_ID_PATTERN.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    public record JoinResult(Room room, Participant participant, Map<String, Object> snapshot) {}
    public record DisconnectResult(Room room, Participant leaving, Participant newHost, boolean empty) {}
}
