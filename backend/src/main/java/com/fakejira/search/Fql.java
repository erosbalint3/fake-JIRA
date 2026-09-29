package com.fakejira.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FakeJIRA Query Language: a small JQL-like language.
 *
 * <pre>
 * project = WEB AND status in (todo, "in progress") AND assignee = me AND due &lt; +7d ORDER BY priority DESC
 * </pre>
 *
 * Clauses: {@code field op value}, {@code field [not] in (a, b)}, {@code field is [not] empty}; combined with
 * AND, OR, NOT and parentheses. Values are words, quoted strings or functions such as {@code me()},
 * {@code membersOf("design")}.
 */
public final class Fql {

    private Fql() {
    }

    // ------------------------------------------------------------------ AST

    public sealed interface Node permits And, Or, Not, Clause {
    }

    public record And(Node left, Node right) implements Node {
    }

    public record Or(Node left, Node right) implements Node {
    }

    public record Not(Node inner) implements Node {
    }

    public enum Op { EQ, NE, GT, GE, LT, LE, CONTAINS, NOT_CONTAINS, IN, NOT_IN, EMPTY, NOT_EMPTY }

    /** A value: plain text, or a function call like {@code membersOf("design")} (then {@code argument} is set). */
    public record Value(String text, boolean function, String argument, int position) {
        public String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    public record Clause(String field, Op op, List<Value> values, int position) implements Node {
    }

    public record Sort(String field, boolean descending) {
    }

    /** {@code where} is null for a query with only ORDER BY (or nothing). */
    public record Query(Node where, List<Sort> order) {
    }

    /** A syntax or meaning error at a character position of the query. */
    public static class FqlException extends RuntimeException {
        private final int position;

        public FqlException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    // ------------------------------------------------------------------ tokens

    enum Kind { WORD, STRING, LPAREN, RPAREN, COMMA, OP, END }

    record Token(Kind kind, String text, int position) {
        boolean is(String keyword) {
            return kind == Kind.WORD && text.equalsIgnoreCase(keyword);
        }
    }

