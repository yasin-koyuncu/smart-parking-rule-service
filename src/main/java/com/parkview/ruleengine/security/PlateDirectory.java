package com.parkview.ruleengine.security;

import java.util.Set;

/**
 * Port: the plates a driver has registered. They are deliberately not a token claim (personal data, stale until the token is
 * renewed, unbounded size): every object-level check on a plate asks the directory.
 */
public interface PlateDirectory {

    /** The normalised plates of the user; empty for an unknown user or an id that is not a user id. */
    Set<String> platesOf(String userId);
}
