package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
  private final UserService service;

  public UserController(UserService s) {
    service = s;
  }

  @PostMapping("/user/register")
  @ResponseStatus(HttpStatus.CREATED)
  public TokenView register(@Valid @RequestBody Credentials c) {
    return service.register(c);
  }

  @PostMapping("/user/auth")
  public TokenView auth(@Valid @RequestBody Credentials c) {
    return service.login(c);
  }

  @PostMapping("/user")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public UserView create(@Valid @RequestBody UserInput i) {
    return service.create(i);
  }

  @PatchMapping("/user")
  @PreAuthorize("hasRole('ADMIN')")
  public UserView update(@Valid @RequestBody UserInput i) {
    return service.update(i);
  }

  @DeleteMapping("/user")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@RequestParam Long id) {
    service.delete(id);
  }

  @GetMapping("/user/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public UserView user(@PathVariable Long id) {
    return service.get(id);
  }

  @GetMapping("/user")
  @PreAuthorize("hasRole('ADMIN')")
  public PageResult<UserView> users(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.list(page, size);
  }
}
