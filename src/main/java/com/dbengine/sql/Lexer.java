package com.dbengine.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lexer {
    private final String input;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0;
    private int current = 0;

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        // Keywords mapped to uppercase internally for case-insensitive lookup
        KEYWORDS.put("CREATE", TokenType.CREATE);
        KEYWORDS.put("DATABASE", TokenType.DATABASE);
        KEYWORDS.put("TABLE", TokenType.TABLE);
        KEYWORDS.put("COLUMN", TokenType.COLUMN);
        KEYWORDS.put("USE", TokenType.USE);
        KEYWORDS.put("DROP", TokenType.DROP);
        KEYWORDS.put("SHOW", TokenType.SHOW);
        KEYWORDS.put("TABLES", TokenType.TABLES);
        KEYWORDS.put("DATABASES", TokenType.DATABASES);
        KEYWORDS.put("ALTER", TokenType.ALTER);
        KEYWORDS.put("ADD", TokenType.ADD);
        KEYWORDS.put("RENAME", TokenType.RENAME);
        KEYWORDS.put("TO", TokenType.TO);
        KEYWORDS.put("TRUNCATE", TokenType.TRUNCATE);
        KEYWORDS.put("DESCRIBE", TokenType.DESCRIBE);
        KEYWORDS.put("DESC", TokenType.DESC);
        KEYWORDS.put("INSERT", TokenType.INSERT);
        KEYWORDS.put("INTO", TokenType.INTO);
        KEYWORDS.put("VALUES", TokenType.VALUES);
        KEYWORDS.put("SELECT", TokenType.SELECT);
        KEYWORDS.put("FROM", TokenType.FROM);
        KEYWORDS.put("WHERE", TokenType.WHERE);
        KEYWORDS.put("UPDATE", TokenType.UPDATE);
        KEYWORDS.put("SET", TokenType.SET);
        KEYWORDS.put("DELETE", TokenType.DELETE);
        KEYWORDS.put("AND", TokenType.AND);
        KEYWORDS.put("OR", TokenType.OR);
        KEYWORDS.put("PRIMARY", TokenType.PRIMARY);
        KEYWORDS.put("KEY", TokenType.KEY);
        KEYWORDS.put("NOT", TokenType.NOT);
        KEYWORDS.put("NULL", TokenType.NULL);
        KEYWORDS.put("INT", TokenType.INT);
        KEYWORDS.put("VARCHAR", TokenType.VARCHAR);
        KEYWORDS.put("BOOLEAN", TokenType.BOOLEAN);
        KEYWORDS.put("FLOAT", TokenType.FLOAT);
        KEYWORDS.put("TRUE", TokenType.BOOLEAN_LITERAL);
        KEYWORDS.put("FALSE", TokenType.BOOLEAN_LITERAL);
        KEYWORDS.put("GROUP", TokenType.GROUP);
        KEYWORDS.put("BY", TokenType.BY);
        KEYWORDS.put("ORDER", TokenType.ORDER);
        KEYWORDS.put("ASC", TokenType.ASC);
        KEYWORDS.put("LIMIT", TokenType.LIMIT);
        KEYWORDS.put("DISTINCT", TokenType.DISTINCT);
        KEYWORDS.put("COUNT", TokenType.COUNT);
        KEYWORDS.put("SUM", TokenType.SUM);
        KEYWORDS.put("AVG", TokenType.AVG);
        KEYWORDS.put("MIN", TokenType.MIN);
        KEYWORDS.put("MAX", TokenType.MAX);
        KEYWORDS.put("AS", TokenType.AS);
        KEYWORDS.put("INNER", TokenType.INNER);
        KEYWORDS.put("JOIN", TokenType.JOIN);
        KEYWORDS.put("ON", TokenType.ON);
        KEYWORDS.put("BEGIN", TokenType.BEGIN);
        KEYWORDS.put("TRANSACTION", TokenType.TRANSACTION);
        KEYWORDS.put("COMMIT", TokenType.COMMIT);
        KEYWORDS.put("ROLLBACK", TokenType.ROLLBACK);
    }

    public Lexer(String input) {
        this.input = input;
    }

    public List<Token> tokenize() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", null, current));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(': addToken(TokenType.LPAREN); break;
            case ')': addToken(TokenType.RPAREN); break;
            case ',': addToken(TokenType.COMMA); break;
            case ';': addToken(TokenType.SEMICOLON); break;
            case '*': addToken(TokenType.STAR); break;
            case '=': addToken(TokenType.EQUALS); break;
            case '.': addToken(TokenType.DOT); break;
            case '"': quotedIdentifier(); break;
            case '\'': string(); break;
            case '!':
                if (match('=')) addToken(TokenType.NOT_EQUALS);
                else throw new LexerException("Unexpected character '!' without '='", start);
                break;
            case '<':
                addToken(match('=') ? TokenType.LTE : TokenType.LT);
                break;
            case '>':
                addToken(match('=') ? TokenType.GTE : TokenType.GT);
                break;
            case ' ':
            case '\r':
            case '\t':
            case '\n':
                break;
            case '-':
                if (match('-')) {
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else {
                    throw new LexerException("Unexpected character '-'", start);
                }
                break;
            default:
                if (isDigit(c)) number();
                else if (isAlpha(c)) identifier();
                else throw new LexerException("Unexpected character '" + c + "'", start);
                break;
        }
    }

    private void string() {
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd()) {
            if (peek() == '\'') {
                if (peekNext() == '\'') {
                    sb.append('\'');
                    advance(); advance();
                } else {
                    advance();
                    addToken(TokenType.STRING_LITERAL, sb.toString());
                    return;
                }
            } else {
                sb.append(advance());
            }
        }
        throw new LexerException("Unterminated string literal", start);
    }

    private void number() {
        boolean isFloat = false;
        while (isDigit(peek())) advance();

        if (peek() == '.' && isDigit(peekNext())) {
            isFloat = true;
            advance();
            while (isDigit(peek())) advance();
        }

        String lexeme = input.substring(start, current);
        if (isFloat) addToken(TokenType.FLOAT_LITERAL, Float.parseFloat(lexeme));
        else addToken(TokenType.INT_LITERAL, Integer.parseInt(lexeme));
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();

        String text = input.substring(start, current);

        // 1. Check keywords case-insensitively
        TokenType type = KEYWORDS.get(text.toUpperCase());

        if (type == null) {
            // FIX: Unquoted identifiers MUST preserve exact case per the MySQL rule set!
            addToken(TokenType.IDENTIFIER, text, null);
        } else if (type == TokenType.BOOLEAN_LITERAL) {
            addToken(TokenType.BOOLEAN_LITERAL, Boolean.parseBoolean(text.toLowerCase()));
        } else {
            addToken(type, text, null);
        }
    }

    private void quotedIdentifier() {
        while (peek() != '"' && !isAtEnd()) advance();

        if (isAtEnd()) throw new LexerException("Unterminated quoted identifier", start);

        advance(); // Consume '"'

        // Quoted identifiers preserve exact case and can contain spaces/keywords
        String exactValue = input.substring(start + 1, current - 1);
        addToken(TokenType.IDENTIFIER, exactValue, null);
    }

    private boolean match(char expected) {
        if (isAtEnd() || input.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private char peek() { return isAtEnd() ? '\0' : input.charAt(current); }
    private char peekNext() { return current + 1 >= input.length() ? '\0' : input.charAt(current + 1); }
    private boolean isAlpha(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
    private boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private boolean isAlphaNumeric(char c) { return isAlpha(c) || isDigit(c); }
    private char advance() { return input.charAt(current++); }
    private boolean isAtEnd() { return current >= input.length(); }

    private void addToken(TokenType type) { addToken(type, null); }
    private void addToken(TokenType type, Object literal) {
        tokens.add(new Token(type, input.substring(start, current), literal, start));
    }
    private void addToken(TokenType type, String customLexeme, Object literal) {
        tokens.add(new Token(type, customLexeme, literal, start));
    }
}

