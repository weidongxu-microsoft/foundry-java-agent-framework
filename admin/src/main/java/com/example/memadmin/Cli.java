package com.example.memadmin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Small shared helpers for the admin CLI (env lookup, arg validation, confirmation prompts). */
final class Cli {

    private Cli() {
    }

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    static void requireArgs(String[] args, int min, String shape) {
        if (args.length < min) {
            System.err.println("Usage: " + shape);
            System.exit(2);
        }
    }

    static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }

    /** Interactive yes/no confirmation. Returns {@code true} immediately when {@code autoYes} is set. */
    static boolean confirm(String prompt, boolean autoYes) {
        if (autoYes) {
            return true;
        }
        System.out.print(prompt + " [y/N]: ");
        System.out.flush();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = r.readLine();
            return line != null && (line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes"));
        } catch (Exception e) {
            return false;
        }
    }
}
