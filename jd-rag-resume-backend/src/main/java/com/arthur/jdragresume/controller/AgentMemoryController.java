package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.agent.AgentMemoryService;
import com.arthur.jdragresume.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/memories")
public class AgentMemoryController {
    private final AgentMemoryService service;

    public AgentMemoryController(AgentMemoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AgentMemoryService.MemoryView>> list() {
        return ApiResponse.ok(service.list());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
