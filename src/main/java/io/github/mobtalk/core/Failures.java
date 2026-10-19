package io.github.mobtalk.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** The words of a failure for the player: the cause behind a future's wrapper, without the exception's class name. */
public final class Failures {
    private Failures() { }
    public static String plain(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return "";
        // A wrapper that kept the class name in its message: "java.lang.IllegalStateException: У вас нет…".
        int colon = message.indexOf(": ");
        if (colon > 0 && message.substring(0, colon).matches("[a-zA-Z_$][\\w$.]*(Exception|Error)")) message = message.substring(colon + 2);
        return message.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
