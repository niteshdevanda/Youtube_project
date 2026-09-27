package com.grras.watchparty.controller;

import com.grras.watchparty.service.WatchPartyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final WatchPartyService service;

    public HealthController(WatchPartyService service) {
        this.service = service;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("rooms", service.roomCount());
        result.put("service", "youtube-watch-party-backend");
        return result;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("service", "YouTube Watch Party API");
        result.put("websocket", "/ws");
        result.put("health", "/api/health");
        return result;
    }
}
