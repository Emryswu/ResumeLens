package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.entity.AppUser;

/** Resolved once per turn on the request thread; services still re-check ownership themselves. */
public record AgentToolContext(AppUser user) {
}
