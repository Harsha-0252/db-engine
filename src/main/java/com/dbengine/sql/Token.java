package com.dbengine.sql;

public record Token(TokenType type, String lexeme, Object literalValue, int position) {
    @Override
    public String toString() {
        if (literalValue != null) {
            return String.format("[%s '%s' val=%s @%d]", type, lexeme, literalValue, position);
        }
        return String.format("[%s '%s' @%d]", type, lexeme, position);
    }
}
