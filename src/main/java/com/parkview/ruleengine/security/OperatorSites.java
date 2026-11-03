package com.parkview.ruleengine.security;

import java.util.Set;

/**
 * Port: the sites an operator may work at. They are deliberately not a token claim (an access-control list inside a bearer
 * token stays valid after an administrator changed it and grows without bound); the sites are looked up here, owned by
 * authentication-service ({@code identity.user_sites}).
 */
public interface OperatorSites {

    /** @return the current site ids of the user; empty for an unknown user or a {@code null} / malformed id */
    Set<String> sitesOf(String userId);
}
