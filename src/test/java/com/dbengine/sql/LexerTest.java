package com.dbengine.sql;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    @Test
    void testTokensKeywordsAndIdentifiers() {
        String input = "SeLeCt * FrOm Users WHERE \"ExactCaseName\" = 'John';";
        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());

        // FIX: Unquoted identifiers now preserve their exact typed case!
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("Users", tokens.get(3).lexeme());

        assertEquals(TokenType.WHERE, tokens.get(4).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(5).type());
        assertEquals("ExactCaseName", tokens.get(5).lexeme());
        assertEquals(TokenType.EQUALS, tokens.get(6).type());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(7).type());
        assertEquals("John", tokens.get(7).literalValue());
    }

    @Test
    void testStringLiteralWithEscape() {
        String input = "INSERT INTO names VALUES ('O''Connor', 'Regular String');";
        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        // Index 4 is the '(' token, Index 5 is the first string literal
        assertEquals(TokenType.STRING_LITERAL, tokens.get(5).type());
        assertEquals("O'Connor", tokens.get(5).literalValue()); // Verify escaped quote collapsed

        // Index 6 is the ',' token, Index 7 is the second string literal
        assertEquals(TokenType.STRING_LITERAL, tokens.get(7).type());
        assertEquals("Regular String", tokens.get(7).literalValue());
    }

    @Test
    void testMultiStatementSplitOnSemicolons() {
        String input = "SELECT * FROM users;\nINSERT INTO users VALUES (1, 'admin');";
        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        // First statement termination
        assertEquals(TokenType.SEMICOLON, tokens.get(4).type());
        // Second statement start
        assertEquals(TokenType.INSERT, tokens.get(5).type());
        // Second statement termination (pushed to index 14 because of the VALUES parens and commas)
        assertEquals(TokenType.SEMICOLON, tokens.get(14).type());
        assertEquals(TokenType.EOF, tokens.get(15).type());
    }

    @Test
    void testMalformedTokensThrowException() {
        String invalidChar = "SELECT @ FROM users;";
        LexerException ex1 = assertThrows(LexerException.class, () -> new Lexer(invalidChar).tokenize());
        assertTrue(ex1.getMessage().contains("Unexpected character '@'"));
        assertEquals(7, ex1.getPosition());

        String incompleteNotEquals = "SELECT * FROM users WHERE id ! 5;";
        LexerException ex2 = assertThrows(LexerException.class, () -> new Lexer(incompleteNotEquals).tokenize());
        assertTrue(ex2.getMessage().contains("Unexpected character '!' without '='"));

        String unterminatedString = "INSERT INTO users VALUES ('Broken string);";
        LexerException ex3 = assertThrows(LexerException.class, () -> new Lexer(unterminatedString).tokenize());
        assertTrue(ex3.getMessage().contains("Unterminated string literal"));
    }
}
