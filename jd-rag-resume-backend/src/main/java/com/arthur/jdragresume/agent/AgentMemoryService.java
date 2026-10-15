package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.entity.AgentMemory;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AgentMemoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AgentMemoryService {
    private final AgentMemoryRepository repository;
    private final CurrentUserService currentUserService;
    private final AgentMemorySearch search;
    private final AgentProperties properties;

    public AgentMemoryService(AgentMemoryRepository repository, CurrentUserService currentUserService,
                              AgentMemorySearch search, AgentProperties properties) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.search = search;
        this.properties = properties;
    }

    @Transactional
    public List<MemoryView> rememberAndRecall(String question, boolean remember) {
        AppUser user = currentUserService.getCurrentUser();
        List<AgentMemory> memories = new ArrayList<>(repository.findByUserIdOrderByIdDesc(user.getId()));
        if (remember) {
            for (var fact : ExplicitMemoryPolicy.extract(question)) {
                boolean exists = memories.stream().anyMatch(memory -> memory.getContent().equals(fact.content()));
                if (exists) continue;
                AgentMemory memory = new AgentMemory();
                memory.setUser(user);
                memory.setCategory(fact.category());
                memory.setContent(fact.content());
                memories.addFirst(repository.save(memory));
            }
        }
        return search.search(user.getId(), question, memories, properties.getMemoryRecallLimit()).stream()
                .map(MemoryView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryView> list() {
        return repository.findByUserIdOrderByIdDesc(currentUserService.getCurrentUser().getId()).stream()
                .map(MemoryView::from).toList();
    }

    @Transactional
    public void delete(Long id) {
        AgentMemory memory = repository.findByIdAndUserId(id, currentUserService.getCurrentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("memory", id));
        repository.delete(memory);
    }

    public record MemoryView(Long id, String category, String content, LocalDateTime createdAt) {
        static MemoryView from(AgentMemory memory) {
            return new MemoryView(memory.getId(), memory.getCategory(), memory.getContent(), memory.getCreatedAt());
        }
    }
}