    static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                tokens.add(new Token(Kind.LPAREN, "(", i++));
            } else if (c == ')') {
                tokens.add(new Token(Kind.RPAREN, ")", i++));
            } else if (c == ',') {
                tokens.add(new Token(Kind.COMMA, ",", i++));
            } else if (c == '"' || c == '\'') {
                int start = i++;
                StringBuilder text = new StringBuilder();
                while (i < input.length() && input.charAt(i) != c) {
                    if (input.charAt(i) == '\\' && i + 1 < input.length()) {
                        i++;
                    }
                    text.append(input.charAt(i++));
                }
                if (i >= input.length()) {
                    throw new FqlException("Missing closing quote.", start);
                }
                i++;
                tokens.add(new Token(Kind.STRING, text.toString(), start));
            } else if (c == '!' || c == '=' || c == '<' || c == '>' || c == '~') {
                int start = i;
                String two = i + 1 < input.length() ? input.substring(i, i + 2) : "";
                if (two.equals("!=") || two.equals(">=") || two.equals("<=") || two.equals("!~")) {
                    tokens.add(new Token(Kind.OP, two, start));
                    i += 2;
                } else if (c == '!') {
                    throw new FqlException("Unexpected '!'. Did you mean != or !~ ?", start);
                } else {
                    tokens.add(new Token(Kind.OP, String.valueOf(c), start));
                    i++;
                }
            } else if (isWordChar(c)) {
                int start = i;
                while (i < input.length() && isWordChar(input.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, input.substring(start, i), start));
            } else {
                throw new FqlException("Unexpected character '" + c + "'.", i);
            }
        }
        tokens.add(new Token(Kind.END, "", input.length()));
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+' || c == '.' || c == ':' || c == '@'
                || c == '/' || c == '#';
    }

    // ------------------------------------------------------------------ parser

    private static final Set<String> RESERVED = Set.of("and", "or", "not", "in", "is", "empty", "null", "order", "by");

    public static Query parse(String input) {
        return new Parser(tokenize(input == null ? "" : input)).query();
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Token peek() {
            return tokens.get(index);
        }

        Token next() {
            return tokens.get(index++);
        }

        Query query() {
            Node where = null;
            if (peek().kind() != Kind.END && !peek().is("order")) {
                where = or();
            }
            List<Sort> order = new ArrayList<>();
            if (peek().is("order")) {
                next();
                if (!next().is("by")) {
                    throw new FqlException("Expected BY after ORDER.", tokens.get(index - 1).position());
                }
                do {
                    Token field = next();
                    if (field.kind() != Kind.WORD) {
                        throw new FqlException("Expected a field to sort by.", field.position());
                    }
                    boolean desc = false;
                    if (peek().is("desc")) {
                        next();
                        desc = true;
                    } else if (peek().is("asc")) {
                        next();
                    }
                    order.add(new Sort(field.text().toLowerCase(Locale.ROOT), desc));
                } while (peek().kind() == Kind.COMMA && next() != null);
            }
            if (peek().kind() != Kind.END) {
                Token t = peek();
                throw new FqlException(t.is("and") || t.is("or") ? "Expected a clause before " + t.text().toUpperCase(Locale.ROOT) + "."
                        : "Unexpected '" + t.text() + "'. Join clauses with AND or OR.", t.position());
            }
            return new Query(where, order);
        }

        Node or() {
            Node left = and();
            while (peek().is("or")) {
                next();
                left = new Or(left, and());
            }
            return left;
        }

        Node and() {
            Node left = not();
            while (peek().is("and")) {
                next();
                left = new And(left, not());
            }
            return left;
        }

        Node not() {
            if (peek().is("not")) {
                next();
                return new Not(not());
            }
            return primary();
        }

        Node primary() {
            Token t = peek();
            if (t.kind() == Kind.LPAREN) {
                next();
                Node inner = or();
                expect(Kind.RPAREN, "Missing closing parenthesis.");
                return inner;
            }
            // Custom fields with spaces are written in quotes: "Customer name" = acme.
            if ((t.kind() != Kind.WORD && t.kind() != Kind.STRING)
                    || t.kind() == Kind.WORD && RESERVED.contains(t.text().toLowerCase(Locale.ROOT))) {
                throw new FqlException(t.kind() == Kind.END ? "The query ends too early: expected a clause."
                        : "Expected a field name, found '" + t.text() + "'.", t.position());
            }
            next();
            String field = t.text().toLowerCase(Locale.ROOT);
            Token op = next();
            if (op.is("is")) {
                boolean negated = false;
                if (peek().is("not")) {
                    next();
                    negated = true;
                }
                Token empty = next();
                if (!empty.is("empty") && !empty.is("null")) {
                    throw new FqlException("Expected EMPTY after IS.", empty.position());
                }
                return new Clause(field, negated ? Op.NOT_EMPTY : Op.EMPTY, List.of(), t.position());
            }
            if (op.is("in")) {
                return new Clause(field, Op.IN, list(), t.position());
            }
            if (op.is("not")) {
                Token in = next();
                if (!in.is("in")) {
                    throw new FqlException("Expected IN after NOT.", in.position());
                }
                return new Clause(field, Op.NOT_IN, list(), t.position());
            }
            if (op.kind() != Kind.OP) {
                throw new FqlException(op.kind() == Kind.END ? "Expected an operator after " + t.text() + "."
                        : "Expected an operator (=, !=, <, >, ~, IN, IS) after " + t.text() + ", found '" + op.text() + "'.",
                        op.position());
            }
            Op operator = switch (op.text()) {
                case "=" -> Op.EQ;
                case "!=" -> Op.NE;
                case ">" -> Op.GT;
                case ">=" -> Op.GE;
                case "<" -> Op.LT;
                case "<=" -> Op.LE;
                case "~" -> Op.CONTAINS;
                default -> Op.NOT_CONTAINS;
            };
            if (peek().is("empty") || peek().is("null")) {
                Token empty = next();
                if (operator != Op.EQ && operator != Op.NE) {
                    throw new FqlException("Use = EMPTY or != EMPTY.", empty.position());
                }
                return new Clause(field, operator == Op.EQ ? Op.EMPTY : Op.NOT_EMPTY, List.of(), t.position());
            }
            return new Clause(field, operator, List.of(value()), t.position());
        }

        List<Value> list() {
            List<Value> values = new ArrayList<>();
            if (peek().kind() != Kind.LPAREN) {
                // A single function like membersOf("x") or a bare value is accepted too.
                values.add(value());
                return values;
            }
            next();
            values.add(value());
            while (peek().kind() == Kind.COMMA) {
                next();
                values.add(value());
            }
            expect(Kind.RPAREN, "Missing ')' after the list.");
            return values;
        }

        Value value() {
            Token t = next();
            if (t.kind() == Kind.STRING) {
                return new Value(t.text(), false, null, t.position());
            }
            if (t.kind() != Kind.WORD) {
                throw new FqlException(t.kind() == Kind.END ? "The query ends too early: expected a value." : "Expected a value, found '" + t.text() + "'.",
                        t.position());
            }
            if (peek().kind() == Kind.LPAREN) {
                next();
                String argument = null;
                if (peek().kind() != Kind.RPAREN) {
                    Token arg = next();
                    if (arg.kind() != Kind.WORD && arg.kind() != Kind.STRING) {
                        throw new FqlException("Expected a function argument.", arg.position());
                    }
                    argument = arg.text();
                }
                expect(Kind.RPAREN, "Missing ')' after the function.");
                return new Value(t.text(), true, argument, t.position());
            }
            return new Value(t.text(), false, null, t.position());
        }

        void expect(Kind kind, String message) {
            Token t = next();
            if (t.kind() != kind) {
                throw new FqlException(message, t.position());
            }
        }
    }
}
