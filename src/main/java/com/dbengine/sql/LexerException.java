package com.dbengine.sql;

public class LexerException extends RuntimeException {
    private final int position;

    public LexerException(String message, int position) {
        super(message + " (at position " + position + ")");
        this.position = position;
    }

    public int getPosition() {
        return position;
    }
}
