package com.arthur.jdragresume.security;

import java.security.Principal;

/** Identity derived from a verified JWT, never from an arbitrary authentication name. */
public record JwtUserPrincipal(long userId, String username) implements Principal {
    @Override
    public String getName() {
        return username;
    }
}
