package io.github.xw66.opsflow.auth;

import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opsflow.bootstrap.enabled", havingValue = "true")
public class BootstrapAdmin implements ApplicationRunner {
    private final AuthService service;
    private final Validator validator;
    private final AuthController.RegisterRequest request;

    public BootstrapAdmin(AuthService service, Validator validator,
            @Value("${opsflow.bootstrap.username}") String username,
            @Value("${opsflow.bootstrap.password}") String password) {
        this.service = service;
        this.validator = validator;
        this.request = new AuthController.RegisterRequest(username, password, "管理员");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("管理员初始化参数不合法");
        service.bootstrapAdmin(request);
    }
}
