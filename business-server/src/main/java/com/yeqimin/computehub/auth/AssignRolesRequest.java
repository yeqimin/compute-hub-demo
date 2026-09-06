package com.yeqimin.computehub.auth;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record AssignRolesRequest(@NotEmpty Set<String> roleCodes) {}
