package coeusyk.game.chess.core.book;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpeningBookTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
            "w, e1h1, 60, 62, castle-k",
            "w, e1a1, 60, 58, castle-q",
            "b, e8h8, 4, 6, castle-k",
            "b, e8a8, 4, 2, castle-q"
    })
    void decodesBothCastlesForBothSides(String side, String polyglotMove, int from, int to, String reaction)
            throws IOException {
        Board board = new Board("r3k2r/8/8/8/8/8/8/R3K2R " + side + " KQkq - 0 1");
        Move move = probe(board, polyglotMove);
        assertNotNull(move);
        assertEquals(from, move.startSquare);
        assertEquals(to, move.targetSquare);
        assertEquals(reaction, move.reaction);
    }

    @Test
    void rookMoveFromTheKingsHomeSquareRemainsARookMove() throws IOException {
        Board board = new Board("k7/8/8/8/8/8/8/4R1K1 w - - 0 1");
        Move move = probe(board, "e1a1");
        assertNotNull(move);
        assertEquals(60, move.startSquare);
        assertEquals(56, move.targetSquare);
        assertNull(move.reaction);
    }

    @Test
    void castlingEncodingCannotBypassTheLegalMoveFilter() throws IOException {
        Board board = new Board("r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1");
        assertNull(probe(board, "e1h1"));
    }

    @Test
    void extractedFileIsDeletedAfterCloseOrReplacementButRegularFileIsKept() throws IOException {
        OpeningBook book = new OpeningBook(0);
        Set<Path> existing = extractedBooks();
        Path regularBook = Files.createFile(tempDir.resolve("regular.bin"));
        try {
            assertTrue(book.openFromClasspath("valid-test-book.bin"));
            Set<Path> created = extractedBooks();
            created.removeAll(existing);
            assertEquals(1, created.size());

            assertTrue(book.openFromClasspath("/valid-test-book.bin"));
            Set<Path> current = extractedBooks();
            current.removeAll(existing);
            assertEquals(1, current.size());
            assertFalse(created.equals(current));
            assertFalse(Files.exists(created.iterator().next()));

            book.close();
            assertEquals(existing, extractedBooks());
            book.open(regularBook);
        } finally {
            book.close();
        }
        assertTrue(Files.exists(regularBook));
    }

    @Test
    void failedClasspathOpenDeletesTheExtractedFile() throws IOException {
        OpeningBook book = new OpeningBook(0);
        Set<Path> existing = extractedBooks();
        assertFalse(book.openFromClasspath("invalid-test-book.bin"));
        assertFalse(book.isOpen());
        assertEquals(existing, extractedBooks());
    }

    private Move probe(Board board, String move) throws IOException {
        Path file = tempDir.resolve("moves.bin");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeLong(PolyglotKey.compute(board));
            output.writeShort(square(move, 0) << 6 | square(move, 2));
            output.writeShort(1);
            output.writeInt(0);
        }
        OpeningBook book = new OpeningBook(0);
        try {
            book.open(file);
            return book.probe(board);
        } finally {
            book.close();
        }
    }

    private static int square(String move, int offset) {
        return (move.charAt(offset + 1) - '1') * 8 + move.charAt(offset) - 'a';
    }

    private static Set<Path> extractedBooks() throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var paths = Files.list(temp)) {
            return new HashSet<>(paths.filter(p -> p.getFileName().toString().startsWith("vex-book-")).toList());
        }
    }
}
