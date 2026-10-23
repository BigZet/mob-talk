package io.github.mobtalk.core;

import java.util.UUID;

/** UUID жителя нужного пола: житель не носит имя другого пола, поэтому тестам с «Анной» нужна жительница. */
final class Sexed {
    private Sexed() { }
    static UUID id(Gender wanted) {
        while (true) { UUID id = UUID.randomUUID(); if (SocialProfile.genderOf(id) == wanted) return id; }
    }
}
