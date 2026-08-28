package com.dbengine.sql;

public class ParseException extends RuntimeException {
    private final Token token;

    public ParseException(Token token, String message) {
        super(String.format("Syntax Error at %s: %s",
                token.type() == TokenType.EOF ? "end of input" : "'" + token.lexeme() + "' (Position: " + token.position() + ")",
                message));
        this.token = token;
    }

    public Token getToken() {
        return token;
    }
}
