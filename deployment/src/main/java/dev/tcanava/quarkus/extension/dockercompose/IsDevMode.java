package dev.tcanava.quarkus.extension.dockercompose;

import io.quarkus.runtime.LaunchMode;

import java.util.function.BooleanSupplier;

public class IsDevMode implements BooleanSupplier {
    private final LaunchMode launchMode;

    public IsDevMode(LaunchMode launchMode) {
        this.launchMode = launchMode;
    }

    public boolean getAsBoolean() {
        return launchMode == LaunchMode.DEVELOPMENT;
    }
}
