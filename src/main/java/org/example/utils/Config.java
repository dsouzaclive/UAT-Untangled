package org.example.utils;

public class Config {

    public static final String SUPABASE_URL =
            getRequiredEnvironmentVariable("SUPABASE_URL");

    public static final String SUPABASE_KEY =
            getRequiredEnvironmentVariable("SUPABASE_KEY");

    private static String getRequiredEnvironmentVariable(String name) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: " + name
            );
        }

        return value;
    }

    private Config() {}
}
