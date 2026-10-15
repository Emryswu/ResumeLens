package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AgentMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {
    List<AgentMemory> findByUserIdOrderByIdDesc(Long userId);
    Optional<AgentMemory> findByIdAndUserId(Long id, Long userId);
}
