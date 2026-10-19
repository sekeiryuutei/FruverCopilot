package com.fruver.interfaces.rest;

import com.fruver.application.service.Actor;
import com.fruver.application.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Size(min = 6) String password,
            String role
    ) {}

    public record UpdatePasswordRequest(
            @NotBlank @Size(min = 6) String password
    ) {}

    public record UpdateEnabledRequest(
            boolean enabled
    ) {}

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserService.UserView> list() {
        return userService.list();
    }

    @PostMapping
    public UserService.UserView create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request.username(), request.password(), request.role(), Actor.name());
    }

    @PutMapping("/{username}/password")
    public void updatePassword(@PathVariable String username, @Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(username, request.password(), Actor.name());
    }

    @PutMapping("/{username}/enabled")
    public void updateEnabled(@PathVariable String username, @RequestBody UpdateEnabledRequest request) {
        userService.setEnabled(username, request.enabled(), Actor.name());
    }

    @DeleteMapping("/{username}")
    public void delete(@PathVariable String username) {
        userService.delete(username, Actor.name());
    }
}

