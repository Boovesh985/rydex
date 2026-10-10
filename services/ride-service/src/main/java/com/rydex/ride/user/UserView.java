package com.rydex.ride.user;

import java.util.UUID;

public record UserView(UUID id, String name, String email, String phone, Role role) {

    public static UserView of(User u) {
        return new UserView(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole());
    }
}
