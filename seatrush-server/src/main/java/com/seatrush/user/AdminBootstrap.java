package com.seatrush.user;

import com.seatrush.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the admin account on startup if it does not exist yet. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          SecurityProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(properties.adminUsername())) {
            return;
        }
        String hash = passwordEncoder.encode(properties.adminPassword());
        userRepository.save(new User(properties.adminUsername(), hash, Role.ADMIN));
        log.info("Created admin user '{}'", properties.adminUsername());
    }
}