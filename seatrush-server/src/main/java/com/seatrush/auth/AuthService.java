package com.seatrush.auth;

import com.seatrush.auth.AuthDtos.LoginRequest;
import com.seatrush.auth.AuthDtos.RegisterRequest;
import com.seatrush.auth.AuthDtos.TokenResponse;
import com.seatrush.auth.AuthDtos.UserResponse;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UsernameTakenException(request.username());
        }
        String hash = passwordEncoder.encode(request.password());
        try {
            User user = userRepository.saveAndFlush(new User(request.username(), hash, Role.CUSTOMER));
            return UserResponse.from(user);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent sign-ups can both pass the exists check; the unique key decides
            throw new UsernameTakenException(request.username());
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return tokenService.issue(user);
    }
}