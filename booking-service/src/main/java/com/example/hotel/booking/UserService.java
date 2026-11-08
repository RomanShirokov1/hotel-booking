package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {
  private final UserRepository users;
  private final BookingRepository bookings;
  private final BookingMapper mapper;
  private final TokenService tokens;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  public UserService(UserRepository u, BookingRepository b, BookingMapper m, TokenService t) {
    users = u;
    bookings = b;
    mapper = m;
    tokens = t;
  }

  public TokenView register(Credentials c) {
    checkPassword(c.password());
    User u =
        users.saveAndFlush(new User(c.username(), passwords.encode(c.password()), User.Role.USER));
    return token(u);
  }

  public TokenView login(Credentials c) {
    User u =
        users
            .findByUsername(c.username())
            .orElseThrow(
                () -> new ApiException(401, "BAD_CREDENTIALS", "Invalid username or password"));
    if (!passwords.matches(c.password(), u.getPassword()))
      throw new ApiException(401, "BAD_CREDENTIALS", "Invalid username or password");
    return token(u);
  }

  private TokenView token(User u) {
    return new TokenView(tokens.user(u), "Bearer", 3600);
  }

  public UserView create(UserInput i) {
    checkPassword(i.password());
    return mapper.user(
        users.saveAndFlush(new User(i.username(), passwords.encode(i.password()), i.role())));
  }

  public UserView update(UserInput i) {
    if (i.id() == null) throw new ApiException(400, "MISSING_ID", "id required");
    checkPassword(i.password());
    User u = require(i.id());
    u.setUsername(i.username());
    u.setPassword(passwords.encode(i.password()));
    u.setRole(i.role());
    return mapper.user(u);
  }

  public void delete(Long id) {
    User u = require(id);
    if (bookings.existsByUserId(id)) throw ApiException.conflict("User has booking history");
    users.delete(u);
  }

  public UserView get(Long id) {
    return mapper.user(require(id));
  }

  public PageResult<UserView> list(int page, int size) {
    return PageResult.of(
        users.findAll().stream()
            .sorted(Comparator.comparing(User::getId))
            .map(mapper::user)
            .toList(),
        page,
        size);
  }

  private User require(Long id) {
    return users.findById(id).orElseThrow(ApiException::missing);
  }

  private void checkPassword(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(400, "INVALID_PASSWORD", "Password must not exceed 72 UTF-8 bytes");
  }
}
