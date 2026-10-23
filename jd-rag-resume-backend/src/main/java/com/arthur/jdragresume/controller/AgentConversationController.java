package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationController {
    private final ConversationStore store;
    private final CurrentUserService users;

    public AgentConversationController(ConversationStore store, CurrentUserService users) {
        this.store = store;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<List<ConversationStore.Header>> list() { return ApiResponse.ok(store.list(users.getCurrentUser().getId())); }

    @PostMapping
    public ApiResponse<ConversationStore.Detail> create() { return ApiResponse.ok(store.create(users.getCurrentUser().getId())); }

    @GetMapping("/{id}")
    public ApiResponse<ConversationStore.Detail> detail(@PathVariable long id, @RequestParam(defaultValue = "0") long after) {
        return ApiResponse.ok(store.detail(users.getCurrentUser().getId(), id, after));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        store.delete(users.getCurrentUser().getId(), id);
        return ResponseEntity.noContent().build();
    }
}
