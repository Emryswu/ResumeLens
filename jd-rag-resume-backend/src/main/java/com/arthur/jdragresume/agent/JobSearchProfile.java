package com.arthur.jdragresume.agent;

/**
 * The facts the user stated about themselves that act as hard search conditions. Read from the user's saved
 * preferences on every check, never from the conversation, so neither the model nor chat text can change them.
 */
@FunctionalInterface
public interface JobSearchProfile {
    JobSearchProfile UNKNOWN = userId -> null;

    /** True or false as the user filled it in; null when unknown, which is never treated as a restriction. */
    Boolean currentlyStudent(Long userId);
}
