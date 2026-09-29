package com.fakejira.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AppSettings {

    static final String REGISTRATION_MODE = "registration.mode";

    private final AppSettingRepository settings;
    private final RegistrationMode defaultMode;

    public AppSettings(AppSettingRepository settings,
                       @Value("${app.registration.default-mode:OPEN}") RegistrationMode defaultMode) {
        this.settings = settings;
        this.defaultMode = defaultMode;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return settings.findById(key).map(AppSetting::getValue);
    }

    /** Insert or update; save() merges by key, so this works with or without a surrounding transaction. */
    public void put(String key, String value) {
        settings.save(new AppSetting(key, value));
    }

    /** The admin's choice if set, otherwise APP_REGISTRATION_DEFAULT_MODE. */
    public RegistrationMode registrationMode() {
        return get(REGISTRATION_MODE).map(RegistrationMode::valueOf).orElse(defaultMode);
    }

    public void setRegistrationMode(RegistrationMode mode) {
        put(REGISTRATION_MODE, mode.name());
    }
}
