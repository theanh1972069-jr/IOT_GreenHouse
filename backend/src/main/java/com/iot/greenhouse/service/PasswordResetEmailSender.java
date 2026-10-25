package com.iot.greenhouse.service;

public interface PasswordResetEmailSender {
    void sendResetLink(String email, String rawToken);
}
